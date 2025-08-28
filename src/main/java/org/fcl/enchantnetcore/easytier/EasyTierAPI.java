package org.fcl.enchantnetcore.easytier;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.fcl.enchantnetcore.core.RoomKind;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
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

        // Port forward (IPv4)
        sb.append("[[port_forward]]\n");
        sb.append("proto = \"tcp\"\n");
        sb.append("bind_addr = \"0.0.0.0:").append(localPort).append("\"\n");
        sb.append("dst_addr = \"").append(hostIp).append(":").append(remotePort).append("\"\n\n");

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
     */
    public static boolean isAlive() {
        try {
            NativeBridge.NetworkInfo[] infos = NativeBridge.getNetworkInfos(512);
            if (infos == null || infos.length == 0) return false;

            JSONObject root = null;

            // 1) Scan all values; try to extract and parse JSON from each value string
            for (NativeBridge.NetworkInfo kv : infos) {
                if (kv == null) continue;
                String v = safe(kv.value);
                String cand = extractJSONObjectString(v);
                if (cand == null) continue;
                try {
                    JSONObject obj = new JSONObject(cand);
                    // Heuristic: pick the first object that looks like the status root
                    if (obj.has("running") || obj.has("my_node_info")) {
                        root = obj;
                        break;
                    }
                } catch (Throwable ignore) {
                    // Not a valid JSON object; keep looking
                }
            }

            if (root == null) return false;

            // 2) running flag (accept true/1/yes)
            boolean running = parseBoolFromJson(root.opt("running"));

            // 3) error message (treat null/empty/"null"/"none"/"nil" as no error)
            String errorMsg = null;
            if (!root.isNull("error_msg")) {
                Object em = root.opt("error_msg");
                errorMsg = (em == null) ? null : String.valueOf(em);
            }

            // 4) virtual IPv4 presence:
            //    A) numeric uint32 at my_node_info.virtual_ipv4.address.addr (non-zero)
            //    B) dotted IPv4 string (optionally with "/len") not equal to 0.0.0.0
            boolean hasV4 = false;
            JSONObject myNode = root.optJSONObject("my_node_info");
            if (myNode != null) {
                Object v4 = myNode.opt("virtual_ipv4");
                if (v4 instanceof JSONObject) {
                    JSONObject v4obj = (JSONObject) v4;
                    // A) numeric form
                    JSONObject addrObj = v4obj.optJSONObject("address");
                    long addr = (addrObj == null) ? 0L : addrObj.optLong("addr", 0L);
                    hasV4 = addr != 0L;
                    // B) dotted IPv4 string fallback (some builds may expose "ip": "10.144.144.2")
                    if (!hasV4) {
                        String ip = v4obj.optString("ip", "");
                        hasV4 = looksLikeIPv4(ip) && !"0.0.0.0".equals(ip);
                    }
                } else if (v4 instanceof String) {
                    // B) dotted IPv4 string or "ip/prefixlen"
                    String s = ((String) v4).trim();
                    String ip = s.contains("/") ? s.substring(0, s.indexOf('/')) : s;
                    hasV4 = looksLikeIPv4(ip) && !"0.0.0.0".equals(ip) && !isNullish(ip);
                }
            }

            if (!running) return false;
            if (!hasV4) return false;
            return isNullish(errorMsg);
        } catch (Throwable t) {
            // Any exception -> consider not alive
            return false;
        }
    }

    // ---------- helpers ----------

    private static String escape(String s) {
        if (s == null) return "";
        // Minimal TOML string escaping for quotes and backslashes
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String safe(Object s) {
        return (s == null) ? "" : String.valueOf(s);
    }

    /** Extract a JSON object substring from arbitrary text (first '{' .. last '}'). */
    private static String extractJSONObjectString(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return raw.substring(start, end + 1);
    }

    /** Parse a boolean from JSON value; accepts true/1/yes (case-insensitive). */
    private static boolean parseBoolFromJson(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        String t = safe(v).trim().toLowerCase(java.util.Locale.ROOT);
        return "true".equals(t) || "1".equals(t) || "yes".equals(t);
    }

    /** Treat null/empty/"null"/"none"/"nil" (case-insensitive) as null-ish. */
    private static boolean isNullish(String v) {
        if (v == null) return true;
        String t = v.trim();
        if (t.isEmpty()) return true;
        String tl = t.toLowerCase(java.util.Locale.ROOT);
        return "null".equals(tl) || "none".equals(tl) || "nil".equals(tl);
    }

    /** Loose IPv4 check: dotted-quad with each octet 0..255. */
    private static boolean looksLikeIPv4(String v) {
        String t = safe(v).trim();
        if (!t.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) return false;
        try {
            String[] parts = t.split("\\.");
            for (String p : parts) {
                int n = Integer.parseInt(p);
                if (n < 0 || n > 255) return false;
            }
            return true;
        } catch (Exception ignore) {
            return false;
        }
    }
}
