package com.rinha.fraud.http;

import com.rinha.fraud.model.FraudRequest;

/** Zero-alloc parser for the fixed fraud-score schema. Walks the buffer once, dispatching on key names. */
public final class JsonParser {
    private JsonParser() {}

    public static void parse(byte[] b, int len, FraudRequest r) {
        r.buf = b;
        int[] kmS = r.kmStartScratch();
        int[] kmE = r.kmEndScratch();
        int kmCount = 0;

        int i = 0;
        while (i < len) {
            if (b[i] != '"') { i++; continue; }
            int ks = i + 1;
            int ke = ks;
            while (ke < len && b[ke] != '"') ke++;
            int after = ke + 1;
            int p = after;
            while (p < len && (b[p] == ' ' || b[p] == '\n' || b[p] == '\t' || b[p] == '\r')) p++;

            if (p < len && b[p] == ':') {
                int vs = p + 1;
                while (vs < len && (b[vs] == ' ' || b[vs] == '\n' || b[vs] == '\t' || b[vs] == '\r')) vs++;
                if (matches(b, ks, ke, "known_merchants")) {
                    // vs points at '['; collect each "..." span until ']'
                    int j = vs + 1;
                    while (j < len && b[j] != ']') {
                        if (b[j] == '"') {
                            int s = j + 1, e = s;
                            while (e < len && b[e] != '"') e++;
                            if (kmCount < kmS.length) { kmS[kmCount] = s; kmE[kmCount] = e; kmCount++; }
                            j = e + 1;
                        } else {
                            j++;
                        }
                    }
                    i = (j < len) ? j + 1 : j;
                    continue;
                }
                dispatch(b, ks, ke, vs, r);
                i = after;
                continue;
            }
            // non-key string value: ignore
            i = after;
        }

        r.unknownMerchant = true;
        if (r.merchIdStart >= 0) {
            int idLen = r.merchIdEnd - r.merchIdStart;
            for (int k = 0; k < kmCount; k++) {
                if (kmE[k] - kmS[k] == idLen && regionEquals(b, r.merchIdStart, kmS[k], idLen)) {
                    r.unknownMerchant = false;
                    break;
                }
            }
        }
    }

    private static void dispatch(byte[] b, int ks, int ke, int vs, FraudRequest r) {
        switch (b[ks]) {
            case 'a' -> {
                if (matches(b, ks, ke, "amount")) r.amount = parseDouble(b, vs);
                else if (matches(b, ks, ke, "avg_amount")) {
                    if (!r.customerAvgSeen) { r.customerAvgAmount = parseDouble(b, vs); r.customerAvgSeen = true; }
                    else r.merchantAvgAmount = parseDouble(b, vs);
                }
            }
            case 'i' -> {
                if (matches(b, ks, ke, "installments")) r.installments = (int) parseLong(b, vs);
                else if (matches(b, ks, ke, "is_online")) r.isOnline = (b[vs] == 't');
                else if (matches(b, ks, ke, "id")) {
                    int s = vs + 1;
                    if (s < b.length && b[s] == 'M') {
                        int e = s; while (e < b.length && b[e] != '"') e++;
                        r.merchIdStart = s; r.merchIdEnd = e;
                    }
                }
            }
            case 'r' -> { if (matches(b, ks, ke, "requested_at")) r.requestedAtOff = vs + 1; }
            case 't' -> {
                if (matches(b, ks, ke, "tx_count_24h")) r.txCount24h = (int) parseLong(b, vs);
                else if (matches(b, ks, ke, "timestamp")) r.lastTxTimestampOff = vs + 1;
            }
            case 'k' -> {
                if (matches(b, ks, ke, "km_from_home")) r.kmFromHome = parseDouble(b, vs);
                else if (matches(b, ks, ke, "km_from_current")) r.lastTxKmFromCurrent = parseDouble(b, vs);
            }
            case 'c' -> { if (matches(b, ks, ke, "card_present")) r.cardPresent = (b[vs] == 't'); }
            case 'm' -> { if (matches(b, ks, ke, "mcc")) r.mcc = parseIntString(b, vs + 1); }
            case 'l' -> { if (matches(b, ks, ke, "last_transaction")) r.hasLastTx = (b[vs] != 'n'); }
            default -> { }
        }
    }

    private static boolean matches(byte[] b, int ks, int ke, String key) {
        int klen = ke - ks;
        if (klen != key.length()) return false;
        for (int j = 0; j < klen; j++) if (b[ks + j] != (byte) key.charAt(j)) return false;
        return true;
    }
    private static boolean regionEquals(byte[] b, int a, int c, int n) {
        for (int j = 0; j < n; j++) if (b[a + j] != b[c + j]) return false;
        return true;
    }
    private static int parseIntString(byte[] b, int s) {
        int v = 0; boolean any = false;
        while (b[s] >= '0' && b[s] <= '9') { v = v * 10 + (b[s] - '0'); s++; any = true; }
        return any ? v : -1;
    }
    private static long parseLong(byte[] b, int s) {
        boolean neg = false; if (b[s] == '-') { neg = true; s++; }
        long v = 0; while (b[s] >= '0' && b[s] <= '9') { v = v * 10 + (b[s] - '0'); s++; }
        return neg ? -v : v;
    }
    private static double parseDouble(byte[] b, int s) {
        boolean neg = false; if (b[s] == '-') { neg = true; s++; }
        long intPart = 0;
        while (b[s] >= '0' && b[s] <= '9') { intPart = intPart * 10 + (b[s] - '0'); s++; }
        double v = intPart;
        if (b[s] == '.') {
            s++;
            double frac = 0, scale = 0.1;
            while (b[s] >= '0' && b[s] <= '9') { frac += (b[s] - '0') * scale; scale *= 0.1; s++; }
            v += frac;
        }
        return neg ? -v : v;
    }
}
