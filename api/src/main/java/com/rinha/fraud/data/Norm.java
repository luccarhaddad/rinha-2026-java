package com.rinha.fraud.data;

/** Normalization constants from normalization.json (static — never changes). */
public record Norm(
        double maxAmount, int maxInstallments, double amountVsAvgRatio,
        int maxMinutes, int maxKm, int maxTxCount24h, double maxMerchantAvgAmount) {

    public static final Norm DEFAULT =
            new Norm(10000, 12, 10, 1440, 1000, 20, 10000);
}
