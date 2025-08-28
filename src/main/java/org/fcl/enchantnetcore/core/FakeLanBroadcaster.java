package org.fcl.enchantnetcore.core;

import android.content.Context;
import android.net.VpnService;
import android.net.wifi.WifiManager;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.Closeable;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Minimal LAN broadcaster (guest-side):
 * - Default: ONE announcement only (IPv4 subnet broadcast on Wi-Fi iface) -> one room in client.
 * - Fallback: IPv4 multicast (224.0.2.60:4445) if subnet broadcast not available.
 * - Optional toggles for IPv6 multicast / global broadcast / loopback (disabled by default).
 * - No reflection: uses ParcelFileDescriptor.fromDatagramSocket() to protect via VpnService.
 * <p>
 * Payload MUST be: [MOTD]{motd}[/MOTD][AD]{localPort}[/AD]
 * where localPort is the local TCP forward port on THIS device.
 */
public final class FakeLanBroadcaster implements Closeable {

    private static final String TAG = "FakeLanMinimal";
    private static final int PORT = 4445;
    private static final InetAddress V4_MCAST;
    private static final InetAddress V6_MCAST;

    static {
        InetAddress v4 = null, v6 = null;
        try { v4 = InetAddress.getByName("224.0.2.60"); } catch (Exception ignored) {}
        try { v6 = InetAddress.getByName("ff75:0230::60"); } catch (Exception ignored) {}
        V4_MCAST = v4;
        V6_MCAST = v6;
    }

    // ---- Options (all false by default to avoid duplicate rooms) ----
    public static final class Options {
        public boolean enableIpv6Multicast = false; // only enable if your local forward is dual-stack
        public boolean enableGlobalBroadcast = false;
        public boolean enableLoopback = false;       // only for same-device debug
    }

