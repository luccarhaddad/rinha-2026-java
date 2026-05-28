package com.rinha.fraud.vec;

import org.junit.jupiter.api.Test;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.assertEquals;

class KnnSearchScalarTest {
    @Test
    void matchesNaiveReference() {
        int n = 1000, dims = 14;
        Random rnd = new Random(42);
        byte[] soa = new byte[n * dims];
        for (int i = 0; i < soa.length; i++) soa[i] = (byte) rnd.nextInt(256);
        MemorySegment seg = Arena.ofAuto().allocate(soa.length);
        MemorySegment.copy(soa, 0, seg, ValueLayout.JAVA_BYTE, 0, soa.length);
        byte[] q = new byte[dims];
        for (int d = 0; d < dims; d++) q[d] = (byte) rnd.nextInt(256);

        int bestIdx = -1; long best = Long.MAX_VALUE;
        for (int r = 0; r < n; r++) {
            long acc = 0;
            for (int d = 0; d < dims; d++) {
                int diff = (soa[d*n + r] & 0xFF) - (q[d] & 0xFF);
                acc += (long) diff * diff;
            }
            if (acc < best) { best = acc; bestIdx = r; }
        }

        TopK top = new TopK(); top.reset();
        int[] block = new int[KnnSearch.TILE];
        KnnSearch.scoreAllScalar(q, seg, n, dims, block, top);
        assertEquals(bestIdx, top.bestIndex());
    }
}
