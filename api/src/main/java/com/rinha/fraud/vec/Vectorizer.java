package com.rinha.fraud.vec;

import com.rinha.fraud.data.MccRisk;
import com.rinha.fraud.data.Norm;
import com.rinha.fraud.model.FraudRequest;
import com.rinha.fraud.util.DateUtil;

public final class Vectorizer {
    private Vectorizer() {}

    public static void vectorize(FraudRequest r, float[] out, Norm n, MccRisk mcc) {
        out[0] = clamp((float) (r.amount / n.maxAmount()));
        out[1] = clamp((float) r.installments / n.maxInstallments());
        out[2] = clamp((float) ((r.amount / r.customerAvgAmount) / n.amountVsAvgRatio()));
        out[3] = DateUtil.hour(r.buf, r.requestedAtOff) / 23f;
        out[4] = DateUtil.dayOfWeek(r.buf, r.requestedAtOff) / 6f;
        if (r.hasLastTx) {
            long minutes = (DateUtil.epochSeconds(r.buf, r.requestedAtOff)
                          - DateUtil.epochSeconds(r.buf, r.lastTxTimestampOff)) / 60L;
            out[5] = clamp((float) minutes / n.maxMinutes());
            out[6] = clamp((float) (r.lastTxKmFromCurrent / n.maxKm()));
        } else {
            out[5] = -1f;
            out[6] = -1f;
        }
        out[7] = clamp((float) (r.kmFromHome / n.maxKm()));
        out[8] = clamp((float) r.txCount24h / n.maxTxCount24h());
        out[9] = r.isOnline ? 1f : 0f;
        out[10] = r.cardPresent ? 1f : 0f;
        out[11] = r.unknownMerchant ? 1f : 0f;
        out[12] = mcc.risk(r.mcc);
        out[13] = clamp((float) (r.merchantAvgAmount / n.maxMerchantAvgAmount()));
    }

    /** Vectorize directly to quantized bytes (hot path). */
    public static void vectorizeQuantized(FraudRequest r, float[] scratch, byte[] outQ, Norm n, MccRisk mcc) {
        vectorize(r, scratch, n, mcc);
        for (int d = 0; d < 14; d++) outQ[d] = Quantizer.qb(scratch[d]);
    }

    private static float clamp(float x) { return x < 0f ? 0f : (x > 1f ? 1f : x); }
}
