package org.fcl.enchantnetcore.core;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.MulticastSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
    private static final String MULTICAST_ADDR_V4 = "224.0.2.60";
    private static final String MULTICAST_ADDR_V6 = "ff75:230::60";
    private static final int MULTICAST_PORT = 4445;
    private static final int TTL = 4;
    private static final long INTERVAL_MS = 1500L;

    private final int listenPort;
    private final String motd;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    // Optional Wi‑Fi multicast lock (recommended on Android)
    private final WifiManager.MulticastLock multicastLock;

    private static final class Sender {
        MulticastSocket socket;
        DatagramPacket packet;
        String nicName;
        boolean v6;
    }

    private final List<Sender> senders = new ArrayList<>();

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
            final String payload = "[MOTD]" + motd + "[/MOTD][AD]" + listenPort + "[/AD]";
            final byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

            closeAllSenders();

            java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                java.net.NetworkInterface ni = en.nextElement();
                try {
                    if (!ni.isUp() || ni.isLoopback()) continue;

                    // ---- IPv4 ----
                    boolean hasV4 = false;
                    for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        if (ia.getAddress() instanceof java.net.Inet4Address) { hasV4 = true; break; }
                    }
                    if (hasV4) {
                        try {
                            MulticastSocket s4 = new MulticastSocket();
                            s4.setTimeToLive(TTL);
                            try { s4.setLoopbackMode(false); } catch (IOException ignored) {}
                            s4.setNetworkInterface(ni);
                            java.net.InetAddress g4 = java.net.InetAddress.getByName(MULTICAST_ADDR_V4);
                            DatagramPacket p4 = new DatagramPacket(bytes, bytes.length, g4, MULTICAST_PORT);

                            Sender sd4 = new Sender();
                            sd4.socket = s4; sd4.packet = p4; sd4.nicName = ni.getName(); sd4.v6 = false;
                            senders.add(sd4);
                            Log.d(TAG, "Fake broadcast v4 via " + ni.getName());
                        } catch (Throwable t) {
                            Log.w(TAG, "v4 sender build failed on " + ni.getName(), t);
                        }
                    }

                    // ---- IPv6 ----
                    boolean hasV6 = false;
                    for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        if (ia.getAddress() instanceof java.net.Inet6Address) { hasV6 = true; break; }
                    }
                    if (hasV6) {
                        try {
                            MulticastSocket s6 = new MulticastSocket();
                            s6.setTimeToLive(TTL);
                            try { s6.setLoopbackMode(false); } catch (IOException ignored) {}
                            s6.setNetworkInterface(ni);
                            java.net.InetAddress g6 = java.net.InetAddress.getByName(MULTICAST_ADDR_V6); // ff75:230::60
                            DatagramPacket p6 = new DatagramPacket(bytes, bytes.length, g6, MULTICAST_PORT);

                            Sender sd6 = new Sender();
                            sd6.socket = s6; sd6.packet = p6; sd6.nicName = ni.getName(); sd6.v6 = true;
                            senders.add(sd6);
                            Log.d(TAG, "Fake broadcast v6 via " + ni.getName());
                        } catch (Throwable t) {
                            Log.w(TAG, "v6 sender build failed on " + ni.getName(), t);
                        }
                    }
                } catch (Throwable ignoredOneNic) {}
            }

            if (senders.isEmpty())
                throw new IOException("No multicast senders available (no up NICs with IPs)");

            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, TAG + "-broadcast");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(this::sendAll, 0L, INTERVAL_MS, TimeUnit.MILLISECONDS);
            Log.i(TAG, "Start broadcasting (dual-stack), MOTD=" + motd + ", port=" + listenPort);
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
        closeAllSenders();
        Log.i(TAG, "Broadcast stopped");
    }

    private void sendAll() {
        if (!running.get()) return;
        for (Sender s : senders) {
            try {
                s.socket.send(s.packet);
            } catch (IOException e) {
                Log.w(TAG, "send failed via " + s.nicName + (s.v6? " (v6)":" (v4)"), e);
            }
        }
    }

    private void closeAllSenders() {
        for (Sender s : senders) {
            try { s.socket.close(); } catch (Throwable ignored) {}
        }
        senders.clear();
    }

}
