package org.fcl.enchantnetcore.core;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android Java port of the FakeServer "fake broadcast".
 * <p>
 * Sends an MC LAN discovery packet to 224.0.2.60:4445 at a fixed rate (1.5s)
 * with payload: [MOTD]{motd}[/MOTD][AD]{port}[/AD]
 * <p>
 * - TTL: 4
 * - Loopback: enabled
 * - Scheduler: ScheduledExecutorService using scheduleWithFixedDelay
 * - Optional: WifiManager.MulticastLock for reliable multicast on Android
 */
public final class FakeLanBroadcaster {

    private static final String TAG = "FakeServer";
    private static final String MULTICAST_ADDR = "224.0.2.60";
    private static final int MULTICAST_PORT = 4445;
    private static final int TTL = 4;
    private static final long INTERVAL_MS = 1500L;

    private final int listenPort;
    private final String motd;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;
    private MulticastSocket socket;
    private DatagramPacket packet;

    // Optional Wi‑Fi multicast lock (recommended on Android)
    private final WifiManager.MulticastLock multicastLock;

    /**
     * Create a broadcaster. Pass a non-null Context to allow taking a MulticastLock.
     * If context is null, no lock will be used.
     */
    public FakeLanBroadcaster(Context context, int listenPort, String motd) {
        this.listenPort = listenPort;
        this.motd = Objects.requireNonNull(motd, "motd");
        WifiManager.MulticastLock lock = null;
        if (context != null) {
            try {
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    lock = wm.createMulticastLock(TAG + "-lock");
                    lock.setReferenceCounted(false);
                }
            } catch (Throwable t) {
                Log.w(TAG, "MulticastLock not available: " + t);
            }
        }
        this.multicastLock = lock;
    }

    public synchronized void start() {
        if (running.get())
            return;
        running.set(true);
        if (multicastLock != null) {
            try {
                multicastLock.acquire();
            } catch (Throwable ignored) {

            }
        }

        try {
            socket = new MulticastSocket();
            socket.setTimeToLive(TTL);
            try {
                socket.setLoopbackMode(false);
            } catch (IOException ignored) {

            }

            final InetAddress group = InetAddress.getByName(MULTICAST_ADDR);
            final String payload = "[MOTD]" + motd + "[/MOTD][AD]" + listenPort + "[/AD]";
            final byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            packet = new DatagramPacket(bytes, bytes.length, group, MULTICAST_PORT);

            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, TAG + "-broadcast");
                t.setDaemon(true);
                return t;
            });

            scheduler.scheduleWithFixedDelay(this::sendOnce, 0L, INTERVAL_MS, TimeUnit.MILLISECONDS);
            Log.i(TAG, "Start broadcasting, MOTD=" + motd + ", port=" + listenPort);
        } catch (IOException e) {
            Log.e(TAG, "Failed to init broadcast socket", e);
            running.set(false);
            if (multicastLock != null && multicastLock.isHeld()) {
                try {
                    multicastLock.release();
                } catch (Throwable ignored) {

                }
            }
        }
    }

    public synchronized void stop() {
        running.set(false);
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                boolean ended = scheduler.awaitTermination(INTERVAL_MS + 500, TimeUnit.MILLISECONDS);
                if (!ended) {
                    Log.w(TAG, "Scheduler didn't terminate in time, forcing shutdownNow()");
                    scheduler.shutdownNow();
                    ended = scheduler.awaitTermination(1000, TimeUnit.MILLISECONDS);
                    if (!ended) {
                        Log.w(TAG, "Scheduler failed to terminate cleanly");
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "stop() interrupted; forcing shutdownNow()", ie);
                scheduler.shutdownNow();
            } finally {
                scheduler = null;
            }
        }
        if (socket != null) {
            try { socket.close(); } catch (Throwable ignored) {}
            socket = null;
        }
        packet = null;
        if (multicastLock != null && multicastLock.isHeld()) {
            try { multicastLock.release(); } catch (Throwable ignored) {}
        }
        Log.i(TAG, "Broadcast stopped");
    }

    private void sendOnce() {
        if (!running.get())
            return;
        MulticastSocket s = this.socket;
        DatagramPacket p = this.packet;
        if (s == null || p == null)
            return;
        try {
            s.send(p);
        } catch (IOException e) {
            Log.e(TAG, "send failed", e);
        }
    }
}
