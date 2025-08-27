package org.fcl.enchantnetcore.service;

import static android.text.TextUtils.isEmpty;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.Context;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.fcl.enchantnetcore.core.FakeLanBroadcaster;
import org.fcl.enchantnetcore.core.RoomKind;
import org.fcl.enchantnetcore.easytier.EasyTierAPI;
import org.fcl.enchantnetcore.utils.IpUtils;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GuestVpnService
 * - Start EasyTier guest instance.
 * - Probe checkConn(localForward) + isAlive() until both are true or timeout.
 * - When established: setTunFd, start FakeLanBroadcaster(MOTD), keep probing.
 * - Failures show a clearable notification; connecting/connected are non-clearable (repost).
 */
public class GuestVpnService extends VpnService {

    // ===== Logging =====
    private static final String TAG = "GuestVpnService";

    // ===== Package Name =====
    private static final String PREFIX = (LanScanService.class.getPackage() != null)
            ? Objects.requireNonNull(LanScanService.class.getPackage()).getName()
            : "org.fcl.enchantnetcore.service";

    // ==== Actions / extras (public API) ====
    public static final String ACTION_START            = PREFIX + ".GUEST_START";

    // callbacks
    public static final String ACTION_CALLBACK_SUCCESS = PREFIX + ".GUEST_CALLBACK_SUCCESS";
    public static final String ACTION_CALLBACK_FAIL    = PREFIX + ".GUEST_CALLBACK_FAIL";
    public static final String ACTION_CALLBACK_STOP    = PREFIX + ".GUEST_CALLBACK_STOP";

    // input extras
    public static final String EXTRA_NETWORK_NAME      = "network_name";
    public static final String EXTRA_NETWORK_KEY       = "network_key";
    public static final String EXTRA_GAME_PORT         = "game_port";    // host game port
    public static final String EXTRA_ROOM_KIND         = "room_kind";    // RoomKind (Serializable)
    public static final String EXTRA_ICON_RES          = "notif_icon";   // @DrawableRes
    public static final String EXTRA_TITLE_CONNECT     = "title_connect";
    public static final String EXTRA_TEXT_CONNECT      = "text_connect";
    public static final String EXTRA_TITLE_OK          = "title_ok";
    public static final String EXTRA_TEXT_OK           = "text_ok";
    public static final String EXTRA_TITLE_FAIL_BOOT   = "title_fail_boot";
    public static final String EXTRA_TEXT_FAIL_BOOT    = "text_fail_boot";
    public static final String EXTRA_TITLE_FAIL_ALIVE  = "title_fail_alive";
    public static final String EXTRA_TEXT_FAIL_ALIVE   = "text_fail_alive";
    public static final String EXTRA_TITLE_FAIL_CONN   = "title_fail_conn";
    public static final String EXTRA_TEXT_FAIL_CONN    = "text_fail_conn";
    public static final String EXTRA_BTN_EXIT_TEXT     = "btn_exit_text";
    public static final String EXTRA_MOTD              = "motd";

    // callback extras
    public static final String EXTRA_FAIL_REASON       = "reason";
    public static final String EXTRA_BACKUP_SERVER     = "backup_server";

    // ==== Guest network constants ====
    private static final String INSTANCE_NAME_PREFIX   = "EnchantNet-Guest-";

    // ==== Overlay subnets (route only; EasyTier assigns real virtual ip) ====
    private static final String TERRACOTTA_NET         = "10.144.144.0";
    private static final String PCL2CE_NET             = "10.114.51.0";

    private static final int   PREFIX_LEN              = 24;
    private static final int   VPN_MTU                 = 1300;

    // ==== Probes ====
    private static final long CHECK_CONN_INTERVAL_MS   = 200L;
    private static final long IS_ALIVE_INTERVAL_MS     = 1000L;
    private static final long BOOT_TIMEOUT_MS          = 10_000L;
    private static final int  CONN_FAILS_TO_LOST       = 3;

    // ==== Notification ====
    private static final String CHANNEL_ID             = "enchantnet_channel";
    private static final int NOTIF_ID                  = 20021;
    private static final int REQ_STOP                  = 20101;
    private static final String ACTION_REPOST          = PREFIX + ".GUEST_NOTIF_REPOST";
    private static final String ACTION_REQ_STOP        = PREFIX + ".GUEST_REQ_STOP";

