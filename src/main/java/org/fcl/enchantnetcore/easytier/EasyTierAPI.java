package org.fcl.enchantnetcore.easytier;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.fcl.enchantnetcore.core.RoomKind;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;
import java.util.Enumeration;
import java.util.UUID;

public class EasyTierAPI {

    public static final String TAG = "EasyTierAPI";

    private static final String[] DEFAULT_PEERS = new String[]{
            "tcp://public.easytier.top:11010",
            "tcp://ah.nkbpal.cn:11010",
            "tcp://turn.hb.629957.xyz:11010",
            "tcp://turn.js.629957.xyz:11012",
            "tcp://sh.993555.xyz:11010",
            "tcp://turn.bj.629957.xyz:11010",
            "tcp://et.sh.suhoan.cn:11010",
            "tcp://et-hk.clickor.click:11010",
            "tcp://et.01130328.xyz:11010",
            "tcp://et.gbc.moe:11011"
    };

    /**
     * Start EasyTier in Host mode.
     *
     * @param instanceName instance name
     * @param networkName  network name
     * @param secret       secret
     * @return 0 on success, non-zero on failure (same as runNetworkInstance)
     */
    public static int startEasyTierHost(String instanceName, String networkName, String secret) {
        StringBuilder sb = new StringBuilder();

        // Core instance settings
        sb.append("instance_name = \"").append(instanceName).append("\"\n");
        sb.append("instance_id = \"").append(UUID.randomUUID().toString()).append("\"\n");
        sb.append("ipv4 = \"10.144.144.1\"\n");
        sb.append("dhcp = false\n");
        sb.append("listeners = [\n");
        sb.append("    \"tcp://0.0.0.0:11010\",\n");
        sb.append("    \"udp://0.0.0.0:11010\",\n");
        sb.append("    \"wg://0.0.0.0:11010\",\n");
        sb.append("]\n");
        sb.append("rpc_portal = \"0.0.0.0:0\"\n\n");

        // Network identity
        sb.append("[network_identity]\n");
        sb.append("network_name = \"").append(escape(networkName)).append("\"\n");
        sb.append("network_secret = \"").append(escape(secret)).append("\"\n\n");

        // Flags
        sb.append("[flags]\n");
        sb.append("latency_first = true\n");
        sb.append("enable_kcp_proxy = true\n\n");

        // Relay peers
        for (String uri : DEFAULT_PEERS) {
            sb.append("[[peer]]\n");
            sb.append("uri = \"").append(uri).append("\"\n\n");
        }

        String toml = sb.toString();

        // Validate then run
        int parse = NativeBridge.parseConfig(toml);
        if (parse != 0) {
            Log.e(TAG, NativeBridge.getLastError());
            return parse;
        } else {
            int run = NativeBridge.runNetworkInstance(toml);
            if (run != 0)
                Log.e(TAG, NativeBridge.getLastError());
            return run;
        }
    }

    /**
     * Start EasyTier in Guest mode.
     *
     * @param instanceName instance name
     * @param networkName  network name
     * @param secret       secret
     * @param localPort    local TCP port to expose (forward bind)
     * @param remotePort   remote TCP port on host side (forward destination)
     * @param roomKind     TERRACOTTA -> host ip 10.144.144.1, else -> 10.114.51.41 (as in C++)
     * @param ipv4         requested guest IPv4 without CIDR (e.g. "10.144.144.183")
     * @return 0 on success, non-zero on failure (same as runNetworkInstance)
     */
    public static int startEasyTierGuest(
            String instanceName,
            String networkName,
            String secret,
            int localPort,
            int remotePort,
            RoomKind roomKind,
            String ipv4
    ) {
        String hostIp = (roomKind == RoomKind.TERRACOTTA) ? "10.144.144.1" : "10.114.51.41";

        StringBuilder sb = new StringBuilder();

        // Core instance settings
        sb.append("instance_name = \"").append(instanceName).append("\"\n");
        sb.append("instance_id = \"").append(UUID.randomUUID().toString()).append("\"\n");
        sb.append("ipv4 = \"").append(escape(ipv4)).append("/24\"\n");
        sb.append("dhcp = false\n");
        sb.append("listeners = [\n");
        sb.append("    \"tcp://0.0.0.0:11010\",\n");
        sb.append("    \"udp://0.0.0.0:11010\",\n");
        sb.append("    \"wg://0.0.0.0:11010\",\n");
        sb.append("]\n");
        sb.append("rpc_portal = \"0.0.0.0:0\"\n\n");

        // Network identity
        sb.append("[network_identity]\n");
        sb.append("network_name = \"").append(escape(networkName)).append("\"\n");
        sb.append("network_secret = \"").append(escape(secret)).append("\"\n\n");

        // Flags
        sb.append("[flags]\n");
        sb.append("latency_first = true\n");
        sb.append("enable_kcp_proxy = true\n\n");

        // Port forward (IPv6)
        sb.append("[[port_forward]]\n");
        sb.append("proto = \"tcp\"\n");
        sb.append("bind_addr = \"[::]:").append(localPort).append("\"\n");
        sb.append("dst_addr = \"").append(hostIp).append(":").append(remotePort).append("\"\n\n");

        // Port forward (IPv4) — only add if device has any IPv4 interface to avoid bind errors
        if (deviceHasIPv4()) {
            sb.append("[[port_forward]]\n");
            sb.append("proto = \"tcp\"\n");
            sb.append("bind_addr = \"0.0.0.0:").append(localPort).append("\"\n");
            sb.append("dst_addr = \"").append(hostIp).append(":").append(remotePort).append("\"\n\n");
        }

        // Extra peer for room_kind == PCL2CE
        if (roomKind == RoomKind.PCL2CE) {
            sb.append("[[peer]]\n");
            sb.append("uri = \"tcp://43.139.42.188:11010\"\n\n");
        }

        // Relay peers
        for (String uri : DEFAULT_PEERS) {
            sb.append("[[peer]]\n");
            sb.append("uri = \"").append(uri).append("\"\n\n");
        }

        String toml = sb.toString();

        // Validate then run
        int parse = NativeBridge.parseConfig(toml);
        if (parse != 0) {
            Log.e(TAG, NativeBridge.getLastError());
            return parse;
        } else {
            int run = NativeBridge.runNetworkInstance(toml);
            if (run != 0)
                Log.e(TAG, NativeBridge.getLastError());
            return run;
        }
    }

