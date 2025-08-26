package org.fcl.enchantnetcore.utils;

import org.fcl.enchantnetcore.core.RoomKind;

import java.math.BigInteger;

/**
 * Fast invite-code validity detector for realtime input.
 * Returns one of InviteCode.RoomKind: TERRACOTTA / PCL2CE / INVALID.
 * <p>
 * It mirrors the lightweight checks in InviteCode.parse logic but avoids
 * heavy conversions where possible.
 */
public final class InviteQuickValidator {
    private InviteQuickValidator() {}

    // Base34 used by Terracotta (no I, O). I=>1, O=>0 tolerated by normalizer.
    private static final String BASE34 = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    // PCL2CE threshold as per C++ logic
    private static final long PCL2CE_THRESHOLD = 999_999_999_965_536L;

    /**
     * Quick detection.
     * - TERRACOTTA: exactly 25 base34 digits (ignoring non-alnum), checksum matches
     * - PCL2CE: 1..10 chars from Crockford-like base32 set; after decoding
     *           value < 999999999965536 and decimal length == 14 or 15; if 15, low 5 digits < 65536
     */
    public static RoomKind quickDetectKind(String input) {
        if (input == null) return RoomKind.INVALID;

        // Try TERRACOTTA (no BigInteger needed):
        int[] digits = new int[25];
        int count = 0;
        for (int i = 0; i < input.length() && count < 25; i++) {
            char c = input.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                int d = lookupBase34(c);
                if (d >= 0) digits[count++] = d;
            }
        }
        if (count == 25) {
            int sum = 0;
            for (int i = 0; i < 24; i++) sum += digits[i];
            if ((sum % 34) == digits[24]) return RoomKind.TERRACOTTA;
        }

        // Try PCL2CE
        String s = input.trim();
        if (!s.isEmpty() && s.length() <= 10) {
            BigInteger value = BigInteger.ZERO;
            for (int i = 0; i < s.length(); i++) {
                int v = pcl32Lookup(s.charAt(i));
                if (v < 0) { value = null; break; }
                value = value.shiftLeft(5).add(BigInteger.valueOf(v));
            }
            if (value != null) {
                if (value.compareTo(BigInteger.valueOf(PCL2CE_THRESHOLD)) < 0) {
                    String dec = value.toString();
                    if (dec.length() == 14) {
                        return RoomKind.PCL2CE;
                    } else if (dec.length() == 15) {
                        long portVal = value.mod(BigInteger.valueOf(100_000L)).longValue();
                        if (portVal < 65_536L) return RoomKind.PCL2CE;
                    }
                }
            }
        }

        return RoomKind.INVALID;
    }

    private static int lookupBase34(char c) {
        char u = Character.toUpperCase(c);
        if (u == 'I') u = '1';
        if (u == 'O') u = '0';
        return BASE34.indexOf(u);
    }

    private static int pcl32Lookup(char ch) {
        char u = Character.toUpperCase(ch);
        if (u >= '2' && u <= '9') return u - '2'; // 0..7
        if (u >= 'A' && u <= 'H') return (u - 'A') + 8; // 8..15
        if (u >= 'J' && u <= 'N') return (u - 'J') + 16; // 16..20
        if (u >= 'P' && u <= 'Z') return (u - 'P') + 21; // 21..31
        return -1;
    }
}
