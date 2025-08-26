package org.fcl.enchantnetcore.core;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LanScanner: multicast discovery for advertised port.
 * <p>
 * Behavior:
 *  - Poll multicast group every pollIntervalMs (default 200ms).
 *  - On the first detected port -> callback and the worker thread stops itself.
 * <p>
 * Multicast message format: "... [AD]12345[/AD] ..."
 * Multicast group: 224.0.2.60:4445 (overridable via setMulticastTarget)
 * <p>
 * Tips:
 *  - Acquire Wi-Fi MulticastLock when on Wi-Fi to ensure multicast delivery.
 *  - Consider running inside a Foreground Service / VPNService for background reliability.
 */
public class LanScanner {

    public static final String TAG = "LanScanner";

    // ---- Multicast config ----
    private String mcAddress = "224.0.2.60";
    private int mcPort = 4445;

    // ---- Polling ----
    private int pollIntervalMs = 200;

    /** Optional handler for dispatching callbacks (e.g., main thread). If null, callbacks run on worker thread. */
    private final Handler callbackHandler;

    /** Optional Wi-Fi MulticastLock (recommended on Wi-Fi). */
    private WifiManager.MulticastLock wifiMulticastLock;

    // ---- Worker state ----
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread workerThread;

    // ================== Callbacks ==================
    public interface DiscoveryCallback {
        /** Called once when the first advertised port is detected. Scanner stops immediately afterwards. */
        void onPort(int port);

        /** Called when the scanner stops (manually or after first detection). */
        void onStopped();

        /** Called when an unrecoverable error occurs. */
        void onError(Exception e);
    }

    // ================== Constructors ==================
    public LanScanner() { this(null); }

    public LanScanner(Handler callbackHandler) {
        this.callbackHandler = callbackHandler;
    }

    // ================== Configuration ==================

    /** Override multicast address and port. */
    public void setMulticastTarget(String address, int port) {
        this.mcAddress = address;
        this.mcPort = port;
    }

    /** Set polling interval (milliseconds). */
    public void setPollIntervalMs(int intervalMs) {
        this.pollIntervalMs = intervalMs;
    }

    // ================== MulticastLock helpers ==================

    /** Acquire Wi-Fi MulticastLock to ensure multicast reception over Wi-Fi. */
    public void acquireWifiMulticastLock(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiMulticastLock = wm.createMulticastLock(TAG + "_mc");
                wifiMulticastLock.setReferenceCounted(true);
                wifiMulticastLock.acquire();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Acquire MulticastLock failed: " + t);
        }
    }

    /** Release Wi-Fi MulticastLock if held. */
    public void releaseWifiMulticastLock() {
        try {
            if (wifiMulticastLock != null && wifiMulticastLock.isHeld()) wifiMulticastLock.release();
        } catch (Throwable ignored) {}
    }

    // ================== Public API ==================

    /** Start discovery. If already running, the call is ignored. */
    public synchronized void start(final DiscoveryCallback cb) {
        if (running.get()) {
            Log.w(TAG, "Scanner already running");
            return;
        }
        running.set(true);
        workerThread = new Thread(() -> runWorker(cb), "LanScanner-Worker");
        workerThread.start();
    }

    /** Stop discovery manually. Safe to call multiple times. */
    public synchronized void stop() {
        running.set(false);
        if (workerThread != null && workerThread != Thread.currentThread()) {
            try { workerThread.join(500); } catch (InterruptedException ignored) {}
        }
        workerThread = null;
    }

    // ================== Internal worker ==================

    private void runWorker(final DiscoveryCallback cb) {
        MulticastSocket socket = null;
        InetAddress group = null;
        try {
            socket = new MulticastSocket(mcPort);
            socket.setReuseAddress(true);
            group = InetAddress.getByName(mcAddress);
            socket.joinGroup(group);
            socket.setSoTimeout(pollIntervalMs);

            // Loop until manually stopped or first detection occurs
            while (running.get()) {
                Integer port = receiveAdPortOnce(socket);
                if (port != null) {
                    notifyOnPort(cb, port);
                    break; // exit loop; finally{} will emit onStopped()
                }
                // timeout -> keep polling; paced by SoTimeout
            }

        } catch (Exception e) {
            Log.e(TAG, "Scanner error", e);
            notifyOnError(cb, e);
        } finally {
            if (socket != null) {
                try { if (group != null) socket.leaveGroup(group); } catch (IOException ignored) {}
                try { socket.close(); } catch (Throwable ignored) {}
            }
            running.set(false);
            notifyOnStopped(cb);
        }
    }

    // ================== UDP packet parsing ==================

    /** Receive one packet and parse pattern [AD]port[/AD]; return null on timeout/no match. */
    private Integer receiveAdPortOnce(MulticastSocket socket) {
        byte[] buf = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        try {
            socket.receive(packet); // blocks until SoTimeout
            String msg = new String(packet.getData(), 0, packet.getLength());
            int start = msg.indexOf("[AD]");
            int end = msg.indexOf("[/AD]");
            if (start >= 0 && end > start + 4) {
                String p = msg.substring(start + 4, end).trim();
                try {
                    int port = Integer.parseInt(p);
                    Log.d(TAG, "Detected port: " + port + " from " + packet.getAddress());
                    return port;
                } catch (NumberFormatException ignored) {}
            }
            return null;
        } catch (SocketTimeoutException te) {
            return null; // no packet for this interval
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
    }

    // ================== Callback dispatch ==================

    private void dispatch(Runnable r) {
        if (callbackHandler != null) callbackHandler.post(r); else r.run();
    }

    private void notifyOnPort(final DiscoveryCallback cb, final int port) {
        try { dispatch(() -> cb.onPort(port)); } catch (Throwable ignored) {}
    }

    private void notifyOnStopped(final DiscoveryCallback cb) {
        try { dispatch(cb::onStopped); } catch (Throwable ignored) {}
    }

    private void notifyOnError(final DiscoveryCallback cb, final Exception e) {
        try { dispatch(() -> cb.onError(e)); } catch (Throwable ignored) {}
    }
}
