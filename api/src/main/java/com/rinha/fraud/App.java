package com.rinha.fraud;

import com.rinha.fraud.data.Dataset;
import com.rinha.fraud.data.MccRisk;
import com.rinha.fraud.faiss.FaissIndex;
import com.rinha.fraud.http.FaissFraudHandler;
import com.rinha.fraud.http.FraudHandler;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.Router;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CountDownLatch;

public final class App {
    private App() {}

    public static void main(String[] args) throws Exception {
        String kernel = System.getenv().getOrDefault("KERNEL", "faiss");
        MccRisk mcc = MccRisk.defaults();

        if ("faiss".equalsIgnoreCase(kernel)) {
            startFaissServer(mcc);
        } else {
            startBruteForceServer(mcc);
        }
    }

    // ---------- production path: Vert.x + FAISS, listening on UDS or TCP ----------

    private static void startFaissServer(MccRisk mcc) throws Exception {
        Path faissPath = Path.of(System.getenv().getOrDefault("FAISS_INDEX", "/app/resources/data.faiss"));
        Path labelsPath = Path.of(System.getenv().getOrDefault("LABELS_BIN", "/app/resources/labels.bin"));
        int nprobe = Integer.parseInt(System.getenv().getOrDefault("FAISS_NPROBE", "8"));

        // Page-in the entire index file before serving — kernel cache the whole 66 MB.
        long t0 = System.nanoTime();
        long preloaded = preloadFile(faissPath);
        System.out.printf("preloaded %.1f MB of %s in %.0fms%n",
                preloaded / 1e6, faissPath.getFileName(), (System.nanoTime() - t0) / 1e6);

        t0 = System.nanoTime();
        FaissIndex faiss = FaissIndex.load(faissPath, nprobe);
        byte[] labels = Files.readAllBytes(labelsPath);
        System.out.printf("loaded faiss + %d labels in %.0fms (nprobe=%d)%n",
                labels.length, (System.nanoTime() - t0) / 1e6, nprobe);

        FaissFraudHandler handler = new FaissFraudHandler(faiss, labels, mcc);
        warmupFaiss(handler);

        // Training mode: exit cleanly so -XX:AOTCacheOutput can snapshot the cache.
        // Used only by the aot-train stage of the Dockerfile.
        if ("1".equals(System.getenv("TRAINING"))) {
            System.out.println("TRAINING=1: warmup done, exiting for AOT cache snapshot.");
            faiss.close();
            return;
        }

        listen(handler::score, "FAISS");
    }

    private static void startBruteForceServer(MccRisk mcc) throws Exception {
        Path bin = Path.of(System.getenv().getOrDefault("DATASET_BIN", "/app/resources/dataset.i8bin"));
        Path labels = Path.of(System.getenv().getOrDefault("LABELS_BIN", "/app/resources/labels.bin"));
        Dataset ds = Dataset.load(bin, labels);

        FraudHandler handler = new FraudHandler(ds, mcc);
        warmupBrute(handler);

        listen(handler::score, "brute-force");
    }

    // ---------- HTTP layer ----------

    @FunctionalInterface
    interface ScoreFn { byte[] score(byte[] body, int len); }

