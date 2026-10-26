package com.startrac.bridge;

/** Decodes only the observed 19-byte GymConnect frame; 6-byte frames are intentionally ignored. */
public final class GymConnectDecoder {
    private GymConnectDecoder() { }
    public static final class Reading {
        public final double speedKmh;
        public final long distanceMeters, elapsedSeconds;
        Reading(double speed, long dist, long seconds) {
            speedKmh = speed; distanceMeters = dist; elapsedSeconds = seconds;
        }
    }
    private static long u(byte b) { return b & 255L; }
    private static long le32(byte[] b, int i) {
        return u(b[i]) | (u(b[i+1]) << 8) | (u(b[i+2]) << 16) | (u(b[i+3]) << 24);
    }
    public static Reading decode(byte[] b) {
        if (b == null || b.length != 19 || (b[0] & 255) != 0x53 ||
                (b[1] & 255) != 0xCB || (b[2] & 255) != 0x0E) return null;
        long speedRaw = u(b[7]) | (u(b[8]) << 8);
        double speed = speedRaw / 100.0;
        if (speed < 0 || speed > 35) return null; // Sanity check, not a treadmill speed limit.
        return new Reading(speed, le32(b, 9), le32(b, 3));
    }
    public static byte[] fromHex(String s) {
        String[] parts = s.trim().split("[-: \\t]+");
        byte[] result = new byte[parts.length];
        for (int i=0; i<parts.length; i++) result[i] = (byte)Integer.parseInt(parts[i], 16);
        return result;
    }
}