    private BroadcastReceiver stopReqReceiver;

    // ==== State ====
    private ScheduledExecutorService exec;
    private ScheduledFuture<?> bootTimeoutTask, connTask, aliveTask;

    private volatile boolean established = false;
    private final AtomicBoolean connOK  = new AtomicBoolean(false);
    private final AtomicBoolean aliveOK = new AtomicBoolean(false);
    private final AtomicInteger connFailStreak = new AtomicInteger(0);
    private volatile boolean selfStop = false;

    private ParcelFileDescriptor vpnPfd;    // VpnService establish() fd
    private ParcelFileDescriptor tunDupPfd; // dup passed to native

    private String instanceName;
    private int localForwardPort;
    private int notifIcon;

    private String titleConnecting, textConnecting;
    private String titleConnected,  textConnected;
    private String titleFailBoot,   textFailBoot;
    private String titleFailAlive,  textFailAlive;
    private String titleFailConn,   textFailConn;
    private String exitBtnText;
    private String motd;

    private FakeLanBroadcaster broadcaster;

    // ========================= Public starters =========================

    /**
     * Simple starter: default notification texts, custom MOTD.
     * localForwardPort is generated internally.
     */
    public static void startGuest(Context ctx,
                                  String networkName,
                                  String secret,
                                  int remoteGamePort,
                                  RoomKind kind,
                                  String motd) {
        Intent it = new Intent(ctx, GuestVpnService.class).setAction(ACTION_START);
        it.putExtra(EXTRA_NETWORK_NAME, networkName);
        it.putExtra(EXTRA_NETWORK_KEY, secret);
        it.putExtra(EXTRA_GAME_PORT, remoteGamePort);
        it.putExtra(EXTRA_ROOM_KIND, kind);
        it.putExtra(EXTRA_MOTD, motd);
        ContextCompat.startForegroundService(ctx, it);
    }

    /**
     * Advanced starter: fully customizable icon/titles/texts/MOTD (port still generated internally).
     */
    public static void startGuest(Context ctx,
                                  String networkName,
                                  String secret,
                                  int remoteGamePort,
                                  RoomKind kind,
                                  @DrawableRes int icon,
                                  @Nullable String titleConnecting, @Nullable String textConnecting,
                                  @Nullable String titleConnected,  @Nullable String textConnected,
                                  @Nullable String titleFailBoot,   @Nullable String textFailBoot,
                                  @Nullable String titleFailAlive,  @Nullable String textFailAlive,
                                  @Nullable String titleFailConn,   @Nullable String textFailConn,
                                  @Nullable String exitBtnText,
                                  @Nullable String motd) {
        Intent it = new Intent(ctx, GuestVpnService.class).setAction(ACTION_START);
        it.putExtra(EXTRA_NETWORK_NAME,     networkName);
        it.putExtra(EXTRA_NETWORK_KEY,      secret);
        it.putExtra(EXTRA_GAME_PORT,        remoteGamePort);
        it.putExtra(EXTRA_ROOM_KIND,        kind);
        it.putExtra(EXTRA_ICON_RES,         icon);
        it.putExtra(EXTRA_TITLE_CONNECT,    titleConnecting);
        it.putExtra(EXTRA_TEXT_CONNECT,     textConnecting);
        it.putExtra(EXTRA_TITLE_OK,         titleConnected);
        it.putExtra(EXTRA_TEXT_OK,          textConnected);
        it.putExtra(EXTRA_TITLE_FAIL_BOOT,  titleFailBoot);
        it.putExtra(EXTRA_TEXT_FAIL_BOOT,   textFailBoot);
        it.putExtra(EXTRA_TITLE_FAIL_ALIVE, titleFailAlive);
        it.putExtra(EXTRA_TEXT_FAIL_ALIVE,  textFailAlive);
        it.putExtra(EXTRA_TITLE_FAIL_CONN,  titleFailConn);
        it.putExtra(EXTRA_TEXT_FAIL_CONN,   textFailConn);
        it.putExtra(EXTRA_BTN_EXIT_TEXT,    exitBtnText);
        it.putExtra(EXTRA_MOTD,             motd);
        ContextCompat.startForegroundService(ctx, it);
    }

