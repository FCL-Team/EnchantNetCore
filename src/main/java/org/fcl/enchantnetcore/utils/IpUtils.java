package org.fcl.enchantnetcore.utils;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;

import org.fcl.enchantnetcore.core.RoomKind;

import java.net.DatagramSocket;

public class IpUtils {

    public static int getRandomUdpPort() {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            return 35781;
        }
    }

    public static String pickGuestIpV4(String deviceUuid, RoomKind kind, String networkName, String secret) {
        String base = (kind == RoomKind.TERRACOTTA) ? "10.144.144" : "10.114.51";
        String seed = deviceUuid + "|" + kind + "|" + networkName + "|" + secret + "|" + base;
        int host = (sha256FirstByte(seed) % 253) + 2; // 2..254
        if (kind == RoomKind.TERRACOTTA && host == 1)
            host = 2;
        if (kind == RoomKind.PCL2CE     && host == 41)
            host = host + 1;
        return base + "." + host;
    }

    public static String getDeviceUUID(Context ctx) {
        final String PREFS = "enchantnet_prefs", KEY = "device_uuid";
        String saved = ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY, null);
        if (saved != null)
            return saved;
        String uuid = java.util.UUID.randomUUID().toString();
        ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY, uuid).apply();
        return uuid;
    }

    private static int sha256FirstByte(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return (md.digest(s.getBytes())[0] & 0xFF);
        } catch (Exception e) {
            return 128;
        }
    }

}
