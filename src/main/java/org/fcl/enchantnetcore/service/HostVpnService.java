package org.fcl.enchantnetcore.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.content.Intent;
import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.fcl.enchantnetcore.R;
import org.fcl.enchantnetcore.easytier.EasyTierAPI;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Host VPN Service with three failure types and persistent connect/connected notices.
 */
public class HostVpnService extends VpnService {

    // ===== Logging =====
    private static final String TAG = "HostVpnService";

    // ===== Package Name =====
    private static final String PREFIX = (LanScanService.class.getPackage() != null)
            ? Objects.requireNonNull(LanScanService.class.getPackage()).getName()
            : "org.fcl.enchantnetcore.service";

    // ===== Actions & Extras =====
    public static final String ACTION_START                   = PREFIX + ".HOST_START";

    public static final String ACTION_CALLBACK_SUCCESS        = PREFIX + ".HOST_CALLBACK_SUCCESS";
    public static final String ACTION_CALLBACK_FAIL           = PREFIX + ".HOST_CALLBACK_FAIL";
    public static final String ACTION_CALLBACK_STOP           = PREFIX + ".HOST_CALLBACK_STOP";
    public static final String ACTION_COPY_INVITE             = PREFIX + ".ACTION_COPY_INVITE_CODE";

    public static final String EXTRA_FAIL_REASON              = "reason"; // "TIMEOUT" | "START_ERROR" | "CONNECTION_LOST" | "EASYTIER_CRASH"

    public static final String EXTRA_GAME_PORT                = "game_port";      // required
    public static final String EXTRA_NETWORK_NAME             = "network_name";   // required
    public static final String EXTRA_NETWORK_KEY              = "network_secret"; // required
    public static final String EXTRA_INVITE_CODE              = "invite_code";    // optional (for Copy button)

    // Customizable notification params (optional)
    public static final String EXTRA_NOTIF_ICON_RES_ID        = "notif_icon_res_id";
    public static final String EXTRA_NOTIF_CONNECTING_TITLE   = "notif_connecting_title";
    public static final String EXTRA_NOTIF_CONNECTING_TEXT    = "notif_connecting_text";
    public static final String EXTRA_NOTIF_CONNECTED_TITLE    = "notif_connected_title";
    public static final String EXTRA_NOTIF_CONNECTED_TEXT     = "notif_connected_text";
    // Three failure buckets (boot fail, ET crash, conn lost)
    public static final String EXTRA_NOTIF_FAIL_BOOT_TITLE    = "notif_fail_boot_title";
    public static final String EXTRA_NOTIF_FAIL_BOOT_TEXT     = "notif_fail_boot_text";
    public static final String EXTRA_NOTIF_FAIL_CRASH_TITLE   = "notif_fail_crash_title";
    public static final String EXTRA_NOTIF_FAIL_CRASH_TEXT    = "notif_fail_crash_text";
    public static final String EXTRA_NOTIF_FAIL_CONN_TITLE    = "notif_fail_conn_title";
    public static final String EXTRA_NOTIF_FAIL_CONN_TEXT     = "notif_fail_conn_text";
    public static final String EXTRA_BTN_EXIT_TEXT            = "notif_btn_exit_text";
    public static final String EXTRA_BTN_COPY_TEXT            = "notif_btn_copy_text";

    // ===== Host network constants =====
    private static final String INSTANCE_NAME_PREFIX          = "EnchantNet-Host-";
    private static final String HOST_IPV4                     = "10.144.144.1";
    private static final int    HOST_PREFIX                   = 24;
    private static final String HOST_NET                      = "10.144.144.0";

    // ===== Probing cadence =====
    private static final long CHECK_CONN_INTERVAL_MS          = 200L;
    private static final long IS_ALIVE_INTERVAL_MS            = 1000L;
    private static final long BOOT_TIMEOUT_MS                 = 10_000L;
    private static final int  CONN_FAILS_TO_LOST              = 3;

    // ===== Notification =====
    private static final String CHANNEL_ID                    = "enchantnet_channel";
    private static final int NOTIF_ID                         = 20011;
    private static final int REQ_STOP                         = 10001;
    private static final String ACTION_REPOST                 = PREFIX + ".HOST_NOTIF_REPOST";
    private static final String ACTION_REQ_STOP               = PREFIX + ".HOST_REQ_STOP";

