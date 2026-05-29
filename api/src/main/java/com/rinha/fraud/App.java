package com.rinha.fraud;

import com.rinha.fraud.data.Dataset;
import com.rinha.fraud.data.MccRisk;
import com.rinha.fraud.faiss.FaissIndex;
import com.rinha.fraud.http.FaissFraudHandler;
import com.rinha.fraud.http.FraudHandler;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.Handler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

        long t0 = System.nanoTime();
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
        warmup(ds, mcc);

        WebServer server = buildServer(port, ds, mcc);
        server.start();
        System.out.println("Rinha fraud API (brute-force) listening on " + server.port() + " (n=" + ds.n + ")");
    }

    private static final byte[] WARM_BODY = (
            "{\"id\":\"tx-warm\",\"transaction\":{\"amount\":100.0,\"installments\":1,\"requested_at\":\"2026-03-11T18:45:53Z\"}," +
            "\"customer\":{\"avg_amount\":80.0,\"tx_count_24h\":2,\"known_merchants\":[\"MERC-001\"]}," +
            "\"merchant\":{\"id\":\"MERC-002\",\"mcc\":\"5411\",\"avg_amount\":60.0}," +
            "\"terminal\":{\"is_online\":true,\"card_present\":false,\"km_from_home\":10.0}," +
            "\"last_transaction\":{\"timestamp\":\"2026-03-11T14:58:35Z\",\"km_from_current\":18.0}}")
            .getBytes(StandardCharsets.US_ASCII);

    private static void warmupFaiss(FaissIndex faiss, byte[] labels, MccRisk mcc) {
        FaissFraudHandler h = new FaissFraudHandler(faiss, labels, mcc);
        int iters = Integer.parseInt(System.getenv().getOrDefault("WARMUP_ITERS", "500"));
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) h.score(WARM_BODY, WARM_BODY.length);
        System.out.printf("warmup %d iters in %.1fms%n", iters, (System.nanoTime() - t0) / 1e6);
    }

    /** Legacy warmup for brute-force path (kept for tests/dev). */
    private static void warmup(Dataset ds, MccRisk mcc) {
        FraudHandler h = new FraudHandler(ds, mcc);
        int iters = Integer.parseInt(System.getenv().getOrDefault("WARMUP_ITERS", "300"));
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) h.score(WARM_BODY, WARM_BODY.length);
        System.out.printf("warmup %d iters in %.1fms%n", iters, (System.nanoTime() - t0) / 1e6);
    }
}