    private final Context appCtx;
    private final VpnService vpn;
    private final Options opts;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "FakeLanBroadcaster");
                t.setDaemon(true);
                return t;
            });

    private WifiManager.MulticastLock mlock;
    private ScheduledFuture<?> task;

    // we keep at most a few sockets, but default is just ONE (subnet broadcast)
    private final List<DatagramSocket> dgramSenders = new ArrayList<>();
    private final List<MulticastSocket> mcastSenders = new ArrayList<>();
    private final List<InetSocketAddress> targets = new ArrayList<>();

    private volatile String motd = "";
    private volatile int localPort = -1;

    public FakeLanBroadcaster(Context ctx, VpnService vpnService) {
        this(ctx, vpnService, new Options());
    }

    public FakeLanBroadcaster(Context ctx, VpnService vpnService, Options options) {
        this.appCtx = ctx != null ? ctx.getApplicationContext() : null;
        this.vpn = vpnService;
        this.opts = options != null ? options : new Options();
    }

    /** Start broadcasting (idempotent). localPort MUST be the guest's local TCP forward port. */
    public synchronized void start(String motd, int localPort) {
        stop(); // ensure clean
        this.motd = motd != null ? motd : "";
        this.localPort = localPort;

        // 1) Prefer Wi-Fi iface IPv4 subnet broadcast (single room)
        boolean ok = openSingleIpv4SubnetBroadcastOnWifi();

        // 2) Fallback to IPv4 multicast if needed
        if (!ok) {
            ok = openIpv4MulticastOnBestIface();
        }

        // 3) Optional extras (disabled by default to avoid duplicates)
        if (opts.enableIpv6Multicast) {
            openIpv6MulticastOnBestIface();
        }
        if (opts.enableGlobalBroadcast) {
            openGlobalBroadcast();
        }
        if (opts.enableLoopback) {
            openLoopback();
        }

        if (targets.isEmpty()) {
            throw new IllegalStateException("No valid LAN announcement path available.");
        }

        final byte[] payload = buildPayload(this.motd, this.localPort);
        task = scheduler.scheduleWithFixedDelay(() -> tick(payload), 0, 1500, TimeUnit.MILLISECONDS);
        Log.i(TAG, "Started; localPort=" + localPort + ", paths=" + targets.size());
    }

    /** Stop broadcasting (idempotent). */
    public synchronized void stop() {
        if (task != null) {
            task.cancel(true);
            task = null;
        }
        closeAll();
        releaseMulticastLockIfNeeded();
    }

    @Override public void close() { stop(); }

    // ---------------- internal ----------------

    /** Try to find a Wi-Fi NIC and send ONE IPv4 subnet broadcast on it (single room). */
    private boolean openSingleIpv4SubnetBroadcastOnWifi() {
        try {
            NetworkInterface best = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (notUsable(ni)) continue;
                String n = ni.getName().toLowerCase(Locale.ROOT);
                if (n.startsWith("wlan")) { best = ni; break; }  // prefer Wi-Fi
            }
            if (best == null) {
                // fallback: pick the first usable NIC with IPv4
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (notUsable(ni)) continue;
                    if (hasFamily(ni, Inet4Address.class)) { best = ni; break; }
                }
            }
            if (best == null) return false;

            for (InterfaceAddress ia : best.getInterfaceAddresses()) {
                if (ia == null) continue;
                InetAddress bcast = ia.getBroadcast();
                InetAddress local = ia.getAddress();
                if (!(bcast instanceof Inet4Address) || !(local instanceof Inet4Address)) continue;

                DatagramSocket ds = new DatagramSocket(null);
                ds.setReuseAddress(true);
                ds.bind(new InetSocketAddress(local, 0)); // bind to NIC's IPv4
                ds.setBroadcast(true);
                protect(ds);

                dgramSenders.add(ds);
                targets.add(new InetSocketAddress(bcast, PORT));
                Log.i(TAG, "Using Wi-Fi IPv4 subnet broadcast on iface=" + best.getName()
                        + " local=" + local.getHostAddress() + " -> " + bcast.getHostAddress() + ":" + PORT);
                return true; // exactly ONE path
            }
        } catch (Throwable t) {
            Log.w(TAG, "openSingleIpv4SubnetBroadcastOnWifi failed", t);
        }
        return false;
    }

    /** Fallback: IPv4 multicast (224.0.2.60:4445) on the first usable iface with IPv4. */
    private boolean openIpv4MulticastOnBestIface() {
        if (V4_MCAST == null) return false;
        try {
            NetworkInterface best = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (notUsable(ni)) continue;
                if (hasFamily(ni, Inet4Address.class)) { best = ni; break; }
            }
            if (best == null) return false;

            acquireMulticastLockIfNeeded();
            MulticastSocket s4 = new MulticastSocket(null);
            s4.setReuseAddress(true);
            s4.bind(new InetSocketAddress(0));
            s4.setNetworkInterface(best);
            s4.setTimeToLive(1);
            s4.setLoopbackMode(false); // allow self-loop on same device
            protect(s4);

            mcastSenders.add(s4);
            targets.add(new InetSocketAddress(V4_MCAST, PORT));
            Log.i(TAG, "Fallback to IPv4 multicast on iface=" + best.getName());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "openIpv4MulticastOnBestIface failed", t);
            return false;
        }
    }

    /** Optional: IPv6 multicast (only if dual-stack ready). */
    private void openIpv6MulticastOnBestIface() {
        if (V6_MCAST == null) return;
        try {
            NetworkInterface best = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (notUsable(ni)) continue;
                if (hasFamily(ni, Inet6Address.class)) { best = ni; break; }
            }
            if (best == null) return;

            acquireMulticastLockIfNeeded();
            MulticastSocket s6 = new MulticastSocket(null);
            s6.setReuseAddress(true);
            s6.bind(new InetSocketAddress(0));
            s6.setNetworkInterface(best);
            s6.setTimeToLive(1);
            s6.setLoopbackMode(false);
            protect(s6);

            // Let kernel pick proper scope for ff75:0230::60 via selected NIC
            mcastSenders.add(s6);
            targets.add(new InetSocketAddress(V6_MCAST, PORT));
            Log.i(TAG, "Optional IPv6 multicast on iface=" + best.getName());
        } catch (Throwable t) {
            Log.w(TAG, "openIpv6MulticastOnBestIface failed", t);
        }
    }

    /** Optional: global broadcast 255.255.255.255:4445 (may be filtered). */
    private void openGlobalBroadcast() {
        try {
            DatagramSocket ds = new DatagramSocket(null);
            ds.setReuseAddress(true);
            ds.bind(new InetSocketAddress(0));
            ds.setBroadcast(true);
            protect(ds);

            dgramSenders.add(ds);
            targets.add(new InetSocketAddress(InetAddress.getByName("255.255.255.255"), PORT));
            Log.i(TAG, "Optional global broadcast enabled");
        } catch (Throwable t) {
            Log.w(TAG, "openGlobalBroadcast failed", t);
        }
    }

    /** Optional: loopback announcements (useful only for same-device debug). */
    private void openLoopback() {
        try {
            DatagramSocket ds4 = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
            protect(ds4);
            dgramSenders.add(ds4);
            targets.add(new InetSocketAddress("127.0.0.1", PORT));
        } catch (Throwable t) { Log.w(TAG, "open loopback v4 failed", t); }

        try {
            DatagramSocket ds6 = new DatagramSocket(new InetSocketAddress("::1", 0));
            protect(ds6);
            dgramSenders.add(ds6);
            targets.add(new InetSocketAddress("::1", PORT));
        } catch (Throwable t) { Log.w(TAG, "open loopback v6 failed", t); }
    }

    private void tick(byte[] payload) {
        // Multicast first (if any)
        for (int i = 0; i < mcastSenders.size(); i++) {
            MulticastSocket ms = mcastSenders.get(i);
            InetSocketAddress dst = targets.get(dgramSenders.size() + i);
            try {
                DatagramPacket p = new DatagramPacket(payload, payload.length, dst);
                ms.send(p);
            } catch (Throwable e) {
                Log.w(TAG, "mcast send failed -> " + dst, e);
            }
        }
        // Then unicast/broadcast
        for (int i = 0; i < dgramSenders.size(); i++) {
            DatagramSocket ds = dgramSenders.get(i);
            InetSocketAddress dst = targets.get(i);
            try {
                DatagramPacket p = new DatagramPacket(payload, payload.length, dst);
                ds.send(p);
            } catch (Throwable e) {
                Log.w(TAG, "dgram send failed -> " + dst, e);
            }
        }
    }

    private void closeAll() {
        for (MulticastSocket ms : mcastSenders) { try { ms.close(); } catch (Throwable ignored) {} }
        mcastSenders.clear();
        for (DatagramSocket ds : dgramSenders) { try { ds.close(); } catch (Throwable ignored) {} }
        dgramSenders.clear();
        targets.clear();
    }

    private static byte[] buildPayload(String motd, int localPort) {
        String s = "[MOTD]" + (motd == null ? "" : motd) + "[/MOTD][AD]" + localPort + "[/AD]";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private boolean notUsable(NetworkInterface ni) {
        try {
            if (ni == null || !ni.isUp() || ni.isLoopback() || ni.isPointToPoint()) return true;
            String name = ni.getName();
            if (name == null) return true;
            String n = name.toLowerCase(Locale.ROOT);
            // exclude tun/ppp/lo/vpn-ish to avoid TUN/VPN routes
            return n.startsWith("lo") || n.startsWith("tun") || n.startsWith("ppp") || n.contains("vpn");// do NOT trust supportsMulticast()
        } catch (Throwable ignore) {
            return true;
        }
    }

    private boolean hasFamily(NetworkInterface ni, Class<?> clazz) {
        try {
            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                if (ia != null && clazz.isInstance(ia.getAddress())) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void acquireMulticastLockIfNeeded() {
        if (appCtx == null) return;
        if (mlock != null && mlock.isHeld()) return;
        try {
            WifiManager wm = (WifiManager) appCtx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                mlock = wm.createMulticastLock("enchantnet-fake-lan");
                mlock.setReferenceCounted(false);
                mlock.acquire();
            }
        } catch (Throwable t) {
            Log.w(TAG, "acquireMulticastLock failed", t);
        }
    }

    private void releaseMulticastLockIfNeeded() {
        try {
            if (mlock != null && mlock.isHeld()) mlock.release();
        } catch (Throwable ignored) {}
        mlock = null;
    }

    /** No reflection: use ParcelFileDescriptor to get fd and protect it. */
    private void protect(DatagramSocket ds) {
        if (vpn == null) return;
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromDatagramSocket(ds)) {
            try {
                if (pfd != null)
                    vpn.protect(pfd.getFd());
            } catch (Throwable t) {
                Log.w(TAG, "protect DatagramSocket failed", t);
            }
        } catch (Throwable ignored) {
        }
    }

    private void protect(MulticastSocket ms) {
        // MulticastSocket extends DatagramSocket; reuse the same path
        protect((DatagramSocket) ms);
    }
}