    private BroadcastReceiver stopReqReceiver;

    // ===== State =====
    private ScheduledExecutorService exec;
    private ScheduledFuture<?> bootTimeoutTask;
    private ScheduledFuture<?> connTask;
    private ScheduledFuture<?> aliveTask;

    private volatile boolean established = false;
    private final AtomicBoolean connOK  = new AtomicBoolean(false);
    private final AtomicBoolean aliveOK = new AtomicBoolean(false);
    private final AtomicInteger  connFailStreak = new AtomicInteger(0);
    private volatile boolean selfStop = false;

    private boolean failureShown = false; // show clearable failure notice and keep it after service stop

    private ParcelFileDescriptor vpnPfd;
    private ParcelFileDescriptor tunDupPfd;

    private int    gamePort;
    private String inviteCode;
    private String instanceName;

    // Customizable notification params
    private int notifIconResId;
    private String connectingTitle, connectingText;
    private String connectedTitle, connectedText;
    private String failBootTitle, failBootText;
    private String failCrashTitle, failCrashText;
    private String failConnTitle, failConnText;
    private String exitBtnText,  copyBtnText;

    // ===== Static helpers =====
    public static void startHost(Context ctx, int gamePort, String netName, String netSecret, @Nullable String inviteCode) {
        Intent i = new Intent(ctx, HostVpnService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_GAME_PORT, gamePort)
                .putExtra(EXTRA_NETWORK_NAME, netName)
                .putExtra(EXTRA_NETWORK_KEY, netSecret)
                .putExtra(EXTRA_INVITE_CODE, inviteCode);
        Log.d(TAG, "startHost(default) gamePort=" + gamePort + ", netName=" + netName);
        ContextCompat.startForegroundService(ctx, i);
    }

    public static void startHost(
            Context ctx, int gamePort, String netName, String netSecret, @Nullable String inviteCode,
            int notifIconResId,
            @Nullable String connectingTitle, @Nullable String connectingText,
            @Nullable String connectedTitle,  @Nullable String connectedText,
            @Nullable String failBootTitle,   @Nullable String failBootText,
            @Nullable String failCrashTitle,  @Nullable String failCrashText,
            @Nullable String failConnTitle,   @Nullable String failConnText,
            @Nullable String exitBtnText,     @Nullable String copyBtnText
    ) {
        Intent i = new Intent(ctx, HostVpnService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_GAME_PORT,              gamePort)
                .putExtra(EXTRA_NETWORK_NAME,           netName)
                .putExtra(EXTRA_NETWORK_KEY,            netSecret)
                .putExtra(EXTRA_INVITE_CODE,            inviteCode)
                .putExtra(EXTRA_NOTIF_ICON_RES_ID,      notifIconResId)
                .putExtra(EXTRA_NOTIF_CONNECTING_TITLE, connectingTitle)
                .putExtra(EXTRA_NOTIF_CONNECTING_TEXT,  connectingText)
                .putExtra(EXTRA_NOTIF_CONNECTED_TITLE,  connectedTitle)
                .putExtra(EXTRA_NOTIF_CONNECTED_TEXT,   connectedText)
                .putExtra(EXTRA_NOTIF_FAIL_BOOT_TITLE,  failBootTitle)
                .putExtra(EXTRA_NOTIF_FAIL_BOOT_TEXT,   failBootText)
                .putExtra(EXTRA_NOTIF_FAIL_CRASH_TITLE, failCrashTitle)
                .putExtra(EXTRA_NOTIF_FAIL_CRASH_TEXT,  failCrashText)
                .putExtra(EXTRA_NOTIF_FAIL_CONN_TITLE,  failConnTitle)
                .putExtra(EXTRA_NOTIF_FAIL_CONN_TEXT,   failConnText)
                .putExtra(EXTRA_BTN_EXIT_TEXT,          exitBtnText)
                .putExtra(EXTRA_BTN_COPY_TEXT,          copyBtnText);
        Log.d(TAG, "startHost(custom) gamePort=" + gamePort + ", netName=" + netName + ", customNotifIcon=" + notifIconResId);
        ContextCompat.startForegroundService(ctx, i);
    }

