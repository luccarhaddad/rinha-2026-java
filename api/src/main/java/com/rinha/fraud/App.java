package com.rinha.fraud;

import com.rinha.fraud.data.Dataset;
import com.rinha.fraud.data.MccRisk;
import com.rinha.fraud.faiss.FaissIndex;
import com.rinha.fraud.http.FaissFraudHandler;
import com.rinha.fraud.http.FraudHandler;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.Handler;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class App {
    private App() {}

    /** Test entry — keeps the legacy brute-force path for tests. */
    public static WebServer buildServer(int port, Dataset ds, MccRisk mcc) {
        FraudHandler handler = new FraudHandler(ds, mcc);
        return server(port, handler);
    }

    /** Production entry — FAISS-backed handler. */
    public static WebServer buildFaissServer(int port, FaissIndex faiss, byte[] labels, MccRisk mcc) {
        FaissFraudHandler handler = new FaissFraudHandler(faiss, labels, mcc);
        return server(port, handler);
    }

    private static WebServer server(int port, Handler scoreHandler) {
        return WebServer.builder()
                .port(port)
                .routing(r -> r
                        .post("/fraud-score", scoreHandler)
                        .get("/ready", (req, res) -> res.send("OK")))
                .build();
    }

    public static void main(String[] args) throws Exception {
        String kernel = System.getenv().getOrDefault("KERNEL", "faiss");
        MccRisk mcc = MccRisk.defaults();
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        if ("faiss".equalsIgnoreCase(kernel)) {
            mainFaiss(mcc, port);
        } else {
            mainBruteForce(mcc, port);
        }
    }

    private static void mainFaiss(MccRisk mcc, int port) throws Exception {
        Path faissPath = Path.of(System.getenv().getOrDefault("FAISS_INDEX", "/app/resources/data.faiss"));
        Path labelsPath = Path.of(System.getenv().getOrDefault("LABELS_BIN", "/app/resources/labels.bin"));
        int nprobe = Integer.parseInt(System.getenv().getOrDefault("FAISS_NPROBE", "16"));

        // Force the kernel to page in the entire 66 MB index BEFORE we accept traffic.
        // Otherwise the first 50+ live requests pay random page-fault latency that
        // inflates p99 and bottlenecks the ramp.
        long t0 = System.nanoTime();
        long preloadBytes = preloadFile(faissPath);
        System.out.printf("preloaded %.1f MB of %s in %.0fms%n",
                preloadBytes / 1e6, faissPath.getFileName(), (System.nanoTime() - t0) / 1e6);

        t0 = System.nanoTime();
        FaissIndex faiss = FaissIndex.load(faissPath, nprobe);
        byte[] labels = Files.readAllBytes(labelsPath);
        System.out.printf("loaded faiss + %d labels in %.0fms (nprobe=%d)%n",
                labels.length, (System.nanoTime() - t0) / 1e6, nprobe);

        warmupFaiss(faiss, labels, mcc);

        WebServer server = buildFaissServer(port, faiss, labels, mcc);
        server.start();
        System.out.println("Rinha fraud API (FAISS) listening on " + server.port() + " (n=" + labels.length + ")");
    }

    private static void mainBruteForce(MccRisk mcc, int port) throws Exception {
        Path bin = Path.of(System.getenv().getOrDefault("DATASET_BIN", "/app/resources/dataset.i8bin"));
        Path labels = Path.of(System.getenv().getOrDefault("LABELS_BIN", "/app/resources/labels.bin"));
        Dataset ds = Dataset.load(bin, labels);
        warmupBrute(ds, mcc);

        WebServer server = buildServer(port, ds, mcc);
        server.start();
        System.out.println("Rinha fraud API (brute-force) listening on " + server.port() + " (n=" + ds.n + ")");
    }

    /**
     * Synchronously touch every page of {@code file} so it lives in the kernel
     * page cache before any real query runs. Equivalent to madvise(MADV_WILLNEED)
     * but blocking — return only when every page is resident.
     *
     * <p>FAISS later mmap()s the same file and shares this cache — search calls
     * incur translation faults only, never disk I/O.
     */
    private static long preloadFile(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
             Arena arena = Arena.ofConfined()) {
            long size = ch.size();
            MemorySegment seg = ch.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);
            byte sink = 0;
            // 4 KiB step touches each page exactly once.
            for (long off = 0; off < size; off += 4096) {
                sink ^= seg.get(ValueLayout.JAVA_BYTE, off);
            }
            // Defeat DCE so the loop actually runs.
            if (sink == Byte.MIN_VALUE && size < 0) {
                throw new IllegalStateException("unreachable");
            }
            return size;
        }
    }

    /**
     * Warmup with **varied** payloads. Each payload routes to a different IVF
     * cell (different vector geometry) so FAISS's first real queries find both
     * the JIT-compiled hot path AND most of the working-set cells already paged.
     */
    private static void warmupFaiss(FaissIndex faiss, byte[] labels, MccRisk mcc) {
        FaissFraudHandler h = new FaissFraudHandler(faiss, labels, mcc);
        byte[][] payloads = WARMUP_PAYLOADS;
        int iters = Integer.parseInt(System.getenv().getOrDefault("WARMUP_ITERS", "400"));
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            byte[] body = payloads[i % payloads.length];
            h.score(body, body.length);
        }
        System.out.printf("warmup %d iters across %d distinct payloads in %.1fms%n",
                iters, payloads.length, (System.nanoTime() - t0) / 1e6);
    }

    /** Legacy warmup for brute-force path (kept for tests/dev). */
    private static void warmupBrute(Dataset ds, MccRisk mcc) {
        FraudHandler h = new FraudHandler(ds, mcc);
        byte[] body = WARMUP_PAYLOADS[0];
        int iters = Integer.parseInt(System.getenv().getOrDefault("WARMUP_ITERS", "300"));
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) h.score(body, body.length);
        System.out.printf("warmup %d iters in %.1fms%n", iters, (System.nanoTime() - t0) / 1e6);
    }

    /**
     * 20 hand-crafted payloads spanning the realistic distribution of the dataset:
     * low/mid/high amounts, varied MCCs (different mcc_risk values), with and
     * without last_transaction, varied km_from_home, varied online/cardPresent,
     * and known/unknown merchant. Mapped through Vectorizer, these route to
     * many distinct IVF cells.
     */
    private static final byte[][] WARMUP_PAYLOADS = buildWarmupPayloads();

    private static byte[][] buildWarmupPayloads() {
        String[] raw = new String[]{
            // 0: low-value, in-person, known, daytime, with-history
            "{\"id\":\"tx-w0\",\"transaction\":{\"amount\":41.12,\"installments\":1,\"requested_at\":\"2026-03-11T13:30:00Z\"},\"customer\":{\"avg_amount\":82.24,\"tx_count_24h\":2,\"known_merchants\":[\"MERC-001\"]},\"merchant\":{\"id\":\"MERC-001\",\"mcc\":\"5411\",\"avg_amount\":60.25},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":2.5},\"last_transaction\":{\"timestamp\":\"2026-03-11T11:15:00Z\",\"km_from_current\":1.2}}",
            // 1: legit-looking, no history (null last_tx)
            "{\"id\":\"tx-w1\",\"transaction\":{\"amount\":120.50,\"installments\":2,\"requested_at\":\"2026-03-11T18:45:00Z\"},\"customer\":{\"avg_amount\":150.0,\"tx_count_24h\":3,\"known_merchants\":[\"MERC-002\",\"MERC-003\"]},\"merchant\":{\"id\":\"MERC-002\",\"mcc\":\"5812\",\"avg_amount\":95.0},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":4.0},\"last_transaction\":null}",
            // 2: high-value, online, unknown merchant, fraud-looking
            "{\"id\":\"tx-w2\",\"transaction\":{\"amount\":9505.97,\"installments\":10,\"requested_at\":\"2026-03-14T05:15:12Z\"},\"customer\":{\"avg_amount\":81.28,\"tx_count_24h\":20,\"known_merchants\":[\"MERC-008\"]},\"merchant\":{\"id\":\"MERC-068\",\"mcc\":\"7802\",\"avg_amount\":54.86},\"terminal\":{\"is_online\":true,\"card_present\":false,\"km_from_home\":952.27},\"last_transaction\":null}",
            // 3: medium amount, distant geography, weekend
            "{\"id\":\"tx-w3\",\"transaction\":{\"amount\":380.00,\"installments\":3,\"requested_at\":\"2026-03-15T22:10:00Z\"},\"customer\":{\"avg_amount\":200.0,\"tx_count_24h\":5,\"known_merchants\":[\"MERC-010\",\"MERC-011\"]},\"merchant\":{\"id\":\"MERC-077\",\"mcc\":\"5944\",\"avg_amount\":250.0},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":250.0},\"last_transaction\":{\"timestamp\":\"2026-03-15T21:45:00Z\",\"km_from_current\":80.0}}",
            // 4: gas station, mid-tier MCC
            "{\"id\":\"tx-w4\",\"transaction\":{\"amount\":85.0,\"installments\":1,\"requested_at\":\"2026-03-12T07:20:00Z\"},\"customer\":{\"avg_amount\":75.0,\"tx_count_24h\":1,\"known_merchants\":[\"MERC-100\"]},\"merchant\":{\"id\":\"MERC-100\",\"mcc\":\"5541\",\"avg_amount\":80.0},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":12.0},\"last_transaction\":{\"timestamp\":\"2026-03-11T19:00:00Z\",\"km_from_current\":15.0}}",
            // 5: ATM withdrawal, high MCC risk
            "{\"id\":\"tx-w5\",\"transaction\":{\"amount\":500.0,\"installments\":1,\"requested_at\":\"2026-03-13T02:15:00Z\"},\"customer\":{\"avg_amount\":300.0,\"tx_count_24h\":4,\"known_merchants\":[\"MERC-050\"]},\"merchant\":{\"id\":\"MERC-200\",\"mcc\":\"7995\",\"avg_amount\":400.0},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":35.0},\"last_transaction\":null}",
            // 6: e-commerce, small, online card-not-present
            "{\"id\":\"tx-w6\",\"transaction\":{\"amount\":29.90,\"installments\":1,\"requested_at\":\"2026-03-11T15:00:00Z\"},\"customer\":{\"avg_amount\":50.0,\"tx_count_24h\":2,\"known_merchants\":[\"MERC-300\"]},\"merchant\":{\"id\":\"MERC-300\",\"mcc\":\"5999\",\"avg_amount\":40.0},\"terminal\":{\"is_online\":true,\"card_present\":false,\"km_from_home\":0.0},\"last_transaction\":{\"timestamp\":\"2026-03-11T14:30:00Z\",\"km_from_current\":0.0}}",
            // 7: international-ish, large, very far
            "{\"id\":\"tx-w7\",\"transaction\":{\"amount\":1500.0,\"installments\":1,\"requested_at\":\"2026-03-14T03:00:00Z\"},\"customer\":{\"avg_amount\":400.0,\"tx_count_24h\":1,\"known_merchants\":[\"MERC-001\"]},\"merchant\":{\"id\":\"MERC-999\",\"mcc\":\"4511\",\"avg_amount\":1200.0},\"terminal\":{\"is_online\":true,\"card_present\":false,\"km_from_home\":8000.0},\"last_transaction\":null}",
            // 8: high-value installment plan, known
            "{\"id\":\"tx-w8\",\"transaction\":{\"amount\":2400.0,\"installments\":12,\"requested_at\":\"2026-03-11T16:30:00Z\"},\"customer\":{\"avg_amount\":300.0,\"tx_count_24h\":1,\"known_merchants\":[\"MERC-040\"]},\"merchant\":{\"id\":\"MERC-040\",\"mcc\":\"5311\",\"avg_amount\":1800.0},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":15.0},\"last_transaction\":{\"timestamp\":\"2026-03-10T20:00:00Z\",\"km_from_current\":10.0}}",
            // 9: drugstore, recurring buyer
            "{\"id\":\"tx-w9\",\"transaction\":{\"amount\":67.30,\"installments\":1,\"requested_at\":\"2026-03-11T11:00:00Z\"},\"customer\":{\"avg_amount\":70.0,\"tx_count_24h\":1,\"known_merchants\":[\"MERC-022\"]},\"merchant\":{\"id\":\"MERC-022\",\"mcc\":\"5912\",\"avg_amount\":65.0},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":3.5},\"last_transaction\":{\"timestamp\":\"2026-03-10T11:00:00Z\",\"km_from_current\":0.0}}",
            // 10: very-high amount, suspicious
            "{\"id\":\"tx-w10\",\"transaction\":{\"amount\":7800.0,\"installments\":1,\"requested_at\":\"2026-03-14T01:00:00Z\"},\"customer\":{\"avg_amount\":150.0,\"tx_count_24h\":15,\"known_merchants\":[\"MERC-001\"]},\"merchant\":{\"id\":\"MERC-555\",\"mcc\":\"7801\",\"avg_amount\":50.0},\"terminal\":{\"is_online\":true,\"card_present\":false,\"km_from_home\":500.0},\"last_transaction\":{\"timestamp\":\"2026-03-13T23:00:00Z\",\"km_from_current\":480.0}}",
            // 11: legit lunch
            "{\"id\":\"tx-w11\",\"transaction\":{\"amount\":52.0,\"installments\":1,\"requested_at\":\"2026-03-11T12:30:00Z\"},\"customer\":{\"avg_amount\":60.0,\"tx_count_24h\":1,\"known_merchants\":[\"MERC-019\",\"MERC-020\"]},\"merchant\":{\"id\":\"MERC-019\",\"mcc\":\"5812\",\"avg_amount\":55.0},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":1.5},\"last_transaction\":{\"timestamp\":\"2026-03-11T08:30:00Z\",\"km_from_current\":2.0}}",
            // 12: card-present but high amount + late hour
            "{\"id\":\"tx-w12\",\"transaction\":{\"amount\":3200.0,\"installments\":6,\"requested_at\":\"2026-03-12T23:50:00Z\"},\"customer\":{\"avg_amount\":250.0,\"tx_count_24h\":3,\"known_merchants\":[\"MERC-033\"]},\"merchant\":{\"id\":\"MERC-033\",\"mcc\":\"5944\",\"avg_amount\":2500.0},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":18.0},\"last_transaction\":{\"timestamp\":\"2026-03-12T20:00:00Z\",\"km_from_current\":12.0}}",
            // 13: subscription-like small recurring
            "{\"id\":\"tx-w13\",\"transaction\":{\"amount\":19.90,\"installments\":1,\"requested_at\":\"2026-03-15T09:00:00Z\"},\"customer\":{\"avg_amount\":80.0,\"tx_count_24h\":2,\"known_merchants\":[\"MERC-555\",\"MERC-666\"]},\"merchant\":{\"id\":\"MERC-555\",\"mcc\":\"5999\",\"avg_amount\":19.90},\"terminal\":{\"is_online\":true,\"card_present\":false,\"km_from_home\":0.0},\"last_transaction\":{\"timestamp\":\"2026-02-15T09:00:00Z\",\"km_from_current\":0.0}}",
            // 14: high-frequency suspicious (many txns)
            "{\"id\":\"tx-w14\",\"transaction\":{\"amount\":190.0,\"installments\":1,\"requested_at\":\"2026-03-11T20:00:00Z\"},\"customer\":{\"avg_amount\":100.0,\"tx_count_24h\":18,\"known_merchants\":[\"MERC-080\"]},\"merchant\":{\"id\":\"MERC-444\",\"mcc\":\"5999\",\"avg_amount\":150.0},\"terminal\":{\"is_online\":true,\"card_present\":false,\"km_from_home\":250.0},\"last_transaction\":{\"timestamp\":\"2026-03-11T19:55:00Z\",\"km_from_current\":300.0}}",
            // 15: utility bill
            "{\"id\":\"tx-w15\",\"transaction\":{\"amount\":210.0,\"installments\":1,\"requested_at\":\"2026-03-15T11:30:00Z\"},\"customer\":{\"avg_amount\":180.0,\"tx_count_24h\":1,\"known_merchants\":[\"MERC-009\"]},\"merchant\":{\"id\":\"MERC-009\",\"mcc\":\"5311\",\"avg_amount\":200.0},\"terminal\":{\"is_online\":true,\"card_present\":false,\"km_from_home\":0.0},\"last_transaction\":{\"timestamp\":\"2026-02-15T11:30:00Z\",\"km_from_current\":0.0}}",
            // 16: foreign-looking, large, weekend night
            "{\"id\":\"tx-w16\",\"transaction\":{\"amount\":4500.0,\"installments\":4,\"requested_at\":\"2026-03-15T03:30:00Z\"},\"customer\":{\"avg_amount\":120.0,\"tx_count_24h\":1,\"known_merchants\":[\"MERC-002\"]},\"merchant\":{\"id\":\"MERC-888\",\"mcc\":\"7802\",\"avg_amount\":3000.0},\"terminal\":{\"is_online\":true,\"card_present\":false,\"km_from_home\":1200.0},\"last_transaction\":null}",
            // 17: very small in-person, very normal
            "{\"id\":\"tx-w17\",\"transaction\":{\"amount\":9.50,\"installments\":1,\"requested_at\":\"2026-03-13T16:00:00Z\"},\"customer\":{\"avg_amount\":45.0,\"tx_count_24h\":4,\"known_merchants\":[\"MERC-100\",\"MERC-200\"]},\"merchant\":{\"id\":\"MERC-100\",\"mcc\":\"5411\",\"avg_amount\":10.0},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":0.8},\"last_transaction\":{\"timestamp\":\"2026-03-13T15:50:00Z\",\"km_from_current\":0.1}}",
            // 18: travel-related (transport mcc 4511)
            "{\"id\":\"tx-w18\",\"transaction\":{\"amount\":680.0,\"installments\":2,\"requested_at\":\"2026-03-14T15:00:00Z\"},\"customer\":{\"avg_amount\":400.0,\"tx_count_24h\":2,\"known_merchants\":[\"MERC-040\"]},\"merchant\":{\"id\":\"MERC-040\",\"mcc\":\"4511\",\"avg_amount\":650.0},\"terminal\":{\"is_online\":true,\"card_present\":false,\"km_from_home\":50.0},\"last_transaction\":{\"timestamp\":\"2026-03-14T12:00:00Z\",\"km_from_current\":30.0}}",
            // 19: card-present rapid-fire (suspicious frequency)
            "{\"id\":\"tx-w19\",\"transaction\":{\"amount\":650.0,\"installments\":1,\"requested_at\":\"2026-03-11T17:35:00Z\"},\"customer\":{\"avg_amount\":80.0,\"tx_count_24h\":12,\"known_merchants\":[\"MERC-080\"]},\"merchant\":{\"id\":\"MERC-088\",\"mcc\":\"5311\",\"avg_amount\":400.0},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":40.0},\"last_transaction\":{\"timestamp\":\"2026-03-11T17:30:00Z\",\"km_from_current\":2.0}}",
        };
        byte[][] out = new byte[raw.length][];
        for (int i = 0; i < raw.length; i++) out[i] = raw[i].getBytes(StandardCharsets.US_ASCII);
        return out;
    }
}
