package org.fcl.enchantnetcore.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public class InviteCode {

    private static final String BASE34 = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ"; // no I, O
    private static final BigInteger BI_34 = BigInteger.valueOf(34);
    private static final BigInteger MASK_64 = new BigInteger("FFFFFFFFFFFFFFFF", 16);

    private static int lookupBase34(char c) {
        char u = Character.toUpperCase(c);
        if (u == 'I') u = '1';
        if (u == 'O') u = '0';
        int idx = BASE34.indexOf(u);
        return idx >= 0 ? idx : -1;
    }

    private static int pcl32Lookup(char ch) {
        char u = Character.toUpperCase(ch);
        if (u >= '2' && u <= '9') return u - '2';           // 0..7
        if (u >= 'A' && u <= 'H') return (u - 'A') + 8;      // 8..15
        if (u >= 'J' && u <= 'N') return (u - 'J') + 16;     // 16..20
        if (u >= 'P' && u <= 'Z') return (u - 'P') + 21;     // 21..31
        return -1;
    }

    private static String trim(String s) {
        int i = 0, j = s.length();
        while (i < j && Character.isWhitespace(s.charAt(i))) i++;
        while (j > i && Character.isWhitespace(s.charAt(j - 1))) j--;
        return s.substring(i, j);
    }

    private static Room invalid() {
        Room r = new Room();
        r.roomKind = RoomKind.INVALID;
        return r;
    }

    /**
     * Generate a Terracotta-style invite code. roomId is generated internally.
     * @param port 0..65535
     * @return formatted code XXXXX-XXXXX-XXXXX-XXXXX-XXXXX
     */
    public static String generateInviteCode(int port) {
        if (port < 0 || port > 0xFFFF)
            throw new IllegalArgumentException("port out of range");

        // Generate 64-bit roomId via SecureRandom (analogue to std::random_device)
        java.security.SecureRandom sr = new java.security.SecureRandom();
        long roomId = ((long) sr.nextInt() << 32) | (sr.nextInt() & 0xFFFFFFFFL);

        // 15 bytes seeded by MT19937_64(roomId), last 2 bytes set to port (big-endian)
        byte[] buf = new byte[15];
        MT19937_64 rng = new MT19937_64(roomId);
        for (int i = 0; i < 15; i++) buf[i] = (byte) (rng.nextLong() & 0xFFL);
        buf[13] = (byte) ((port >>> 8) & 0xFF);
        buf[14] = (byte) (port & 0xFF);

        BigInteger value = new BigInteger(1, buf);
        char[] chars = new char[25];
        int checksum = 0;
        for (int i = 0; i < 15; i++) {
            int r = value.mod(BI_34).intValue();
            chars[i] = BASE34.charAt(r);
            checksum = (checksum + r) % 34;
            value = value.divide(BI_34);
        }
        for (int i = 15; i < 24; i++) {
            int r = value.mod(BI_34).intValue();
            chars[i] = BASE34.charAt(r);
            checksum = (checksum + r) % 34;
            value = value.divide(BI_34);
        }
        chars[24] = BASE34.charAt(checksum);

        StringBuilder out = new StringBuilder(29);
        for (int i = 0; i < 25; i++) {
            out.append(chars[i]);
            if ((i + 1) % 5 == 0 && i + 1 < 25) out.append('-');
        }
        return out.toString();
    }

    /**
     * Parse either Terracotta (Base34) or PCL2CE (Base32-like) invite codes.
     */
    public static Room parseInviteCode(String input) {
        Room res;

        // 1) Try Terracotta
        res = tryParseTerracotta(input);
        if (res.roomKind != RoomKind.INVALID) return res;

        // 2) Try PCL2CE
        res = tryParsePcl2Ce(input);
        if (res.roomKind != RoomKind.INVALID) return res;

        // 3) Invalid
        res = new Room();
        res.roomKind = RoomKind.INVALID;
        return res;
    }

    // ================= Terracotta =================
    private static Room tryParseTerracotta(String input) {
        List<Integer> digits = new ArrayList<>(25);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                int d = lookupBase34(c);
                if (d < 0) return invalid();
                digits.add(d);
            }
        }
        if (digits.size() != 25) return invalid();

        int checksum = 0;
        for (int i = 0; i < 24; i++) checksum += digits.get(i);
        if ((checksum % 34) != digits.get(24)) return invalid();

        // Reconstruct low 64 bits from base-34 digits (reverse order multiply-accumulate)
        BigInteger total = BigInteger.ZERO;
        for (int i = 24; i >= 0; i--) {
            total = total.multiply(BI_34).add(BigInteger.valueOf(digits.get(i)));
        }
        long low64 = total.and(MASK_64).longValue();
        int port = (int) (low64 & 0xFFFFL);
        long roomId = (low64 >>> 16);

        Room r = new Room();
        r.port = port;
        r.roomId = roomId;
        StringBuilder name = new StringBuilder(15);
        StringBuilder secret = new StringBuilder(10);
        for (int i = 0; i < 15; i++) name.append(BASE34.charAt(digits.get(i)));
        for (int i = 15; i < 25; i++) secret.append(BASE34.charAt(digits.get(i)));
        r.name = "terracotta-mc-" + name.toString().toLowerCase();
        r.secret = secret.toString().toLowerCase();
        r.roomKind = RoomKind.TERRACOTTA;        return r;
    }

    // ================= PCL2CE (Base32-like) =================
    private static Room tryParsePcl2Ce(String input) {
        String s = trim(input);
        if (s.isEmpty() || s.length() > 10) return invalid();

        BigInteger value = BigInteger.ZERO;
        for (int i = 0; i < s.length(); i++) {
            int v = pcl32Lookup(s.charAt(i));
            if (v < 0) return invalid();
            value = value.shiftLeft(5).add(BigInteger.valueOf(v));
        }

        // threshold: 999999999965536
        if (value.compareTo(BigInteger.valueOf(999_999_999_965_536L)) >= 0) return invalid();

        String dec = value.toString();
        if (dec.length() < 10) return invalid();

        long portVal;
        if (dec.length() == 14) {
            portVal = value.mod(BigInteger.valueOf(10_000L)).longValue();
        } else if (dec.length() == 15) {
            portVal = value.mod(BigInteger.valueOf(100_000L)).longValue();
            if (portVal >= 65_536L) return invalid();
        } else {
            return invalid();
        }

        Room r = new Room();
        r.roomId = 0L;
        r.port = (int) portVal;
        String n = dec.substring(0, 8);
        String s2 = dec.substring(8, 10);
        r.name = "PCLCELobby" + n;
        r.secret = "PCLCEETLOBBY2025" + s2;
        r.roomKind = RoomKind.PCL2CE;        return r;
    }

    // ================= MT19937-64 (std::mt19937_64 compatible) =================
    private static final class MT19937_64 {
        private static final int NN = 312;
        private static final int MM = 156;
        private static final long MATRIX_A = 0xB5026F5AA96619E9L;
        private static final long UM = 0xFFFFFFFF80000000L; // Most significant 33 bits
        private static final long LM = 0x7FFFFFFFL;          // Least significant 31 bits

        private final long[] mt = new long[NN];
        private int mti = NN + 1;

        MT19937_64(long seed) { setSeed(seed); }

        private void setSeed(long seed) {
            mt[0] = seed;
            for (mti = 1; mti < NN; mti++) {
                long x = mt[mti - 1];
                x ^= (x >>> 62);
                mt[mti] = 6364136223846793005L * x + mti; // implicit mod 2^64 via overflow
            }
        }

        long nextLong() {
            long x;
            if (mti >= NN) twist();
            x = mt[mti++];

            // tempering
            x ^= (x >>> 29) & 0x5555555555555555L;
            x ^= (x << 17) & 0x71D67FFFEDA60000L;
            x ^= (x << 37) & 0xFFF7EEE000000000L;
            x ^= (x >>> 43);
            return x;
        }

        private void twist() {
            int i = 0;
            long x;
            for (; i < NN - MM; i++) {
                x = (mt[i] & UM) | (mt[i + 1] & LM);
                mt[i] = mt[i + MM] ^ (x >>> 1) ^ ((x & 1L) != 0 ? MATRIX_A : 0L);
            }
            for (; i < NN - 1; i++) {
                x = (mt[i] & UM) | (mt[i + 1] & LM);
                mt[i] = mt[i + (MM - NN)] ^ (x >>> 1) ^ ((x & 1L) != 0 ? MATRIX_A : 0L);
            }
            x = (mt[NN - 1] & UM) | (mt[0] & LM);
            mt[NN - 1] = mt[MM - 1] ^ (x >>> 1) ^ ((x & 1L) != 0 ? MATRIX_A : 0L);
            mti = 0;
        }
    }

}
