package com.rinha.fraud.data;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** mmap-backed SoA int8 dataset: vectors[(long)d*n + r], plus labels[r]. */
public final class Dataset {
    public final int n;
    public final int dims;
    public final MemorySegment vectors; // n*dims bytes, SoA (dim-major)
    public final byte[] labels;

    private Dataset(int n, int dims, MemorySegment vectors, byte[] labels) {
        this.n = n; this.dims = dims; this.vectors = vectors; this.labels = labels;
    }

    public static Dataset load(Path bin, Path labelsPath) throws IOException {
        try (FileChannel ch = FileChannel.open(bin, StandardOpenOption.READ)) {
            ByteBuffer hdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            ch.read(hdr, 0);
            hdr.flip();
            int n = hdr.getInt();
            int dims = hdr.getInt();
            long payload = (long) n * dims;
            MemorySegment seg = ch.map(FileChannel.MapMode.READ_ONLY, 8, payload, Arena.global());
            byte[] labels = Files.readAllBytes(labelsPath);
            if (labels.length != n) throw new IOException("labels length " + labels.length + " != n " + n);
            return new Dataset(n, dims, seg, labels);
        }
    }

    /** Unsigned byte at dimension d, record r. */
    public int byteAt(int d, int r) {
        return vectors.get(ValueLayout.JAVA_BYTE, (long) d * n + r) & 0xFF;
    }
}
