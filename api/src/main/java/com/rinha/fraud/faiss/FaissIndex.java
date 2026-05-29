package com.rinha.fraud.faiss;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Java FFM wrapper for the FAISS C API.
 *
 * <p>Loads {@code libfaiss_c.so} (built from FAISS v1.14.2 source, see
 * {@code docker/Dockerfile.faiss-builder}) and binds the four entry points we
 * need: open an index file, set IVF nprobe, search top-k, free.
 *
 * <p>Native symbols resolve lazily on first {@link #load(Path, int)} call so this
 * class can be loaded on hosts that don't ship libfaiss_c (e.g. dev macOS).
 * Thread-safe for {@link #search} when each caller uses its own {@link Scratch}.
 */
public final class FaissIndex implements AutoCloseable {

    /** FAISS IOFlags::IO_FLAG_MMAP — mmap the index file instead of slurping into RAM. */
    private static final int IO_FLAG_MMAP = 0x4;

    private final MemorySegment idxPtr;

    private FaissIndex(MemorySegment idxPtr) {
        this.idxPtr = idxPtr;
    }

    /**
     * Load an IVF index from disk (mmap) and configure nprobe.
     *
     * @param file    path to a FAISS-serialized index (built with {@code IVF*,SQ8} or similar)
     * @param nprobe  number of IVF cells to probe per query (4..64 typical)
     */
    public static FaissIndex load(Path file, int nprobe) throws IOException {
        Native n = Native.get();
        try (Arena tmp = Arena.ofConfined()) {
            MemorySegment outPtr = tmp.allocate(ADDRESS);
            MemorySegment pathStr = tmp.allocateFrom(file.toString());
            int rc = (int) n.readIndex.invokeExact(pathStr, IO_FLAG_MMAP, outPtr);
            if (rc != 0) {
                throw new IOException("faiss_read_index_fname rc=" + rc + " " + lastError(n));
            }
            MemorySegment idx = outPtr.get(ADDRESS, 0).reinterpret(Long.MAX_VALUE);

            // Cast to IndexIVF to set nprobe. cast() returns NULL for non-IVF indexes.
            MemorySegment ivf = (MemorySegment) n.castIvf.invokeExact(idx);
            if (ivf.address() == 0) {
                n.free.invokeExact(idx);
                throw new IOException("loaded index is not an IndexIVF (need IVF* factory string)");
            }
            ivf = ivf.reinterpret(Long.MAX_VALUE);
            n.setNprobe.invokeExact(ivf, (long) nprobe);
            return new FaissIndex(idx);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("FAISS load failed", t);
        }
    }

    /**
     * Search top-k for one query. Results land in {@code scratch.distances} and
     * {@code scratch.labels} (a FAISS ordinal that indexes the caller's labels array).
     */
    public void search(float[] query, int k, Scratch scratch) {
        Native n = Native.get();
        try {
            MemorySegment.copy(query, 0, scratch.querySeg, JAVA_FLOAT, 0, query.length);
            int rc = (int) n.search.invokeExact(
                    idxPtr,
                    1L,                  // n queries
                    scratch.querySeg,
                    (long) k,            // top-k
                    scratch.distSeg,
                    scratch.labelSeg);
            if (rc != 0) {
                throw new RuntimeException("faiss_Index_search rc=" + rc + " " + lastError(n));
            }
            MemorySegment.copy(scratch.distSeg, JAVA_FLOAT, 0, scratch.distances, 0, k);
            MemorySegment.copy(scratch.labelSeg, JAVA_LONG, 0, scratch.labels, 0, k);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("FAISS search failed", t);
        }
    }

    @Override
    public void close() {
        try {
            Native.get().free.invokeExact(idxPtr);
        } catch (Throwable t) {
            // best effort — process is shutting down
        }
    }

    private static String lastError(Native n) {
        try {
            MemorySegment p = (MemorySegment) n.lastError.invokeExact();
            if (p.address() == 0) return "(no error string)";
            return p.reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            return "(failed to read last error: " + t.getMessage() + ")";
        }
    }

    /**
     * Off-heap scratch buffers reused across queries on the same request thread.
     * One per concurrent request; FraudHandler pools them.
     */
    public static final class Scratch implements AutoCloseable {
        public static final int DIMS = 14;
        public static final int K = 5;

        private final Arena arena = Arena.ofShared();
        final MemorySegment querySeg;
        final MemorySegment distSeg;
        final MemorySegment labelSeg;
        public final float[] distances = new float[K];
        public final long[] labels = new long[K];

        public Scratch() {
            querySeg = arena.allocate((long) DIMS * Float.BYTES, 64);
            distSeg = arena.allocate((long) K * Float.BYTES, 64);
            labelSeg = arena.allocate((long) K * Long.BYTES, 64);
        }

        @Override
        public void close() {
            arena.close();
        }
    }

    /**
     * Lazy-initialized native handles. Loaded on first reference, so the
     * enclosing class can be loaded by the JVM on machines that don't ship
     * libfaiss_c (dev hosts).
     */
    private static final class Native {
        private static volatile Native instance;

        static Native get() {
            Native local = instance;
            if (local != null) return local;
            synchronized (Native.class) {
                if (instance == null) instance = new Native();
                return instance;
            }
        }

        final Linker linker = Linker.nativeLinker();
        /** Allow overriding via env (tests/dev). Default matches the Dockerfile install path. */
        final SymbolLookup lib = SymbolLookup.libraryLookup(
                java.nio.file.Path.of(System.getenv().getOrDefault(
                        "FAISS_C_LIB_PATH", "/usr/local/lib/libfaiss_c.so")),
                Arena.global());

        final MethodHandle readIndex = link("faiss_read_index_fname",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        final MethodHandle search = link("faiss_Index_search",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
        final MethodHandle castIvf = link("faiss_IndexIVF_cast",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        final MethodHandle setNprobe = link("faiss_IndexIVF_set_nprobe",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
        final MethodHandle lastError = link("faiss_get_last_error",
                FunctionDescriptor.of(ADDRESS));
        final MethodHandle free = link("faiss_Index_free",
                FunctionDescriptor.ofVoid(ADDRESS));

        private MethodHandle link(String name, FunctionDescriptor desc) {
            return linker.downcallHandle(
                    lib.find(name).orElseThrow(
                            () -> new UnsatisfiedLinkError("symbol not found in libfaiss_c: " + name)),
                    desc);
        }
    }
}
