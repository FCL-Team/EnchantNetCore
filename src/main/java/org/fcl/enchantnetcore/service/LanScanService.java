package org.fcl.enchantnetcore.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.fcl.enchantnetcore.core.LanScanner;

import java.util.Objects;

/**
 * Foreground service that runs LAN scan using {@link LanScanner}.
 */
public class LanScanService extends Service {

    // ===== Logging =====
    private static final String TAG = "LanScanService";

    // ===== Package Name =====
    private static final String PREFIX = (LanScanService.class.getPackage() != null)
            ? Objects.requireNonNull(LanScanService.class.getPackage()).getName()
            : "org.fcl.enchantnetcore.service";

    // ===== Intent actions =====
    private static final String ACTION_START           = PREFIX + ".LanScanService.ACTION_START";
    private static final String ACTION_STOP            = PREFIX + ".LanScanService.ACTION_STOP";
    private static final String ACTION_REPOST          = PREFIX + ".LanScanService.ACTION_REPOST";

    // ===== Intent extras (public contract) =====
    public static final String EXTRA_RESULT_RECEIVER   = "result_receiver";      // ResultReceiver
    public static final String EXTRA_MULTICAST_ADDR    = "multicast_addr";       // String, default 224.0.2.60
    public static final String EXTRA_MULTICAST_PORT    = "multicast_port";       // int, default 4445
    public static final String EXTRA_POLL_INTERVAL_MS  = "poll_interval_ms";     // int, default 200
    public static final String EXTRA_USE_WIFI_LOCK     = "use_wifi_lock";        // boolean, default true

    // Notification customization
    public static final String EXTRA_NOTIF_ID          = "notif_id";             // int, default 1001
    public static final String EXTRA_NOTIF_CHANNEL_ID  = "notif_channel_id";     // String, default "lan_scan"
    public static final String EXTRA_NOTIF_CHANNEL_NAME= "notif_channel_name";   // String, default "LAN Scan"
    public static final String EXTRA_NOTIF_TITLE       = "notif_title";          // String, default app name + " scanning"
    public static final String EXTRA_NOTIF_TEXT        = "notif_text";           // String, default "Searching on LAN…"
    public static final String EXTRA_NOTIF_ICON        = "notif_icon";           // int, drawable res id, default android.R.drawable.stat_sys_warning
    public static final String EXTRA_NOTIF_TAP_INTENT  = "notif_tap_intent";     // PendingIntent (as Parcelable)

    // Result keys
    public static final String RESULT_KEY_PORT         = "port";
    public static final String RESULT_KEY_ERROR        = "error";

    // ===== Defaults =====
    private static final String DEFAULT_MC_ADDR        = "224.0.2.60";
    private static final int    DEFAULT_MC_PORT        = 4445;
    private static final int    DEFAULT_POLL_MS        = 200;
    private static final int    DEFAULT_NOTIF_ID       = 1001;
    private static final String DEFAULT_CHANNEL_ID     = "lan_scan";
    private static final String DEFAULT_CHANNEL_NAME   = "LAN Scan";