    public static void stopGuest(Context ctx) {
        ctx.sendBroadcast(new Intent(GuestVpnService.ACTION_REQ_STOP).setPackage(ctx.getPackageName()));
    }

    // ========================= Lifecycle =========================

    @Override
    public void onCreate() {
        super.onCreate();
        stopReqReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent it) {
                selfStop = true;
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
        if (intent == null) return START_NOT_STICKY;

        String act = intent.getAction();
        if (ACTION_REPOST.equals(act)) {
            if (vpnPfd != null) {
                Log.d(TAG, "Notification repost; refreshing foreground");
                startForeground(NOTIF_ID, established ? buildConnectedNotification() : buildConnectingNotification());
            }
            return START_NOT_STICKY;
        }
        if (!Objects.equals(act, ACTION_START)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Read inputs
        String networkName = intent.getStringExtra(EXTRA_NETWORK_NAME);
        String networkSecret = intent.getStringExtra(EXTRA_NETWORK_KEY);
        int remoteGamePort = intent.getIntExtra(EXTRA_GAME_PORT, -1);
        RoomKind roomKind = (RoomKind) intent.getSerializableExtra(EXTRA_ROOM_KIND);

        notifIcon       = intent.getIntExtra(EXTRA_ICON_RES, android.R.drawable.stat_sys_download);
        exitBtnText     = nvl(intent.getStringExtra(EXTRA_BTN_EXIT_TEXT),    "Exit");
        motd            = nvl(intent.getStringExtra(EXTRA_MOTD),             "EnchantNet Room");
        titleConnecting = nvl(intent.getStringExtra(EXTRA_TITLE_CONNECT),    "EnchantNet: connecting…");
        textConnecting  = nvl(intent.getStringExtra(EXTRA_TEXT_CONNECT),     "Starting EasyTier and probing forward port");
        titleConnected  = nvl(intent.getStringExtra(EXTRA_TITLE_OK),         "EnchantNet: connected");
        textConnected   = nvl(intent.getStringExtra(EXTRA_TEXT_OK),          "Forward ready.");
        titleFailBoot   = nvl(intent.getStringExtra(EXTRA_TITLE_FAIL_BOOT),  "Failed to connect");
        textFailBoot    = nvl(intent.getStringExtra(EXTRA_TEXT_FAIL_BOOT),   "Could not start within time");
        titleFailAlive  = nvl(intent.getStringExtra(EXTRA_TITLE_FAIL_ALIVE), "Disconnected: EasyTier crashed");
        textFailAlive   = nvl(intent.getStringExtra(EXTRA_TEXT_FAIL_ALIVE),  "Please restart");
        titleFailConn   = nvl(intent.getStringExtra(EXTRA_TITLE_FAIL_CONN),  "Disconnected: game unreachable");
        textFailConn    = nvl(intent.getStringExtra(EXTRA_TEXT_FAIL_CONN),   "Lost connection to forward port");

        if (isEmpty(networkName) || isEmpty(networkSecret) || remoteGamePort <= 0 || roomKind == null) {
            Log.e(TAG, "Invalid inputs, abort start");
            sendFail("START_ERROR");
            stopSelf();
            return START_NOT_STICKY;
        }

        // Generate local forward UDP port internally
        localForwardPort = IpUtils.getRandomUdpPort();
        instanceName = INSTANCE_NAME_PREFIX + networkSecret;
        String guestIPv4 = IpUtils.pickGuestIpV4(IpUtils.getDeviceUUID(this), roomKind, networkName, networkSecret);

        Log.i(TAG, "Starting guest: inst=" + instanceName +
                " net=" + networkName + " kind=" + roomKind +
                " remotePort=" + remoteGamePort + " localForward=" + localForwardPort +
                " motd=" + motd);

        // Cancel any stale notif first
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ID);

        // Foreground: connecting
        startForeground(NOTIF_ID, buildConnectingNotification());

        // Build TUN route
        final String routeNet  = (roomKind == RoomKind.TERRACOTTA) ? TERRACOTTA_NET : PCL2CE_NET;
        VpnService.Builder b = new Builder()
                .setSession("EnchantNet Guest")
                .setMtu(VPN_MTU)
                .setBlocking(false)
                .addRoute(routeNet, PREFIX_LEN)
                .addAddress(guestIPv4, PREFIX_LEN);
        vpnPfd = b.establish();
        if (vpnPfd == null) {
            Log.e(TAG, "VPN establish() failed");
            sendFail("START_ERROR");
            stopSelf();
            return START_NOT_STICKY;
        }

        // Start EasyTier Guest
        int rc = EasyTierAPI.startEasyTierGuest(
                instanceName,
                networkName,
                networkSecret,
                localForwardPort,  // local forward (guest side)
                remoteGamePort,    // host game port
                roomKind,
                guestIPv4
        );
        if (rc != 0) {
            Log.e(TAG, "startEasytierGuest failed rc=" + rc);
            sendFail("START_ERROR");
            shutdown("START_ET_FAIL");
            return START_NOT_STICKY;
        }

        // Start probing
        exec = Executors.newScheduledThreadPool(3);
        startBootProbing();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        selfStop = true;
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
        shutdown("ON_REVOKE");
    }

