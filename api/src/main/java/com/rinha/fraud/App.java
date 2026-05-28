package com.rinha.fraud;

import com.rinha.fraud.data.Dataset;
import com.rinha.fraud.data.MccRisk;
import com.rinha.fraud.http.FraudHandler;
import io.helidon.webserver.WebServer;

import java.nio.file.Path;

public final class App {
    private App() {}

    public static WebServer buildServer(int port, Dataset ds, MccRisk mcc) {
        FraudHandler handler = new FraudHandler(ds, mcc);
        return WebServer.builder()
                .port(port)
                .routing(r -> r
                        .post("/fraud-score", handler)
                        .get("/ready", (req, res) -> res.send("OK")))
                .build();
    }

    public static void main(String[] args) throws Exception {
        Path bin = Path.of(System.getenv().getOrDefault("DATASET_BIN", "/app/resources/dataset.i8bin"));
        Path labels = Path.of(System.getenv().getOrDefault("LABELS_BIN", "/app/resources/labels.bin"));
        Dataset ds = Dataset.load(bin, labels);
        MccRisk mcc = MccRisk.defaults();

        warmup(ds, mcc);

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        WebServer server = buildServer(port, ds, mcc);
        server.start();
        System.out.println("Rinha fraud API listening on " + server.port() + " (n=" + ds.n + ")");
    }

    /** Drive the SIMD kernel + JIT to peak before accepting traffic. */
    private static void warmup(Dataset ds, MccRisk mcc) {
        FraudHandler h = new FraudHandler(ds, mcc);
        byte[] body = ("{\"id\":\"tx-warm\",\"transaction\":{\"amount\":100.0,\"installments\":1,\"requested_at\":\"2026-03-11T18:45:53Z\"}," +
                "\"customer\":{\"avg_amount\":80.0,\"tx_count_24h\":2,\"known_merchants\":[\"MERC-001\"]}," +
                "\"merchant\":{\"id\":\"MERC-002\",\"mcc\":\"5411\",\"avg_amount\":60.0}," +
                "\"terminal\":{\"is_online\":true,\"card_present\":false,\"km_from_home\":10.0}," +
                "\"last_transaction\":{\"timestamp\":\"2026-03-11T14:58:35Z\",\"km_from_current\":18.0}}")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int iters = Integer.parseInt(System.getenv().getOrDefault("WARMUP_ITERS", "300"));
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) h.score(body, body.length);
        System.out.printf("warmup %d iters in %.1fms%n", iters, (System.nanoTime() - t0) / 1e6);
    }
}
