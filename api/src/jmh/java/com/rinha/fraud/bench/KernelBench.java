package com.rinha.fraud.bench;

import com.rinha.fraud.vec.KnnSearch;
import com.rinha.fraud.vec.TopK;
import org.openjdk.jmh.annotations.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules=jdk.incubator.vector"})
public class KernelBench {
    @Param({"3000000"})
    int n;
    int dims = 14;
    MemorySegment seg;
    byte[] q;
    int[] block;
    TopK top;

    @Setup
    public void setup() {
        Random r = new Random(1);
        seg = Arena.ofShared().allocate((long) n * dims);
        for (long i = 0; i < (long) n * dims; i++) seg.set(ValueLayout.JAVA_BYTE, i, (byte) r.nextInt(256));
        q = new byte[dims];
        for (int d = 0; d < dims; d++) q[d] = (byte) r.nextInt(256);
        block = new int[KnnSearch.TILE];
        top = new TopK();
    }

    @Benchmark
    public void scoreAllVector() {
        top.reset();
        KnnSearch.scoreAll(q, seg, n, dims, block, top);
    }

    @Benchmark
    public void scoreAllScalar() {
        top.reset();
        KnnSearch.scoreAllScalar(q, seg, n, dims, block, top);
    }
}