    private static void listen(ScoreFn score, String tag) throws Exception {
        // Event loop count = effective CPU count. With cgroup 0.45 CPU, JVM sees ~1 via
        // ActiveProcessorCount; we use 2 to keep one loop free for connection accept
        // while another handles a request. Plus this is overrideable via env.
        int loops = Integer.parseInt(System.getenv().getOrDefault("VERTX_EVENT_LOOPS", "2"));

        // Native epoll transport is required by Vert.x 4.5 for AF_UNIX sockets.
        // Falls back to NIO automatically if epoll isn't available on the platform.
        VertxOptions vopts = new VertxOptions()
                .setEventLoopPoolSize(loops)
                .setPreferNativeTransport(true)
                .setWorkerPoolSize(1)             // unused (CPU work runs on event loop)
                .setInternalBlockingPoolSize(1)
                // Push the blocked-thread watchdog window very high so it never logs
                // about our event-loop CPU work, but not Long.MAX_VALUE (overflows in
                // Vert.x's Timer.schedule). 1h = effectively off for our usage.
                .setBlockedThreadCheckInterval(3_600_000L)
                .setWarningExceptionTime(3_600_000L * 1_000_000L); // ns
        Vertx vertx = Vertx.vertx(vopts);

        HttpServerOptions httpOpts = new HttpServerOptions()
                .setTcpNoDelay(true)
                .setTcpKeepAlive(false)
                .setReusePort(false)
                .setReceiveBufferSize(4096)
                .setSendBufferSize(4096)
                .setAcceptBacklog(1024)
                .setLogActivity(false);

        HttpServer server = vertx.createHttpServer(httpOpts);
        Router router = Router.router(vertx);

        router.get("/ready").handler(ctx -> ctx.response().end(Buffer.buffer(READY_BYTES)));

        router.post("/fraud-score").handler(ctx -> {
            ctx.request().body().onSuccess(body -> {
                byte[] arr = body.getBytes();
                byte[] resp = score.score(arr, arr.length);
                ctx.response()
                   .putHeader(io.vertx.core.http.HttpHeaders.CONTENT_TYPE, "application/json")
                   .end(Buffer.buffer(resp));
            }).onFailure(t -> ctx.response().setStatusCode(400).end());
        });

        // Address: Unix socket if SOCK_PATH set, else TCP port.
        String sockPath = System.getenv("SOCK_PATH");
        SocketAddress addr;
        if (sockPath != null && !sockPath.isBlank()) {
            // Remove stale socket file from prior run
            Files.deleteIfExists(Path.of(sockPath));
            addr = SocketAddress.domainSocketAddress(sockPath);
        } else {
            int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
            addr = SocketAddress.inetSocketAddress(port, "0.0.0.0");
        }

        CountDownLatch latch = new CountDownLatch(1);
        long startedAt = System.nanoTime();
        server.requestHandler(router).listen(addr).onComplete(ar -> {
            if (ar.succeeded()) {
                // chmod the socket so nginx (potentially different container, same volume) can read+write
                if (sockPath != null && !sockPath.isBlank()) {
                    try {
                        Files.setPosixFilePermissions(Path.of(sockPath),
                                PosixFilePermissions.fromString("rw-rw-rw-"));
                    } catch (IOException e) {
                        System.err.println("WARN: chmod " + sockPath + ": " + e);
                    }
                }
                System.out.printf("Rinha fraud API (Vert.x %s) listening on %s — start in %.0fms%n",
                        tag, addr, (System.nanoTime() - startedAt) / 1e6);
            } else {
                System.err.println("Vert.x listen() failed: " + ar.cause());
                System.exit(1);
            }
            latch.countDown();
        });
        latch.await();
    }

    // ---------- preload + warmup ----------

