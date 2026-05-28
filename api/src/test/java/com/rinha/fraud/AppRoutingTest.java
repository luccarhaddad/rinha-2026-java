package com.rinha.fraud;

import com.rinha.fraud.data.Dataset;
import com.rinha.fraud.data.MccRisk;
import com.rinha.fraud.preprocess.PreprocessDataset;
import io.helidon.webserver.WebServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class AppRoutingTest {
    @Test
    void readyAndFraudScore(@TempDir Path dir) throws Exception {
        String json = "[{\"vector\":[0,0,0,0,0,0,0,0,0,0,0,0,0,0],\"label\":\"legit\"}]";
        Path gz = dir.resolve("r.json.gz");
        try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(gz)), StandardCharsets.UTF_8)) { w.write(json); }
        Path bin = dir.resolve("d.i8bin"), labels = dir.resolve("l.bin");
        PreprocessDataset.run(gz, bin, labels);
        Dataset ds = Dataset.load(bin, labels);

        WebServer server = App.buildServer(0, ds, MccRisk.defaults());
        server.start();
        try {
            int port = server.port();
            HttpClient c = HttpClient.newHttpClient();
            HttpResponse<String> ready = c.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ready")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ready.statusCode());

            String payload = "{\"id\":\"tx-1\",\"transaction\":{\"amount\":41.12,\"installments\":2,\"requested_at\":\"2026-03-11T18:45:53Z\"},\"customer\":{\"avg_amount\":82.24,\"tx_count_24h\":3,\"known_merchants\":[\"MERC-016\"]},\"merchant\":{\"id\":\"MERC-016\",\"mcc\":\"5411\",\"avg_amount\":60.25},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":29.23},\"last_transaction\":null}";
            HttpResponse<String> fs = c.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/fraud-score"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, fs.statusCode());
            assertTrue(fs.body().contains("\"approved\":true"));
            assertTrue(fs.body().contains("\"fraud_score\":0.0"));
        } finally {
            server.stop();
        }
    }
}
