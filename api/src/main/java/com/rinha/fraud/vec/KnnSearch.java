package com.rinha.fraud.vec;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/** SoA tiled int8 brute-force KNN. Scalar reference here; vectorized variant added later. */
public final class KnnSearch {
    public static final int TILE = 4096;
    private KnnSearch() {}

    private static final VectorSpecies<Integer> ISP = IntVector.SPECIES_256;  // 8 int lanes (AVX2)
    private static final VectorSpecies<Byte> BSP = ByteVector.SPECIES_64;     // 8 byte lanes (match ISP)
    private static final java.nio.ByteOrder ORDER = java.nio.ByteOrder.nativeOrder();
    private static final int LANES = ISP.length();

    /** Scalar reference: exact, used as the correctness oracle for the vectorized path. */
    public static void scoreAllScalar(byte[] q, MemorySegment v, int n, int dims, int[] block, TopK top) {
        for (int t = 0; t < n; t += TILE) {
            int len = Math.min(TILE, n - t);
            for (int i = 0; i < len; i++) block[i] = 0;
            for (int d = 0; d < dims; d++) {
                int qd = q[d] & 0xFF;
                long base = (long) d * n + t;
                for (int i = 0; i < len; i++) {
                    int diff = (v.get(ValueLayout.JAVA_BYTE, base + i) & 0xFF) - qd;
                    block[i] += diff * diff;
                }
            }
            for (int i = 0; i < len; i++) top.offer(block[i], t + i);
        }
    }

    /** Vectorized: identical results to scoreAllScalar. */
    public static void scoreAll(byte[] q, MemorySegment v, int n, int dims, int[] block, TopK top) {
        for (int t = 0; t < n; t += TILE) {
            int len = Math.min(TILE, n - t);
            for (int i = 0; i < len; i++) block[i] = 0;
            int simdLen = (len / LANES) * LANES;
            for (int d = 0; d < dims; d++) {
                int qd = q[d] & 0xFF;
                IntVector qv = IntVector.broadcast(ISP, qd);
                long base = (long) d * n + t;
                int i = 0;
                for (; i < simdLen; i += LANES) {
                    ByteVector bv = ByteVector.fromMemorySegment(BSP, v, base + i, ORDER);
                    IntVector vi = ((IntVector) bv.convertShape(VectorOperators.B2I, ISP, 0)).and(0xFF);
                    IntVector diff = vi.sub(qv);
                    IntVector acc = IntVector.fromArray(ISP, block, i);
                    diff.mul(diff).add(acc).intoArray(block, i);
                }
                for (; i < len; i++) {
                    int diff = (v.get(ValueLayout.JAVA_BYTE, base + i) & 0xFF) - qd;
                    block[i] += diff * diff;
                }
            }
            for (int i = 0; i < len; i++) top.offer(block[i], t + i);
        }
    }
}
