package com.rinha.fraud.model;

/** Mutable, reusable holder for one parsed request. Date fields kept as byte offsets into buf. */
public final class FraudRequest {
    public double amount;
    public int installments;
    public byte[] buf;
    public int requestedAtOff;       // offset of first char (YYYY) of transaction.requested_at
    public boolean hasLastTx;
    public int lastTxTimestampOff;   // offset of first char of last_transaction.timestamp
    public double lastTxKmFromCurrent;
    public double customerAvgAmount;
    public boolean customerAvgSeen;  // disambiguates customer vs merchant avg_amount
    public int txCount24h;
    public double kmFromHome;
    public boolean isOnline;
    public boolean cardPresent;
    public int mcc;                  // merchant.mcc parsed to int (-1 if non-numeric)
    public double merchantAvgAmount;
    public boolean unknownMerchant;  // true if merchant.id not in customer.known_merchants
    public int merchIdStart, merchIdEnd;

    private final int[] kmStart = new int[64];
    private final int[] kmEnd = new int[64];
    public int[] kmStartScratch() { return kmStart; }
    public int[] kmEndScratch() { return kmEnd; }

    public void reset() {
        amount = 0; installments = 0; buf = null; requestedAtOff = -1;
        hasLastTx = false; lastTxTimestampOff = -1; lastTxKmFromCurrent = 0;
        customerAvgAmount = 0; customerAvgSeen = false; txCount24h = 0; kmFromHome = 0;
        isOnline = false; cardPresent = false; mcc = -1;
        merchantAvgAmount = 0; unknownMerchant = false;
        merchIdStart = -1; merchIdEnd = -1;
    }
}