    // ===== Runtime state =====
    private LanScanner scanner;
    private ResultReceiver receiver;
    private boolean wifiLockEnabled = true;
    private Intent lastNotifIntent;
    private volatile boolean isStopping = false;
    private int currentNotifId = DEFAULT_NOTIF_ID;
    private BroadcastReceiver stopReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        stopReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_STOP.equals(intent.getAction())) {
                    stopScanAndSelf();
                }
            }
        };
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stopReceiver, new android.content.IntentFilter(ACTION_STOP), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stopReceiver, new android.content.IntentFilter(ACTION_STOP));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        final String action = intent.getAction();
        if (ACTION_REPOST.equals(action)) {
            if (isStopping || scanner == null)
                return START_NOT_STICKY;
            Intent src = (lastNotifIntent != null) ? lastNotifIntent : new Intent(this, LanScanService.class);
            startForegroundWithNotification(src);  // 重新拉回前台
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            startForegroundWithNotification(intent);
            startScan(intent);
            return START_STICKY;
        }
        // Unknown action -> ignore
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        stopScanAndSelf();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }

    // ====================== Public helpers ======================

    /**
     * Start the scan service as foreground.
     */
    public static void start(Context ctx, @Nullable ResultReceiver rr,
                             int notifIcon, @Nullable String notifTitle, @Nullable String notifText) {
        Intent i = new Intent(ctx, LanScanService.class).setAction(ACTION_START);
        if (rr != null) i.putExtra(EXTRA_RESULT_RECEIVER, rr);
        i.putExtra(EXTRA_NOTIF_ICON, notifIcon);
        if (notifTitle != null) i.putExtra(EXTRA_NOTIF_TITLE, notifTitle);
        if (notifText != null) i.putExtra(EXTRA_NOTIF_TEXT, notifText);
        ContextCompat.startForegroundService(ctx, i);
    }

    /** Overload with defaults. */
    public static void start(Context ctx, @Nullable ResultReceiver rr) {
        Intent i = new Intent(ctx, LanScanService.class).setAction(ACTION_START);
        if (rr != null) i.putExtra(EXTRA_RESULT_RECEIVER, rr);
        ContextCompat.startForegroundService(ctx, i);
    }

    /** Stop if running. */
    public static void stop(Context ctx) {
        Intent i = new Intent(ACTION_STOP).setPackage(ctx.getPackageName());
        ctx.sendBroadcast(i);
    }

    // ====================== Internals ======================

    private void startScan(Intent intent) {
        // Tear down previous if any
        stopScanInternal();

        receiver = intent.getParcelableExtra(EXTRA_RESULT_RECEIVER);
        final String mcAddr = intent.getStringExtra(EXTRA_MULTICAST_ADDR) != null
                ? intent.getStringExtra(EXTRA_MULTICAST_ADDR) : DEFAULT_MC_ADDR;
        final int mcPort = intent.getIntExtra(EXTRA_MULTICAST_PORT, DEFAULT_MC_PORT);
        final int pollMs = intent.getIntExtra(EXTRA_POLL_INTERVAL_MS, DEFAULT_POLL_MS);
        wifiLockEnabled = intent.getBooleanExtra(EXTRA_USE_WIFI_LOCK, true);

        scanner = new LanScanner();
        scanner.setMulticastTarget(mcAddr, mcPort);
        scanner.setPollIntervalMs(pollMs);
        if (wifiLockEnabled) scanner.acquireWifiMulticastLock(getApplicationContext());

        // Use service thread for callbacks -> deliver to receiver inside callbacks
        scanner.start(new LanScanner.DiscoveryCallback() {
            @Override public void onPort(int port) {
                deliverSuccess(port);
                stopScanAndSelf();
            }
            @Override public void onStopped() {
                // If stopped without success and not already finished -> treat as canceled
                // (Do not send duplicate if success already delivered)
            }
            @Override public void onError(Exception e) {
                deliverError(e);
                stopScanAndSelf();
            }
        });
    }

    private void stopScanAndSelf() {
        if (isStopping) return;
        isStopping = true;

        try { unregisterReceiver(stopReceiver); } catch (Throwable ignored) {}

        stopScanInternal();

        try { stopForeground(true); } catch (Throwable ignored) {}

        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(currentNotifId);
        } catch (Throwable ignored) {}

        stopSelf();
    }

    private void stopScanInternal() {
        if (scanner != null) {
            try { scanner.stop(); } catch (Throwable ignored) {}
            if (wifiLockEnabled) {
                try { scanner.releaseWifiMulticastLock(); } catch (Throwable ignored) {}
            }
            scanner = null;
        }
    }

    private void deliverSuccess(int port) {
        if (receiver != null) {
            Bundle b = new Bundle();
            b.putInt(RESULT_KEY_PORT, port);
            try { receiver.send(android.app.Activity.RESULT_OK, b); } catch (Throwable ignored) {}
        }
    }

    private void deliverError(Exception e) {
        if (receiver != null) {
            Bundle b = new Bundle();
            b.putString(RESULT_KEY_ERROR, e != null ? (e.getClass().getSimpleName()+": "+e.getMessage()) : "Unknown error");
            try { receiver.send(android.app.Activity.RESULT_CANCELED, b); } catch (Throwable ignored) {}
        }
    }

    private void startForegroundWithNotification(Intent intent) {
        final int notifId = intent.getIntExtra(EXTRA_NOTIF_ID, DEFAULT_NOTIF_ID);
        final String chId = intent.getStringExtra(EXTRA_NOTIF_CHANNEL_ID) != null
                ? intent.getStringExtra(EXTRA_NOTIF_CHANNEL_ID) : DEFAULT_CHANNEL_ID;
        final String chName = intent.getStringExtra(EXTRA_NOTIF_CHANNEL_NAME) != null
                ? intent.getStringExtra(EXTRA_NOTIF_CHANNEL_NAME) : DEFAULT_CHANNEL_NAME;
        final int icon = intent.getIntExtra(EXTRA_NOTIF_ICON, android.R.drawable.stat_sys_warning);

        final String appLabel = getApplicationInfo().loadLabel(getPackageManager()).toString();
        final String title = intent.getStringExtra(EXTRA_NOTIF_TITLE) != null
                ? intent.getStringExtra(EXTRA_NOTIF_TITLE) : (appLabel + " scanning");
        final String text = intent.getStringExtra(EXTRA_NOTIF_TEXT) != null
                ? intent.getStringExtra(EXTRA_NOTIF_TEXT) : "Searching on LAN…";

        this.lastNotifIntent = new Intent(intent);
        this.currentNotifId = notifId;   // ← 记录ID，供 stop 时兜底 cancel

        final NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(chId, chName, NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }

        PendingIntent tapPi = intent.getParcelableExtra(EXTRA_NOTIF_TAP_INTENT);
        assert chId != null;
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, chId)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        if (tapPi != null) b.setContentIntent(tapPi);

        PendingIntent deletePi = PendingIntent.getService(
                this, 3001, new Intent(this, LanScanService.class).setAction(ACTION_REPOST),
                PendingIntent.FLAG_IMMUTABLE);
        b.setDeleteIntent(deletePi);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }

        Notification notif = b.build();
        notif.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;

        // No foreground service type flags -> compatible down to minSdk 26
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            androidx.core.app.ServiceCompat.startForeground(
                    this,
                    notifId,
                    notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            );
        } else {
            startForeground(notifId, notif);
        }

    }
}
