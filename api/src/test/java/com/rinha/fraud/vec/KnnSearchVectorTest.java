package com.rinha.fraud.vec;

import org.junit.jupiter.api.Test;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.assertEquals;

class KnnSearchVectorTest {
    @Test
    void vectorMatchesScalarTop5() {
        int n = 5003, dims = 14; // non-multiple of lane width exercises the tail
        Random rnd = new Random(7);
        byte[] soa = new byte[n * dims];
        for (int i = 0; i < soa.length; i++) soa[i] = (byte) rnd.nextInt(256);
        MemorySegment seg = Arena.ofAuto().allocate(soa.length);
        MemorySegment.copy(soa, 0, seg, ValueLayout.JAVA_BYTE, 0, soa.length);
        byte[] q = new byte[dims];
        for (int d = 0; d < dims; d++) q[d] = (byte) rnd.nextInt(256);

        int[] block = new int[KnnSearch.TILE];
        TopK a = new TopK(); a.reset();
        KnnSearch.scoreAllScalar(q, seg, n, dims, block, a);
        TopK b = new TopK(); b.reset();
        KnnSearch.scoreAll(q, seg, n, dims, block, b);

        assertEquals(a.snapshot(), b.snapshot());
    }
}