    /** Page-in every page of {@code file} into kernel cache before serving traffic. */
    private static long preloadFile(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
             Arena arena = Arena.ofConfined()) {
            long size = ch.size();
            MemorySegment seg = ch.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);
            byte sink = 0;
            for (long off = 0; off < size; off += 4096) {
                sink ^= seg.get(ValueLayout.JAVA_BYTE, off);
            }
            if (sink == Byte.MIN_VALUE && size < 0) throw new IllegalStateException();
            return size;
        }
    }

    private static void warmupFaiss(FaissFraudHandler h) {
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

    private static void warmupBrute(FraudHandler h) {
        byte[] body = WARMUP_PAYLOADS[0];
        int iters = Integer.parseInt(System.getenv().getOrDefault("WARMUP_ITERS", "300"));
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) h.score(body, body.length);
        System.out.printf("warmup %d iters in %.1fms%n", iters, (System.nanoTime() - t0) / 1e6);
    }

    private static final byte[] READY_BYTES = "OK".getBytes(StandardCharsets.US_ASCII);

    private static final byte[][] WARMUP_PAYLOADS = buildWarmupPayloads();

    private static byte[][] buildWarmupPayloads() {
        String[] raw = new String[]{
            "{\"id\":\"tx-w0\",\"transaction\":{\"amount\":41.12,\"installments\":1,\"requested_at\":\"2026-03-11T13:30:00Z\"},\"customer\":{\"avg_amount\":82.24,\"tx_count_24h\":2,\"known_merchants\":[\"MERC-001\"]},\"merchant\":{\"id\":\"MERC-001\",\"mcc\":\"5411\",\"avg_amount\":60.25},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":2.5},\"last_transaction\":{\"timestamp\":\"2026-03-11T11:15:00Z\",\"km_from_current\":1.2}}",
            "{\"id\":\"tx-w1\",\"transaction\":{\"amount\":120.50,\"installments\":2,\"requested_at\":\"2026-03-11T18:45:00Z\"},\"customer\":{\"avg_amount\":150.0,\"tx_count_24h\":3,\"known_merchants\":[\"MERC-002\",\"MERC-003\"]},\"merchant\":{\"id\":\"MERC-002\",\"mcc\":\"5812\",\"avg_amount\":95.0},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":4.0},\"last_transaction\":null}",
            "{\"id\":\"tx-w2\",\"transaction\":{\"amount\":9505.97,\"installments\":10,\"requested_at\":\"2026-03-14T05:15:12Z\"},\"customer\":{\"avg_amount\":81.28,\"tx_count_24h\":20,\"known_merchants\":[\"MERC-008\"]},\"merchant\":{\"id\":\"MERC-068\",\"mcc\":\"7802\",\"avg_amount\":54.86},\"terminal\":{\"is_online\":true,\"card_present\":false,\"km_from_home\":952.27},\"last_transaction\":null}",
            "{\"id\":\"tx-w3\",\"transaction\":{\"amount\":380.00,\"installments\":3,\"requested_at\":\"2026-03-15T22:10:00Z\"},\"customer\":{\"avg_amount\":200.0,\"tx_count_24h\":5,\"known_merchants\":[\"MERC-010\",\"MERC-011\"]},\"merchant\":{\"id\":\"MERC-077\",\"mcc\":\"5944\",\"avg_amount\":250.0},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":250.0},\"last_transaction\":{\"timestamp\":\"2026-03-15T21:45:00Z\",\"km_from_current\":80.0}}",
            "{\"id\":\"tx-w4\",\"transaction\":{\"amount\":85.0,\"installments\":1,\"requested_at\":\"2026-03-12T07:20:00Z\"},\"customer\":{\"avg_amount\":75.0,\"tx_count_24h\":1,\"known_merchants\":[\"MERC-100\"]},\"merchant\":{\"id\":\"MERC-100\",\"mcc\":\"5541\",\"avg_amount\":80.0},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":12.0},\"last_transaction\":{\"timestamp\":\"2026-03-11T19:00:00Z\",\"km_from_current\":15.0}}",
            "{\"id\":\"tx-w5\",\"transaction\":{\"amount\":500.0,\"installments\":1,\"requested_at\":\"2026-03-13T02:15:00Z\"},\"customer\":{\"avg_amount\":300.0,\"tx_count_24h\":4,\"known_merchants\":[\"MERC-050\"]},\"merchant\":{\"id\":\"MERC-200\",\"mcc\":\"7995\",\"avg_amount\":400.0},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":35.0},\"last_transaction\":null}",
            "{\"id\":\"tx-w6\",\"transaction\":{\"amount\":29.90,\"installments\":1,\"requested_at\":\"2026-03-11T15:00:00Z\"},\"customer\":{\"avg_amount\":50.0,\"tx_count_24h\":2,\"known_merchants\":[\"MERC-300\"]},\"merchant\":{\"id\":\"MERC-300\",\"mcc\":\"5999\",\"avg_amount\":40.0},\"terminal\":{\"is_online\":true,\"card_present\":false,\"km_from_home\":0.0},\"last_transaction\":{\"timestamp\":\"2026-03-11T14:30:00Z\",\"km_from_current\":0.0}}",
            "{\"id\":\"tx-w7\",\"transaction\":{\"amount\":1500.0,\"installments\":1,\"requested_at\":\"2026-03-14T03:00:00Z\"},\"customer\":{\"avg_amount\":400.0,\"tx_count_24h\":1,\"known_merchants\":[\"MERC-001\"]},\"merchant\":{\"id\":\"MERC-999\",\"mcc\":\"4511\",\"avg_amount\":1200.0},\"terminal\":{\"is_online\":true,\"card_present\":false,\"km_from_home\":8000.0},\"last_transaction\":null}",
            "{\"id\":\"tx-w8\",\"transaction\":{\"amount\":2400.0,\"installments\":12,\"requested_at\":\"2026-03-11T16:30:00Z\"},\"customer\":{\"avg_amount\":300.0,\"tx_count_24h\":1,\"known_merchants\":[\"MERC-040\"]},\"merchant\":{\"id\":\"MERC-040\",\"mcc\":\"5311\",\"avg_amount\":1800.0},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":15.0},\"last_transaction\":{\"timestamp\":\"2026-03-10T20:00:00Z\",\"km_from_current\":10.0}}",
            "{\"id\":\"tx-w9\",\"transaction\":{\"amount\":67.30,\"installments\":1,\"requested_at\":\"2026-03-11T11:00:00Z\"},\"customer\":{\"avg_amount\":70.0,\"tx_count_24h\":1,\"known_merchants\":[\"MERC-022\"]},\"merchant\":{\"id\":\"MERC-022\",\"mcc\":\"5912\",\"avg_amount\":65.0},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":3.5},\"last_transaction\":{\"timestamp\":\"2026-03-10T11:00:00Z\",\"km_from_current\":0.0}}",
            "{\"id\":\"tx-w10\",\"transaction\":{\"amount\":7800.0,\"installments\":1,\"requested_at\":\"2026-03-14T01:00:00Z\"},\"customer\":{\"avg_amount\":150.0,\"tx_count_24h\":15,\"known_merchants\":[\"MERC-001\"]},\"merchant\":{\"id\":\"MERC-555\",\"mcc\":\"7801\",\"avg_amount\":50.0},\"terminal\":{\"is_online\":true,\"card_present\":false,\"km_from_home\":500.0},\"last_transaction\":{\"timestamp\":\"2026-03-13T23:00:00Z\",\"km_from_current\":480.0}}",
            "{\"id\":\"tx-w11\",\"transaction\":{\"amount\":52.0,\"installments\":1,\"requested_at\":\"2026-03-11T12:30:00Z\"},\"customer\":{\"avg_amount\":60.0,\"tx_count_24h\":1,\"known_merchants\":[\"MERC-019\",\"MERC-020\"]},\"merchant\":{\"id\":\"MERC-019\",\"mcc\":\"5812\",\"avg_amount\":55.0},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":1.5},\"last_transaction\":{\"timestamp\":\"2026-03-11T08:30:00Z\",\"km_from_current\":2.0}}",
            "{\"id\":\"tx-w12\",\"transaction\":{\"amount\":3200.0,\"installments\":6,\"requested_at\":\"2026-03-12T23:50:00Z\"},\"customer\":{\"avg_amount\":250.0,\"tx_count_24h\":3,\"known_merchants\":[\"MERC-033\"]},\"merchant\":{\"id\":\"MERC-033\",\"mcc\":\"5944\",\"avg_amount\":2500.0},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":18.0},\"last_transaction\":{\"timestamp\":\"2026-03-12T20:00:00Z\",\"km_from_current\":12.0}}",
            "{\"id\":\"tx-w13\",\"transaction\":{\"amount\":19.90,\"installments\":1,\"requested_at\":\"2026-03-15T09:00:00Z\"},\"customer\":{\"avg_amount\":80.0,\"tx_count_24h\":2,\"known_merchants\":[\"MERC-555\",\"MERC-666\"]},\"merchant\":{\"id\":\"MERC-555\",\"mcc\":\"5999\",\"avg_amount\":19.90},\"terminal\":{\"is_online\":true,\"card_present\":false,\"km_from_home\":0.0},\"last_transaction\":{\"timestamp\":\"2026-02-15T09:00:00Z\",\"km_from_current\":0.0}}",
            "{\"id\":\"tx-w14\",\"transaction\":{\"amount\":190.0,\"installments\":1,\"requested_at\":\"2026-03-11T20:00:00Z\"},\"customer\":{\"avg_amount\":100.0,\"tx_count_24h\":18,\"known_merchants\":[\"MERC-080\"]},\"merchant\":{\"id\":\"MERC-444\",\"mcc\":\"5999\",\"avg_amount\":150.0},\"terminal\":{\"is_online\":true,\"card_present\":false,\"km_from_home\":250.0},\"last_transaction\":{\"timestamp\":\"2026-03-11T19:55:00Z\",\"km_from_current\":300.0}}",
            "{\"id\":\"tx-w15\",\"transaction\":{\"amount\":210.0,\"installments\":1,\"requested_at\":\"2026-03-15T11:30:00Z\"},\"customer\":{\"avg_amount\":180.0,\"tx_count_24h\":1,\"known_merchants\":[\"MERC-009\"]},\"merchant\":{\"id\":\"MERC-009\",\"mcc\":\"5311\",\"avg_amount\":200.0},\"terminal\":{\"is_online\":true,\"card_present\":false,\"km_from_home\":0.0},\"last_transaction\":{\"timestamp\":\"2026-02-15T11:30:00Z\",\"km_from_current\":0.0}}",
            "{\"id\":\"tx-w16\",\"transaction\":{\"amount\":4500.0,\"installments\":4,\"requested_at\":\"2026-03-15T03:30:00Z\"},\"customer\":{\"avg_amount\":120.0,\"tx_count_24h\":1,\"known_merchants\":[\"MERC-002\"]},\"merchant\":{\"id\":\"MERC-888\",\"mcc\":\"7802\",\"avg_amount\":3000.0},\"terminal\":{\"is_online\":true,\"card_present\":false,\"km_from_home\":1200.0},\"last_transaction\":null}",
            "{\"id\":\"tx-w17\",\"transaction\":{\"amount\":9.50,\"installments\":1,\"requested_at\":\"2026-03-13T16:00:00Z\"},\"customer\":{\"avg_amount\":45.0,\"tx_count_24h\":4,\"known_merchants\":[\"MERC-100\",\"MERC-200\"]},\"merchant\":{\"id\":\"MERC-100\",\"mcc\":\"5411\",\"avg_amount\":10.0},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":0.8},\"last_transaction\":{\"timestamp\":\"2026-03-13T15:50:00Z\",\"km_from_current\":0.1}}",
            "{\"id\":\"tx-w18\",\"transaction\":{\"amount\":680.0,\"installments\":2,\"requested_at\":\"2026-03-14T15:00:00Z\"},\"customer\":{\"avg_amount\":400.0,\"tx_count_24h\":2,\"known_merchants\":[\"MERC-040\"]},\"merchant\":{\"id\":\"MERC-040\",\"mcc\":\"4511\",\"avg_amount\":650.0},\"terminal\":{\"is_online\":true,\"card_present\":false,\"km_from_home\":50.0},\"last_transaction\":{\"timestamp\":\"2026-03-14T12:00:00Z\",\"km_from_current\":30.0}}",
            "{\"id\":\"tx-w19\",\"transaction\":{\"amount\":650.0,\"installments\":1,\"requested_at\":\"2026-03-11T17:35:00Z\"},\"customer\":{\"avg_amount\":80.0,\"tx_count_24h\":12,\"known_merchants\":[\"MERC-080\"]},\"merchant\":{\"id\":\"MERC-088\",\"mcc\":\"5311\",\"avg_amount\":400.0},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":40.0},\"last_transaction\":{\"timestamp\":\"2026-03-11T17:30:00Z\",\"km_from_current\":2.0}}",
        };
        byte[][] out = new byte[raw.length][];
        for (int i = 0; i < raw.length; i++) out[i] = raw[i].getBytes(StandardCharsets.US_ASCII);
        return out;
    }
}
