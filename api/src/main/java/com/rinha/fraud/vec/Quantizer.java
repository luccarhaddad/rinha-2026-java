package com.rinha.fraud.vec;

/** Maps a normalized value in [-1,1] to an unsigned byte [0,255]. */
public final class Quantizer {
    private Quantizer() {}

    /** Returns 0..255. -1 (sentinel) -> 0, 0.0 -> 128, 1.0 -> 255. */
    public static int q(float x) {
        int v = Math.round((x + 1f) * 0.5f * 255f);
        if (v < 0) return 0;
        if (v > 255) return 255;
        return v;
    }

    /** Same as q(float) but returns the value packed in a (signed) byte. */
    public static byte qb(float x) {
        return (byte) q(x);
    }
}