    /**
     * Wrap NativeBridge.setTunFd(instanceName, fd) with a ParcelFileDescriptor parameter.
     * @param instanceName EasyTier instance name (e.g., "Terracotta-Host")
     * @param pfd          ParcelFileDescriptor pointing to the TUN fd (dup recommended)
     * @return native status code (0 == success)
     */
    public static int setTunFd(String instanceName, ParcelFileDescriptor pfd) {
        if (pfd == null)
            return -1;
        try {
            int fd = pfd.getFd();
            return NativeBridge.setTunFd(instanceName, fd);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Stop all EasyTier instances in the current process.
     * Internally calls retainNetworkInstance with an empty set.
     *
     * @return true on success (native returns 0), false otherwise.
     */
    public static boolean stopEasyTier() {
        try {
            int rc = NativeBridge.retainNetworkInstance(new String[0]);
            return rc == 0; // 0 = success per native API
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Connectivity probe:
     * Connect to 127.0.0.1:port, send 0xFE, expect first byte 0xFF.
     * Returns true if the probe succeeds, false otherwise.
     * NOTE: Do not call on Android main thread.
     */
    public static boolean checkConn(int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
            s.setSoTimeout(timeoutMs);

            OutputStream os = s.getOutputStream();
            InputStream is  = s.getInputStream();

            os.write(0xFE);
            os.flush();

            int b = is.read(); // -1 means EOF; exceptions for timeout/IO errors
            return b == 0xFF;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * EasyTier liveness check based on NativeBridge.getNetworkInfos():
     * - running == true
     * - error_msg is empty
     * - my_node_info.virtual_ipv4 is non-empty and not "null"
     * Returns true if all conditions are satisfied.
     * NOTE: This function depends on collect_network_infos() exposing flattened key/value pairs.
     */
    public static boolean isAlive() {
        try {
            // Pull K/V pairs from native. Use a generous upper bound.
            NativeBridge.NetworkInfo[] infos = NativeBridge.getNetworkInfos(512);
            if (infos == null || infos.length == 0) return false;

            String virtualIp = "";
            String errorMsg  = "";
            Boolean running  = null;

            for (NativeBridge.NetworkInfo kv : infos) {
                if (kv == null) continue;
                String k = safe(kv.key).toLowerCase();
                String v = safe(kv.value);

                // running flag (accept "true"/"1")
                if (equalsKey(k, "running")) {
                    running = parseBool(v);
                }

                // virtual IPv4 (any key that contains "virtual_ipv4")
                if (k.contains("virtual_ipv4")) {
                    if (!isNullOrEmptyOrLiteralNull(v)) {
                        virtualIp = v.trim();
                    }
                }

                // error message (accept "error_msg" or keys ending with ".error_msg")
                if (equalsKey(k, "error_msg") || k.endsWith(".error_msg")) {
                    errorMsg = v.trim();
                }
            }

            if (running == null || !running) return false;
            if (isNullOrEmptyOrLiteralNull(virtualIp)) return false;
            return isNullOrEmptyOrLiteralNull(errorMsg);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- helpers ----------

    private static boolean deviceHasIPv4() {
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en == null) return false;
            for (NetworkInterface nif : Collections.list(en)) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                var addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    String host = addrs.nextElement().getHostAddress();
                    if (host != null && host.indexOf(':') < 0) { // crude IPv4 check
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static String escape(String s) {
        if (s == null) return "";
        // Minimal TOML string escaping for quotes and backslashes
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static boolean equalsKey(String k, String expected) {
        // exact match or dotted-tail match, e.g. "my_node_info.running"
        return k.equals(expected) || k.endsWith("." + expected);
    }

    private static boolean isNullOrEmptyOrLiteralNull(String s) {
        if (s == null) return true;
        String t = s.trim();
        return t.isEmpty() || "null".equalsIgnoreCase(t);
    }

    private static boolean parseBool(String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase();
        return "true".equals(t) || "1".equals(t) || "yes".equals(t);
    }
}
