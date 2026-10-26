package com.startrac.bridge;

/** Bluetooth SIG Running Speed and Cadence measurement (0x2A53). */
public final class RscCodec {
    private RscCodec() { }
    /** Cadence is unknown: the RSC mandatory field is set to 0, NEVER guessed. */
    public static byte[] encode(double kmh, double totalDistanceMeters) {
        int speed = (int)Math.round(Math.max(0, kmh) / 3.6 * 256.0);
        speed = Math.max(0, Math.min(65535, speed));
        long distDecimeters = Math.max(0, Math.min(0xffffffffL,
            Math.round(Math.max(0, totalDistanceMeters) * 10.0)));
        boolean running = kmh >= 6.0;
        return new byte[] {
            (byte)(0x02 | (running ? 0x04 : 0x00)), // total distance included + walk/run
            (byte)speed, (byte)(speed >>> 8),
            0, // mandatory cadence: 0 because unknown; may affect Garmin's interpretation
            (byte)distDecimeters, (byte)(distDecimeters >>> 8),
            (byte)(distDecimeters >>> 16), (byte)(distDecimeters >>> 24)
        };
    }
}
