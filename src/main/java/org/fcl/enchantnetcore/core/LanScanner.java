package org.fcl.enchantnetcore.core;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
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
    private String mcAddressV4 = "224.0.2.60";
    private String mcAddressV6 = "ff75:230::60";
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
    public void setMulticastTarget(String v4, String v6, int port) {
        this.mcAddressV4 = v4;
        this.mcAddressV6 = v6;
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

    private static final class UdpSock {
        MulticastSocket sock;
        NetworkInterface nic;
        InetAddress group;
        boolean v6;
    }

    private List<UdpSock> openAllMcSockets(int port, int soTimeoutMs, String mcAddrV4, String mcAddrV6) throws Exception {
        List<UdpSock> out = new ArrayList<>();
        Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
        while (en.hasMoreElements()) {
            NetworkInterface ni = en.nextElement();
            if (!ni.isUp() || ni.isLoopback())
                continue;

            // ---- IPv4 on this NIC ----
            boolean hasV4 = false;
            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                if (ia.getAddress() instanceof Inet4Address) {
                    hasV4 = true;
                    break;
                }
            }
            if (hasV4) {
                try {
                    InetAddress g4 = InetAddress.getByName(mcAddrV4);
                    MulticastSocket s4 = new MulticastSocket(new InetSocketAddress("0.0.0.0", port));
                    s4.setReuseAddress(true);
                    s4.setSoTimeout(soTimeoutMs);
                    s4.setNetworkInterface(ni);
                    try {
                        s4.joinGroup(g4);
                    } catch (Throwable t) {
                        s4.joinGroup(new InetSocketAddress(g4, port), ni);
                    }
                    UdpSock us = new UdpSock();
                    us.sock = s4;
                    us.nic = ni;
                    us.group = g4;
                    us.v6 = false;
                    out.add(us);
                } catch (Throwable ignore) {}
            }

            // ---- IPv6 on this NIC ----
            List<Inet6Address> addrs6 = new ArrayList<>();
            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                if (ia.getAddress() instanceof Inet6Address) addrs6.add((Inet6Address) ia.getAddress());
            }
            if (!addrs6.isEmpty()) {
                InetAddress g6 = InetAddress.getByName(mcAddrV6);
                for (Inet6Address a6 : addrs6) {
                    try {
                        MulticastSocket s6 = new MulticastSocket(new InetSocketAddress(a6, port));
                        s6.setReuseAddress(true);
                        s6.setSoTimeout(soTimeoutMs);
                        s6.setNetworkInterface(ni);
                        s6.joinGroup(new InetSocketAddress(g6, port), ni);
                        UdpSock us = new UdpSock();
                        us.sock = s6;
                        us.nic = ni;
                        us.group = g6;
                        us.v6 = true;
                        out.add(us);
                        break;
                    } catch (Throwable ignore) {}
                }
            }
        }
        if (out.isEmpty())
            throw new IOException("No multicast sockets joined.");
        return out;
    }

    private void runWorker(final DiscoveryCallback cb) {
        List<UdpSock> socks = null;
        try {
            final String V4 = mcAddressV4;
            final String V6 = mcAddressV6;
            socks = openAllMcSockets(mcPort, pollIntervalMs, V4, V6);

            while (running.get()) {
                for (UdpSock us : socks) {
                    try {
                        Integer port = receiveAdPortOnce(us.sock);
                        if (port != null && port > 0 && port <= 65535) {
                            notifyOnPort(cb, port);
                            running.set(false);
                            break;
                        }
                    } catch (RuntimeException re) {
                        // RuntimeException
                    }
                }
            }
        } catch (Exception e) {
            notifyOnError(cb, e);
        } finally {
            if (socks != null) {
                for (UdpSock us : socks) {
                    try {
                        if (us.group != null) {
                            if (us.v6)
                                us.sock.leaveGroup(new InetSocketAddress(us.group, mcPort), us.nic);
                            else
                                us.sock.leaveGroup(us.group);
                        }
                    } catch (Throwable ignored) {}
                    try { us.sock.close(); } catch (Throwable ignored) {}
                }
            }
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