    // ========================= Probing =========================
    private void startBootProbing() {
        // Boot timeout
        bootTimeoutTask = exec.schedule(() -> {
            if (!established) {
                Log.w(TAG, "Boot timeout (10s) not established");
                sendFail("TIMEOUT");
                shutdown("BOOT_TIMEOUT");
            }
        }, BOOT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        // checkConn on LOCAL FORWARD PORT
        connTask = exec.scheduleWithFixedDelay(() -> {
            boolean ok = EasyTierAPI.checkConn(localForwardPort, (int) CHECK_CONN_INTERVAL_MS);
            if (ok) {
                if (!connOK.get()) Log.d(TAG, "checkConn OK (forwardPort=" + localForwardPort + ")");
                connOK.set(true);
                connFailStreak.set(0);
            } else {
                if (established) {
                    int streak = connFailStreak.incrementAndGet();
                    Log.w(TAG, "checkConn fail x" + streak);
                    if (streak >= CONN_FAILS_TO_LOST) {
                        sendFail("CONNECTION_LOST");
                        shutdown("CONN_3X_FAIL");
                    }
                } else {
                    connOK.set(false);
                }
            }
            tryFinishBootIfReady();
        }, 0, CHECK_CONN_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // isAlive
        aliveTask = exec.scheduleWithFixedDelay(() -> {
            boolean ok = EasyTierAPI.isAlive();
            if (ok) {
                if (!aliveOK.get()) Log.d(TAG, "isAlive OK");
                aliveOK.set(true);
            } else {
                aliveOK.set(false);
                Log.e(TAG, "isAlive FAILED");
                if (established) {
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
            if (doSetTunFd()) {
                established = true;
                String backup = "127.0.0.1:" + localForwardPort; // guest-only
                Log.i(TAG, "Established! backup=" + backup);
                sendSuccess(backup);
                updateNotification(buildConnectedNotification());
                // start fake broadcaster after connected
                startFakeBroadcast(localForwardPort, motd);
            } else {
                Log.e(TAG, "setTunFd failed during boot");
                sendFail("START_ERROR");
                shutdown("SET_TUN_FD_FAIL");
            }
        }
    }

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

    // ========================= Shutdown =========================
    private void shutdown(String reason) {
        Log.w(TAG, "Shutdown: " + reason + ", established=" + established + ", selfStop=" + selfStop);

        try { unregisterReceiver(stopReqReceiver); } catch (Throwable ignore) {}

        // stop fake broadcaster
        stopFakeBroadcast();

        // stop probes
        if (bootTimeoutTask != null) bootTimeoutTask.cancel(true);
        if (connTask != null) connTask.cancel(true);
        if (aliveTask != null) aliveTask.cancel(true);
        if (exec != null) exec.shutdownNow();

        try { EasyTierAPI.stopEasyTier(); } catch (Throwable ignore) {}

        // close fds
        try { if (tunDupPfd != null) tunDupPfd.close(); } catch (IOException ignore) {}
        tunDupPfd = null;
        try { if (vpnPfd != null) vpnPfd.close(); } catch (IOException ignore) {}
        vpnPfd = null;

        // notifications
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (selfStop) {
            sendStop();
            nm.cancel(NOTIF_ID);
        }
        // failure path keeps the failure notice

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // ========================= Notifications =========================
    private Notification buildConnectingNotification() {
        ensureChannel();
        PendingIntent deletePending = PendingIntent.getService(
                this, 3001,
                new Intent(this, GuestVpnService.class).setAction(ACTION_REPOST),
                PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(notifIcon)
                .setContentTitle(titleConnecting)
                .setContentText(textConnecting)
                .setOngoing(true)
                .setDeleteIntent(deletePending);

        // Exit
        PendingIntent stopPi = PendingIntent.getBroadcast(
                this,
                REQ_STOP,
                new Intent(ACTION_REQ_STOP).setPackage(getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        b.addAction(new Notification.Action.Builder(null, exitBtnText, stopPi).build());
        return b.build();
    }

    private Notification buildConnectedNotification() {
        ensureChannel();
        PendingIntent deletePending = PendingIntent.getService(
                this, 3002,
                new Intent(this, GuestVpnService.class).setAction(ACTION_REPOST),
                PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(notifIcon)
                .setContentTitle(titleConnected)
                .setContentText(nvl(textConnected, "Forward ready at 127.0.0.1:" + localForwardPort))
                .setOngoing(true)
                .setDeleteIntent(deletePending)
                // Exit
                .addAction(new Notification.Action.Builder(
                        null, exitBtnText,
                        PendingIntent.getBroadcast(
                                this,
                                REQ_STOP,
                                new Intent(ACTION_REQ_STOP).setPackage(getPackageName()),
                                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        )
                ).build())
                .build();
    }

    private void showBootFailNotification() { showFailNotification(titleFailBoot, textFailBoot); }
    private void showAliveFailNotification(){ showFailNotification(titleFailAlive, textFailAlive); }
    private void showConnFailNotification() { showFailNotification(titleFailConn, textFailConn); }

    private void showFailNotification(String title, String text) {
        ensureChannel();
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(notifIcon)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(false); // clearable
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, b.build());
    }

    private void updateNotification(Notification n) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, n);
    }

    private void ensureChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "EnchantNet", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
    }

    // ========================= Callbacks =========================
    private void sendSuccess(String backupServer) {
        Intent it = new Intent(ACTION_CALLBACK_SUCCESS).setPackage(getPackageName()).putExtra(EXTRA_BACKUP_SERVER, backupServer);
        sendBroadcast(it);
    }

    private void sendFail(String reason) {
        Log.e(TAG, "Callback FAIL: " + reason);
        switch (reason) {
            case "EASYTIER_CRASH": showAliveFailNotification(); break;
            case "CONNECTION_LOST": showConnFailNotification(); break;
            case "TIMEOUT": default: showBootFailNotification(); break;
        }
        Intent it = new Intent(ACTION_CALLBACK_FAIL).setPackage(getPackageName()).putExtra(EXTRA_FAIL_REASON, reason);
        sendBroadcast(it);
    }

    private void sendStop() {
        Log.i(TAG, "Callback STOP (proactive)");
        sendBroadcast(new Intent(ACTION_CALLBACK_STOP).setPackage(getPackageName()));
    }

    // ========================= Fake LAN broadcaster =========================
    private void startFakeBroadcast(int forwardPort, String motd) {
        stopFakeBroadcast();
        broadcaster = new FakeLanBroadcaster(getApplicationContext(), forwardPort, motd);
        broadcaster.start();
        Log.d(TAG, "FakeLanBroadcaster started, port=" + forwardPort + ", motd=" + motd);
    }

    private void stopFakeBroadcast() {
        if (broadcaster != null) {
            try { broadcaster.stop(); } catch (Throwable ignore) {}
            Log.d(TAG, "FakeLanBroadcaster stopped");
        }
        broadcaster = null;
    }

    // ========================= Utils =========================
    private static String nvl(String s, String d) {
        return (s == null || s.isEmpty()) ? d : s;
    }

}
