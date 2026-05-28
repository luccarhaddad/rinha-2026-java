package com.rinha.fraud.vec;

/** Ordered top-5 by smallest distance, via 5-slot insertion. Reusable per request. */
public final class TopK {
    private int t0, t1, t2, t3, t4;
    private int i0, i1, i2, i3, i4;
    private int filled;

    public void reset() {
        t0 = t1 = t2 = t3 = t4 = Integer.MAX_VALUE;
        i0 = i1 = i2 = i3 = i4 = -1;
        filled = 0;
    }

    public void offer(int d, int idx) {
        if (d >= t4) { if (filled < 5) filled++; return; }
        if (d < t0)      { t4=t3;i4=i3; t3=t2;i3=i2; t2=t1;i2=i1; t1=t0;i1=i0; t0=d;i0=idx; }
        else if (d < t1) { t4=t3;i4=i3; t3=t2;i3=i2; t2=t1;i2=i1; t1=d;i1=idx; }
        else if (d < t2) { t4=t3;i4=i3; t3=t2;i3=i2; t2=d;i2=idx; }
        else if (d < t3) { t4=t3;i4=i3; t3=d;i3=idx; }
        else             { t4=d;i4=idx; }
        if (filled < 5) filled++;
    }

    /** Number of frauds among the valid top slots (handles n<5). */
    public int countFrauds(byte[] labels) {
        int c = 0;
        if (i0 >= 0) c += labels[i0];
        if (i1 >= 0) c += labels[i1];
        if (i2 >= 0) c += labels[i2];
        if (i3 >= 0) c += labels[i3];
        if (i4 >= 0) c += labels[i4];
        return c;
    }

    public int filled() { return filled; }
    public int bestIndex() { return i0; }
    public String snapshot() {
        return t0+":"+i0+","+t1+":"+i1+","+t2+":"+i2+","+t3+":"+i3+","+t4+":"+i4;
    }
}