    /** Proactively stop; sends STOP callback (not FAIL). */
    public static void stopHost(Context ctx) {
        Log.d(TAG, "stopHost() called");
        ctx.sendBroadcast(new Intent(HostVpnService.ACTION_REQ_STOP).setPackage(ctx.getPackageName()));
    }

    // ===== Lifecycle =====

    @Override
    public void onCreate() {
        super.onCreate();
        stopReqReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent it) {
                selfStop = true;
                failureShown = false;
                shutdown("USER_STOP_BUTTON");
            }
        };
        IntentFilter f = new IntentFilter(ACTION_REQ_STOP);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stopReqReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stopReqReceiver, f);
        }
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand() flags=" + flags + ", startId=" + startId + ", intent=" + (intent == null ? "null" : intent.getAction()));
        if (intent == null) return START_NOT_STICKY;

        final String action = intent.getAction();
        if (ACTION_REPOST.equals(action)) {
            Log.d(TAG, "Notification repost; refreshing foreground");
            startForeground(
                    NOTIF_ID,
                    established ? buildConnectedNotification() : buildConnectingNotification()
            );
            return START_NOT_STICKY;
        }
        if (!Objects.equals(action, ACTION_START)) {
            Log.w(TAG, "Unknown action: " + action + " -> stopSelf()");
            stopSelf();
            return START_NOT_STICKY;
        }

        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
        Log.d(TAG, "Cleared any previous notification id=" + NOTIF_ID);

        // Inputs
        gamePort      = intent.getIntExtra(EXTRA_GAME_PORT, -1);
        String networkName = intent.getStringExtra(EXTRA_NETWORK_NAME);
        String networkSecret = intent.getStringExtra(EXTRA_NETWORK_KEY);
        inviteCode    = intent.getStringExtra(EXTRA_INVITE_CODE);
        instanceName  = INSTANCE_NAME_PREFIX + networkSecret;
        Log.d(TAG, "Start params: port=" + gamePort + ", netName=" + networkName + ", instanceName=" + instanceName
                + ", hasInvite=" + (inviteCode != null));

        // Notification params + defaults
        notifIconResId  = intent.getIntExtra(EXTRA_NOTIF_ICON_RES_ID, R.drawable.enchantnet);
        connectingTitle = orDefault(intent.getStringExtra(EXTRA_NOTIF_CONNECTING_TITLE), "EnchantNet: connecting…");
        connectingText  = orDefault(intent.getStringExtra(EXTRA_NOTIF_CONNECTING_TEXT),  "Starting EasyTier and probing game port");
        connectedTitle  = orDefault(intent.getStringExtra(EXTRA_NOTIF_CONNECTED_TITLE),  "EnchantNet: connected");
        connectedText   = orDefault(intent.getStringExtra(EXTRA_NOTIF_CONNECTED_TEXT),   "VPN established. Game is reachable.");
        failBootTitle   = orDefault(intent.getStringExtra(EXTRA_NOTIF_FAIL_BOOT_TITLE),  "Failed to connect");
        failBootText    = orDefault(intent.getStringExtra(EXTRA_NOTIF_FAIL_BOOT_TEXT),   "Could not start within time");
        failCrashTitle  = orDefault(intent.getStringExtra(EXTRA_NOTIF_FAIL_CRASH_TITLE), "Disconnected: EasyTier crashed");
        failCrashText   = orDefault(intent.getStringExtra(EXTRA_NOTIF_FAIL_CRASH_TEXT),  "Please restart");
        failConnTitle   = orDefault(intent.getStringExtra(EXTRA_NOTIF_FAIL_CONN_TITLE),  "Disconnected: game unreachable");
        failConnText    = orDefault(intent.getStringExtra(EXTRA_NOTIF_FAIL_CONN_TEXT),   "Lost connection to game port");
        exitBtnText     = orDefault(intent.getStringExtra(EXTRA_BTN_EXIT_TEXT),          "Exit");
        copyBtnText     = orDefault(intent.getStringExtra(EXTRA_BTN_COPY_TEXT),          "Copy Invite Code");

        if (gamePort <= 0 || isEmpty(networkName) || isEmpty(networkSecret)) {
            Log.e(TAG, "Invalid start params. port=" + gamePort + ", nameEmpty=" + isEmpty(networkName) + ", keyEmpty=" + isEmpty(networkSecret));
            sendFail("START_ERROR");
            stopSelf();
            return START_NOT_STICKY;
        }

        // Foreground: persistent, non-clearable "connecting" notice
        startForeground(NOTIF_ID, buildConnectingNotification());
        Log.d(TAG, "Foreground started with connecting notification. notifId=" + NOTIF_ID);

        exec = Executors.newScheduledThreadPool(3);
        Log.d(TAG, "ScheduledExecutorService initialized.");

        // Build TUN route
        Log.d(TAG, "Building VpnService.Builder...");
        Builder b = new Builder()
                .setSession("EnchantNet Host")
                .setMtu(1300)
                .setBlocking(false)
                .addAddress(HOST_IPV4, HOST_PREFIX)
                .addRoute(HOST_NET, HOST_PREFIX);
        try { b.addDisallowedApplication(getPackageName()); } catch (Exception ignored) {}
        vpnPfd = b.establish();
        if (vpnPfd == null) {
            Log.e(TAG, "VPN establish() failed");
            sendFail("START_ERROR");
            stopSelf();
            return START_NOT_STICKY;
        }

        // Start EasyTier Host
        int rc = EasyTierAPI.startEasyTierHost(instanceName, networkName, networkSecret);
        Log.d(TAG, "startEasytierHost rc=" + rc);
        if (rc != 0) {
            Log.e(TAG, "Failed to start EasyTier host instance.");
            sendFail("START_ERROR");
            shutdown("START_ET_FAIL");
            return START_NOT_STICKY;
        }

        // Boot probing
        startBootProbing();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "onDestroy()");
        shutdown("onDestroy");
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        final String action = intent != null ? intent.getAction() : null;
        if (VpnService.SERVICE_INTERFACE.equals(action)) {
            return super.onBind(intent);
        }
        return null;
    }

    @Override
    public void onRevoke() {
        Log.w(TAG, "onRevoke(): preempted by another VPN or revoked by user; tearing down.");
        selfStop = true;
        failureShown = false;
        shutdown("ON_REVOKE");
    }

    // ===== Probing =====
    private void startBootProbing() {
        Log.d(TAG, "startBootProbing(): scheduling boot timeout and probes.");

        // One-shot boot timeout
        bootTimeoutTask = exec.schedule(() -> {
            if (!established) {
                Log.e(TAG, "Boot timeout reached (" + BOOT_TIMEOUT_MS + " ms).");
                sendFail("TIMEOUT");
                shutdown("BOOT_TIMEOUT");
            }
        }, BOOT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        // checkConn every 200ms
        connTask = exec.scheduleWithFixedDelay(() -> {
            boolean ok = EasyTierAPI.checkConn(gamePort, (int) CHECK_CONN_INTERVAL_MS);
            if (ok) {
                if (!connOK.get()) Log.d(TAG, "checkConn OK.");
                connOK.set(true);
                connFailStreak.set(0);
            } else {
                if (established) {
                    int streak = connFailStreak.incrementAndGet();
                    Log.w(TAG, "checkConn FAILED. streak=" + streak + "/" + CONN_FAILS_TO_LOST);
                    if (streak >= CONN_FAILS_TO_LOST) {
                        Log.e(TAG, "checkConn failure threshold reached -> CONNECTION_LOST.");
                        sendFail("CONNECTION_LOST");
                        shutdown("CONN_3X_FAIL");
                    }
                } else {
                    if (connOK.get()) Log.d(TAG, "checkConn transitioned to NOT OK (boot phase).");
                    connOK.set(false);
                }
            }
            tryFinishBootIfReady();
        }, 0, CHECK_CONN_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // isAlive every 1s
        aliveTask = exec.scheduleWithFixedDelay(() -> {
            boolean ok = EasyTierAPI.isAlive();
            if (ok) {
                if (!aliveOK.get()) Log.d(TAG, "isAlive OK.");
                aliveOK.set(true);
            } else {
                Log.w(TAG, "isAlive FAILED.");
                aliveOK.set(false);
                if (established) {
                    Log.e(TAG, "EasyTier crashed after established -> EASYTIER_CRASH.");
                    sendFail("EASYTIER_CRASH");
                    shutdown("ALIVE_FAIL");
                }
            }
            tryFinishBootIfReady();
        }, 0, IS_ALIVE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void tryFinishBootIfReady() {
        if (established) return;
        if (connOK.get() && aliveOK.get()) {
            Log.i(TAG, "Both probes passed during boot. Proceeding to setTunFd().");
            if (doSetTunFd()) {
                established = true;
                Log.i(TAG, "setTunFd success; VPN established.");
                sendSuccess();
                updateNotification(buildConnectedNotification()); // now persistent, non-clearable
            } else {
                Log.e(TAG, "setTunFd failed.");
                sendFail("START_ERROR");
                shutdown("SET_TUN_FD_FAIL");
            }
        }
    }

    // ===== VPN + setTunFd =====
    private boolean doSetTunFd() {
        try {
            tunDupPfd = ParcelFileDescriptor.dup(vpnPfd.getFileDescriptor());
            int setRc = EasyTierAPI.setTunFd(instanceName, tunDupPfd);
            if (setRc != 0) {
                Log.e(TAG, "setTunFd rc=" + setRc);
                return false;
            }
            Log.i(TAG, "setTunFd OK");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "setTunFd exception", t);
            return false;
        }
    }

    // ===== Persistent notices =====
    private Notification buildConnectingNotification() {
        ensureChannel();
        PendingIntent deletePending = PendingIntent.getService(
                this, 2001,
                new Intent(this, HostVpnService.class).setAction(ACTION_REPOST),
                PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(notifIconResId)
                .setOngoing(true)
                .setContentTitle(connectingTitle)
                .setContentText(connectingText)
                .setDeleteIntent(deletePending);

        if (android.os.Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }


    private Notification buildConnectedNotification() {
        ensureChannel();
        PendingIntent deletePending = PendingIntent.getService(
                this, 2002,
                new Intent(this, HostVpnService.class).setAction(ACTION_REPOST),
                PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(notifIconResId)
                .setOngoing(true)
                .setContentTitle(connectedTitle)
                .setContentText(connectedText)
                .setDeleteIntent(deletePending);

        // Exit button
        PendingIntent stopPi = PendingIntent.getBroadcast(
                this,
                REQ_STOP,
                new Intent(ACTION_REQ_STOP).setPackage(getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        b.addAction(new Notification.Action.Builder(null, exitBtnText, stopPi).build());

        // Copy invite button
        if (!isEmpty(inviteCode)) {
            Intent copy = new Intent(ACTION_COPY_INVITE).setPackage(getPackageName()).putExtra("invite_code", inviteCode);
            PendingIntent copyPi = PendingIntent.getBroadcast(
                    this,
                    (int) (System.currentTimeMillis() & 0x7fffffff),
                    copy,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT
            );
            b.addAction(new Notification.Action.Builder(null, copyBtnText, copyPi).build());
        }
        Log.d(TAG, "buildConnectedNotification()");
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private void updateNotification(Notification n) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, n);
        Log.d(TAG, "updateNotification() id=" + NOTIF_ID + (established ? " [connected]" : " [connecting]"));
    }

    /** Re-notify current persistent notice every 5s (connecting/connected only). */
    private void ensurePersistentNotice() {
        if (failureShown) {
            Log.d(TAG, "ensurePersistentNotice(): failureShown=true, skip.");
            return;
        }
        if (established) {
            Log.d(TAG, "ensurePersistentNotice(): repost connected notice.");
            updateNotification(buildConnectedNotification());
        } else {
            Log.d(TAG, "ensurePersistentNotice(): repost connecting notice.");
            updateNotification(buildConnectingNotification());
        }
    }

    private void ensureChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "EnchantNet", NotificationManager.IMPORTANCE_LOW));
    }

    // ===== Failure notices (clearable) =====
    private void showFailureNoticeBoot() {
        failureShown = true;
        // Remove foreground state but keep an app-level clearable failure notification
        stopForeground(true);
        ensureChannel();
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID);
        b.setSmallIcon(notifIconResId)
         .setOngoing(false) // clearable
         .setContentTitle(failBootTitle)
         .setContentText(failBootText);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, b.build());
        Log.e(TAG, "showFailureNoticeBoot()");
    }

    private void showFailureNoticeCrash() {
        failureShown = true;
        stopForeground(true);
        ensureChannel();
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID);
        b.setSmallIcon(notifIconResId)
         .setOngoing(false)
         .setContentTitle(failCrashTitle)
         .setContentText(failCrashText);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, b.build());
        Log.e(TAG, "showFailureNoticeCrash()");
    }

    private void showFailureNoticeConn() {
        failureShown = true;
        stopForeground(true);
        ensureChannel();
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID);
        b.setSmallIcon(notifIconResId)
         .setOngoing(false)
         .setContentTitle(failConnTitle)
         .setContentText(failConnText);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, b.build());
        Log.e(TAG, "showFailureNoticeConn()");
    }

    // ===== Callbacks =====
    private void sendSuccess() {
        Intent i = new Intent(ACTION_CALLBACK_SUCCESS).setPackage(getPackageName());
        i.putExtra(EXTRA_GAME_PORT, gamePort);
        i.putExtra(EXTRA_INVITE_CODE, inviteCode);
        sendBroadcast(i);
        Log.i(TAG, "Callback SUCCESS sent. port=" + gamePort);
    }

    private void sendFail(String reason) {
        if (selfStop) {
            Log.d(TAG, "sendFail skipped due to selfStop=true. reason=" + reason);
            return;
        }
        switch (reason) {
            case "EASYTIER_CRASH": showFailureNoticeCrash(); break;
            case "CONNECTION_LOST": showFailureNoticeConn(); break;
            case "TIMEOUT": default: showFailureNoticeBoot(); break;
        }
        Intent i = new Intent(ACTION_CALLBACK_FAIL).setPackage(getPackageName());
        i.putExtra(EXTRA_FAIL_REASON, reason);
        sendBroadcast(i);
        Log.i(TAG, "Callback FAIL sent. reason=" + reason);
    }

    private void sendStop() {
        Intent i = new Intent(ACTION_CALLBACK_STOP).setPackage(getPackageName());
        i.putExtra(EXTRA_INVITE_CODE, inviteCode);
        sendBroadcast(i);
        Log.i(TAG, "Callback STOP sent.");
    }

    // ===== Teardown =====
    private synchronized void shutdown(String tag) {
        Log.w(TAG, "shutdown() tag=" + tag + " selfStop=" + selfStop + " established=" + established + " failureShown=" + failureShown);

        try { unregisterReceiver(stopReqReceiver); } catch (Throwable ignore) {}

        try { if (bootTimeoutTask != null) bootTimeoutTask.cancel(true); } catch (Throwable t) { Log.w(TAG, "cancel bootTimeoutTask err", t); }
        try { if (connTask != null)        connTask.cancel(true); }      catch (Throwable t) { Log.w(TAG, "cancel connTask err", t); }
        try { if (aliveTask != null)       aliveTask.cancel(true); }     catch (Throwable t) { Log.w(TAG, "cancel aliveTask err", t); }
        try { if (exec != null)            exec.shutdownNow(); }         catch (Throwable t) { Log.w(TAG, "exec.shutdownNow err", t); }

        try { if (tunDupPfd != null) { Log.d(TAG, "Closing dup FD " + tunDupPfd.getFd()); tunDupPfd.close(); } } catch (Throwable t) { Log.w(TAG, "close dup fd err", t); }
        tunDupPfd = null;
        try { if (vpnPfd != null) { Log.d(TAG, "Closing vpn FD " + vpnPfd.getFd()); vpnPfd.close(); } } catch (Throwable t) { Log.w(TAG, "close vpn fd err", t); }
        vpnPfd = null;

        try {
            boolean ret = EasyTierAPI.stopEasyTier();
            if (!ret)
                Log.w(TAG, "The Easytier instance might not have terminated properly.");
            else
                Log.d(TAG, "Easytier stopped.");
        } catch (Throwable t) {
            Log.e(TAG, "stopEasytier threw: " + t.getMessage(), t);
        }

        if (selfStop) {
            // proactive stop callback; remove any notice
            sendStop();
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
            Log.d(TAG, "Canceled notification id=" + NOTIF_ID + " due to selfStop.");
        }
        // failureShown -> keep failure notice; do NOT cancel here

        stopSelf();
        Log.d(TAG, "stopSelf() done.");
    }

    // ===== Utils =====
    private static boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }
    private static String orDefault(String v, String def) { return (v == null || v.trim().isEmpty()) ? def : v; }
}
