package org.fcl.enchantnetcore;

import static android.text.TextUtils.isEmpty;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.fcl.enchantnetcore.core.InviteCode;
import org.fcl.enchantnetcore.core.Room;
import org.fcl.enchantnetcore.service.GuestVpnService;
import org.fcl.enchantnetcore.service.HostVpnService;
import org.fcl.enchantnetcore.service.LanScanService;
import org.fcl.enchantnetcore.state.EnchantNetException;
import org.fcl.enchantnetcore.state.EnchantNetRole;
import org.fcl.enchantnetcore.state.EnchantNetSnapshot;
import org.fcl.enchantnetcore.state.EnchantNetState;
import org.fcl.enchantnetcore.state.EnchantNetStateListener;
import org.fcl.enchantnetcore.state.GuestNoticeRes;
import org.fcl.enchantnetcore.state.HostNoticeRes;
import org.fcl.enchantnetcore.state.LanScanNoticeRes;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * EnchantNet – single-instance state orchestrator for Host/Guest flows.
 * - Host: WAITING → SCANNING → HOSTING; any error → EXCEPTION; manual stop → WAITING
 * - Guest: WAITING → GUESTING (connecting/connected); any error → EXCEPTION; manual stop → WAITING
 * <p>
 * VPN consent (VpnService.prepare) is requested before starting VPN services.
 */
public final class EnchantNet {

    // ===== Singleton =====
    private static EnchantNet s;

    public static synchronized EnchantNet init(@NonNull Context appContext,
                                               @NonNull LanScanNoticeRes lanScan,
                                               @NonNull HostNoticeRes host,
                                               @NonNull GuestNoticeRes guest) {
        if (s == null) {
            s = new EnchantNet((Application) appContext.getApplicationContext(), lanScan, host, guest);
        }
        return s;
    }

    public static EnchantNet get() {
        if (s == null)
            throw new IllegalStateException("Call EnchantNet.init(...) first.");
        return s;
    }

    private final Application app;
    private final LanScanNoticeRes lanScanNotice;
    private final HostNoticeRes hostNotice;
    private final GuestNoticeRes guestNotice;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<EnchantNetStateListener> listeners = new HashSet<>();

    private boolean receiversRegistered = false;

    // state
    private volatile boolean running = false;
    private volatile EnchantNetState state = EnchantNetState.WAITING;
    private volatile EnchantNetRole role = EnchantNetRole.NONE;
    @Nullable
    private volatile EnchantNetException exception = null;
    @Nullable
    private volatile String message = null;
    @Nullable
    private volatile String inviteCode = null;
    @Nullable
    private volatile String backupServer = null;

    private EnchantNet(Application app, LanScanNoticeRes ls, HostNoticeRes hn, GuestNoticeRes gn) {
        this.app = app;
        this.lanScanNotice = ls;
        this.hostNotice = hn;
        this.guestNotice = gn;
    }

    // ===== Observe =====
    public void addListener(@NonNull EnchantNetStateListener l) {
        listeners.add(l);
        l.onStateChanged(getStateSnapshot());
    }

    public void removeListener(@NonNull EnchantNetStateListener l) {
        listeners.remove(l);
    }

    public synchronized EnchantNetSnapshot getStateSnapshot() {
        return new EnchantNetSnapshot(state, role, exception, message, inviteCode, backupServer, System.currentTimeMillis());
    }

    // ===== Public: startHost() =====
    @MainThread
    public synchronized boolean startHost() {
        if (running || state != EnchantNetState.WAITING)
            return false;
        running = true;
        role = EnchantNetRole.HOST;
        exception = null;
        message = null;
        inviteCode = null;
        backupServer = null;
        state = EnchantNetState.SCANNING;
        emit();

        ensureReceivers(true);

        // Start LAN scan immediately; VPN consent will be requested right before starting Host VPN.
        kickoffScanning();
        return true;
    }

    // ===== Public: startGuest(invite) =====
    @MainThread
    public synchronized boolean startGuest(@NonNull String invite) {
        if (running || state != EnchantNetState.WAITING)
            return false;
        running = true;
        role = EnchantNetRole.GUEST;
        exception = null;
        message = null;
        inviteCode = invite;
        backupServer = null;
        state = EnchantNetState.GUESTING;
        emit();

        ensureReceivers(true);

        // VPN consent first, then start Guest VPN service.
        startGuestService(invite);
        return true;
    }

