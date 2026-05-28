package com.rinha.fraud.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rinha.fraud.data.Dataset;
import com.rinha.fraud.data.MccRisk;
import com.rinha.fraud.preprocess.PreprocessDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class FraudHandlerLogicTest {
    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void producesWellFormedResponse(@TempDir Path dir) throws Exception {
        JsonNode refs = M.readTree(getClass().getResourceAsStream("/example-references.json"));
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < refs.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(refs.get(i).toString());
        }
        sb.append(']');
        Path gz = dir.resolve("ref.json.gz");
        try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(gz)), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        }
        Path bin = dir.resolve("dataset.i8bin"), labels = dir.resolve("labels.bin");
        PreprocessDataset.run(gz, bin, labels);
        Dataset ds = Dataset.load(bin, labels);

        FraudHandler h = new FraudHandler(ds, MccRisk.defaults());

        JsonNode payloads = M.readTree(getClass().getResourceAsStream("/example-payloads.json"));
        for (JsonNode node : payloads) {
            byte[] body = M.writeValueAsBytes(node);
            byte[] resp = h.score(body, body.length);
            JsonNode r = M.readTree(resp);
            assertTrue(r.has("approved"));
            assertTrue(r.has("fraud_score"));
            double s = r.get("fraud_score").asDouble();
            assertTrue(s >= 0.0 && s <= 1.0);
            assertEquals(s < 0.6, r.get("approved").asBoolean());
            long times5 = Math.round(s * 5);
            assertEquals(s, times5 / 5.0, 1e-9);
        }
    }
}
