package com.rinha.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rinha.fraud.data.MccRisk;
import com.rinha.fraud.data.Norm;
import com.rinha.fraud.http.JsonParser;
import com.rinha.fraud.model.FraudRequest;
import com.rinha.fraud.vec.*;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EndToEndDetectionTest {
    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void int8PipelineMatchesExactFloat64Decision() throws Exception {
        JsonNode refs = M.readTree(getClass().getResourceAsStream("/example-references.json"));
        int n = refs.size(), dims = 14;
        float[][] rf = new float[n][dims];
        byte[] labels = new byte[n];
        byte[] soa = new byte[n * dims];
        for (int r = 0; r < n; r++) {
            JsonNode vec = refs.get(r).get("vector");
            for (int d = 0; d < dims; d++) {
                float x = (float) vec.get(d).asDouble();
                rf[r][d] = x;
                soa[d * n + r] = Quantizer.qb(x);
            }
            labels[r] = (byte) ("fraud".equals(refs.get(r).get("label").asText()) ? 1 : 0);
        }
        MemorySegment seg = Arena.ofAuto().allocate(soa.length);
        MemorySegment.copy(soa, 0, seg, ValueLayout.JAVA_BYTE, 0, soa.length);

        JsonNode payloads = M.readTree(getClass().getResourceAsStream("/example-payloads.json"));
        FraudRequest req = new FraudRequest();
        float[] scratch = new float[dims];
        byte[] q = new byte[dims];
        int[] block = new int[KnnSearch.TILE];
        MccRisk mcc = MccRisk.defaults();

        int flips = 0;
        for (JsonNode node : payloads) {
            byte[] body = M.writeValueAsBytes(node);
            req.reset();
            JsonParser.parse(body, body.length, req);
            Vectorizer.vectorize(req, scratch, Norm.DEFAULT, mcc);

            boolean exactApproved = exactDecision(scratch, rf, labels, dims);

            for (int d = 0; d < dims; d++) q[d] = Quantizer.qb(scratch[d]);
            TopK top = new TopK(); top.reset();
            KnnSearch.scoreAll(q, seg, n, dims, block, top);
            float score = top.countFrauds(labels) / 5f;
            boolean int8Approved = score < 0.6f;

            if (exactApproved != int8Approved) flips++;
        }
        assertEquals(0, flips, "int8 quantization should not flip decisions on the example set");
    }

    private static boolean exactDecision(float[] query, float[][] rf, byte[] labels, int dims) {
        int n = rf.length;
        double[] dist = new double[n];
        Integer[] idx = new Integer[n];
        for (int r = 0; r < n; r++) {
            double acc = 0;
            for (int d = 0; d < dims; d++) { double diff = rf[r][d] - query[d]; acc += diff * diff; }
            dist[r] = acc; idx[r] = r;
        }
        Arrays.sort(idx, Comparator.comparingDouble(a -> dist[a]));
        int frauds = 0;
        for (int k = 0; k < 5 && k < n; k++) frauds += labels[idx[k]];
        return (frauds / 5f) < 0.6f;
    }
}
