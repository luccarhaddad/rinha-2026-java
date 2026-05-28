package com.rinha.fraud.data;

/** MCC (4-digit string) -> risk in [0,1]; 0.5 default. Lookup via int-indexed array. */
public final class MccRisk {
    private final float[] table;

    private MccRisk(float[] table) { this.table = table; }

    public static MccRisk defaults() {
        float[] t = new float[10000];
        java.util.Arrays.fill(t, 0.5f);
        t[5411] = 0.15f; t[5812] = 0.30f; t[5912] = 0.20f; t[5944] = 0.45f;
        t[7801] = 0.80f; t[7802] = 0.75f; t[7995] = 0.85f; t[4511] = 0.35f;
        t[5311] = 0.25f; t[5999] = 0.50f;
        return new MccRisk(t);
    }

    public float risk(String mcc) {
        int code = parse(mcc);
        return (code >= 0 && code < table.length) ? table[code] : 0.5f;
    }

    /** Risk for an MCC already parsed to int; -1 or out-of-range -> 0.5. */
    public float risk(int code) {
        return (code >= 0 && code < table.length) ? table[code] : 0.5f;
    }

    private static int parse(String s) {
        if (s == null) return -1;
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            int d = s.charAt(i) - '0';
            if (d < 0 || d > 9) return -1;
            v = v * 10 + d;
        }
        return v;
    }
}