    // ===== Public: stop() =====
    @MainThread
    public synchronized void stop() {
        if (!running && state == EnchantNetState.WAITING)
            return;
        if (role == EnchantNetRole.HOST) {
            try {
                HostVpnService.stopHost(app);
            } catch (Throwable ignored) {
            }
            stopLanScanService();
        } else if (role == EnchantNetRole.GUEST) {
            try {
                GuestVpnService.stopGuest(app);
            } catch (Throwable ignored) {
            }
        }
        try {
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            nm.cancelAll();
        } catch (Throwable ignore) {
        }
        resetToWaiting("manual_stop");
    }

    // ===== Host flow: scanning → invite → VPN → HostVpnService =====
    private final ResultReceiver scanReceiver = new ResultReceiver(main) {
        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            if (resultCode == Activity.RESULT_OK) {
                int port = resultData != null ? resultData.getInt(LanScanService.RESULT_KEY_PORT, -1) : -1;
                if (port > 0)
                    onGamePortFound(port);
                else
                    failStart("scan_bad_port");
            } else {
                String err = resultData != null ? resultData.getString(LanScanService.RESULT_KEY_ERROR) : "scan_failed";
                assert err != null;
                failStart(err);
            }
        }
    };

    private void kickoffScanning() {
        LanScanService.start(
                app,
                scanReceiver,
                lanScanNotice.icon,
                lanScanNotice.titleScanning,
                lanScanNotice.textScanning
        );
    }

    private void stopLanScanService() {
        try {
            LanScanService.stop(app);
        } catch (Throwable ignored) {
        }
    }

    private void onGamePortFound(int gamePort) {
        String code;
        try {
            code = InviteCode.generateInviteCode(gamePort);
        } catch (Throwable t) {
            failStart("invite_build_error");
            return;
        }
        inviteCode = code; emit();

        Room r = InviteCode.parseInviteCode(code);
        if (r.port <= 0 || isEmpty(r.name) || isEmpty(r.secret)) {
            failStart("invite_parse_error");
            return;
        }

        startHostService(gamePort, r.name, r.secret, code);
    }

    private void startHostService(int gamePort, @NonNull String networkName, @NonNull String networkSecret, @NonNull String code) {
        try {
            HostVpnService.startHost(
                    app,
                    gamePort,
                    networkName,
                    networkSecret,
                    code,
                    hostNotice.icon,
                    hostNotice.titleConnecting, hostNotice.textConnecting,
                    hostNotice.titleConnected,  hostNotice.textConnected,
                    hostNotice.titleFailBoot,   hostNotice.textFailBoot,
                    hostNotice.titleFailCrash,  hostNotice.textFailCrash,
                    hostNotice.titleFailConn,   hostNotice.textFailConn,
                    hostNotice.btnExit,         hostNotice.btnCopy
            );
        } catch (Throwable t) {
            failStart("host_start_error");
        }
    }

    // ===== Guest flow: parse invite → GuestVpnService =====
    private void startGuestService(@NonNull String invite) {
        Room room = InviteCode.parseInviteCode(invite);
        if (room.port <= 0 || isEmpty(room.name) || isEmpty(room.secret) || room.roomKind == null) {
            failStart("invite_parse_error");
            return;
        }

        try {
            GuestVpnService.startGuest(
                    app,
                    room.name,
                    room.secret,
                    room.port,
                    room.roomKind,
                    guestNotice.icon,
                    guestNotice.titleConnecting, guestNotice.textConnecting,
                    guestNotice.titleConnected,  guestNotice.textConnected,
                    guestNotice.titleFailBoot,   guestNotice.textFailBoot,
                    guestNotice.titleFailCrash,  guestNotice.textFailCrash,
                    guestNotice.titleFailConn,   guestNotice.textFailConn,
                    guestNotice.btnExit,
                    guestNotice.motd
            );
        } catch (Throwable t) {
            failStart("guest_start_error");
        }
    }

    // ===== Broadcast callbacks from Host/Guest services =====
    private void ensureReceivers(boolean register) {
        if (register == receiversRegistered) return;
        receiversRegistered = register;
        if (register) {
            IntentFilter f = new IntentFilter();
            // Host callbacks
            f.addAction(HostVpnService.ACTION_CALLBACK_SUCCESS);
            f.addAction(HostVpnService.ACTION_CALLBACK_FAIL);
            f.addAction(HostVpnService.ACTION_CALLBACK_STOP);
            f.addAction(HostVpnService.ACTION_COPY_INVITE);
            // Guest callbacks
            f.addAction(GuestVpnService.ACTION_CALLBACK_SUCCESS);
            f.addAction(GuestVpnService.ACTION_CALLBACK_FAIL);
            f.addAction(GuestVpnService.ACTION_CALLBACK_STOP);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(receiver, f);
            }
        } else {
            try { app.unregisterReceiver(receiver); } catch (Throwable ignored) {}
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent i) {
            String a = i.getAction();
            if (HostVpnService.ACTION_CALLBACK_SUCCESS.equals(a)) {
                state = EnchantNetState.HOSTING; exception = null; message = null; emit();
            } else if (HostVpnService.ACTION_CALLBACK_FAIL.equals(a)) {
                mapHostFail(i.getStringExtra(HostVpnService.EXTRA_FAIL_REASON)); emit();
            } else if (HostVpnService.ACTION_CALLBACK_STOP.equals(a)) {
                resetToWaiting("host_stop");
            } else if (HostVpnService.ACTION_COPY_INVITE.equals(a)) {
                String code = i.getStringExtra("invite_code");
                if (code != null) {
                    copyInviteCode(code);
                }
            } else if (GuestVpnService.ACTION_CALLBACK_SUCCESS.equals(a)) {
                state = EnchantNetState.GUESTING; exception = null; message = null;
                backupServer = i.getStringExtra(GuestVpnService.EXTRA_BACKUP_SERVER);
                emit();
            } else if (GuestVpnService.ACTION_CALLBACK_FAIL.equals(a)) {
                mapGuestFail(i.getStringExtra(GuestVpnService.EXTRA_FAIL_REASON)); emit();
            } else if (GuestVpnService.ACTION_CALLBACK_STOP.equals(a)) {
                resetToWaiting("guest_stop");
            }
        }
    };

    private void mapHostFail(@Nullable String reason) {
        state = EnchantNetState.EXCEPTION; message = reason;
        if (Objects.equals("EASYTIER_CRASH", reason))
            exception = EnchantNetException.HOST_EASYTIER_CRASH;
        else if (Objects.equals("CONNECTION_LOST", reason))
            exception = EnchantNetException.HOST_GAME_CLOSED;
        else
            exception = EnchantNetException.START_FAILED;
    }

    private void mapGuestFail(@Nullable String reason) {
        state = EnchantNetState.EXCEPTION; message = reason;
        if (Objects.equals("EASYTIER_CRASH", reason))
            exception = EnchantNetException.GUEST_EASYTIER_CRASH;
        else if (Objects.equals("CONNECTION_LOST", reason))
            exception = EnchantNetException.GUEST_CONN_LOST;
        else
            exception = EnchantNetException.START_FAILED;
    }

    private void failStart(@NonNull String why) {
        state = EnchantNetState.EXCEPTION;
        exception = EnchantNetException.START_FAILED;
        message = why;
        emit();
    }

    private void resetToWaiting(@NonNull String why) {
        ensureReceivers(false);
        state = EnchantNetState.WAITING;
        role = EnchantNetRole.NONE;
        exception = null;
        message = null;
        inviteCode = null;
        backupServer = null;
        running = false;
        emit();
    }

    private void emit() {
        EnchantNetSnapshot s = getStateSnapshot();
        for (EnchantNetStateListener l : listeners.toArray(new EnchantNetStateListener[0])) {
            try {
                l.onStateChanged(s);
            } catch (Throwable ignored) {
            }
        }
    }

    private void copyInviteCode(String inviteCode) {
        for (EnchantNetStateListener l : listeners.toArray(new EnchantNetStateListener[0])) {
            try {
                l.onCopyInviteCode(inviteCode);
            } catch (Throwable ignored) {
            }
        }
    }
}
