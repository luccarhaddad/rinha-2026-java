package com.rinha.fraud.http;

import com.rinha.fraud.data.Dataset;
import com.rinha.fraud.data.MccRisk;
import com.rinha.fraud.data.Norm;
import com.rinha.fraud.model.FraudRequest;
import com.rinha.fraud.vec.KnnSearch;
import com.rinha.fraud.vec.Quantizer;
import com.rinha.fraud.vec.TopK;
import com.rinha.fraud.vec.Vectorizer;
import io.helidon.webserver.http.Handler;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;

import java.util.concurrent.ConcurrentLinkedQueue;

/** POST /fraud-score handler. Pure scoring is in score(); HTTP glue in handle(). */
public final class FraudHandler implements Handler {
    private final Dataset ds;
    private final MccRisk mcc;
    private final ConcurrentLinkedQueue<Scratch> pool = new ConcurrentLinkedQueue<>();

    /** Kernel selection. Default "vector" (optimal on the AVX2 amd64 target).
     *  Set KERNEL=scalar to use the auto-vectorized scalar loop — useful on hosts
     *  without AVX2 (e.g. ARM dev hosts) where SPECIES_256 is emulated and slow. */
    private static final boolean USE_SCALAR =
            "scalar".equalsIgnoreCase(System.getenv().getOrDefault("KERNEL", "vector"));

    public FraudHandler(Dataset ds, MccRisk mcc) { this.ds = ds; this.mcc = mcc; }

    private static final class Scratch {
        final FraudRequest req = new FraudRequest();
        final float[] vec = new float[14];
        final byte[] q = new byte[14];
        final int[] block = new int[KnnSearch.TILE];
        final TopK top = new TopK();
    }

    private Scratch acquire() { Scratch s = pool.poll(); return s != null ? s : new Scratch(); }
    private void release(Scratch s) { pool.offer(s); }

    /** Pure scoring: body -> response bytes. */
    public byte[] score(byte[] body, int len) {
        Scratch s = acquire();
        try {
            s.req.reset();
            JsonParser.parse(body, len, s.req);
            Vectorizer.vectorize(s.req, s.vec, Norm.DEFAULT, mcc);
            for (int d = 0; d < 14; d++) s.q[d] = (byte) Quantizer.q(s.vec[d]);
            s.top.reset();
            if (USE_SCALAR) KnnSearch.scoreAllScalar(s.q, ds.vectors, ds.n, ds.dims, s.block, s.top);
            else KnnSearch.scoreAll(s.q, ds.vectors, ds.n, ds.dims, s.block, s.top);
            int frauds = s.top.countFrauds(ds.labels);
            return serialize(frauds);
        } finally {
            release(s);
        }
    }

    /**
     * frauds in 0..5 → one of 6 pre-built byte arrays. Zero allocation per request.
     * Decision boundary: approved = fraud_score < 0.6 ⇔ frauds < 3.
     */
    private static final byte[][] RESPONSES = {
        "{\"approved\":true,\"fraud_score\":0.0}".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        "{\"approved\":true,\"fraud_score\":0.2}".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        "{\"approved\":true,\"fraud_score\":0.4}".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        "{\"approved\":false,\"fraud_score\":0.6}".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        "{\"approved\":false,\"fraud_score\":0.8}".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        "{\"approved\":false,\"fraud_score\":1.0}".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
    };

    static byte[] serialize(int frauds) {
        return RESPONSES[frauds];
    }

    @Override
    public void handle(ServerRequest request, ServerResponse response) {
        byte[] body = request.content().as(byte[].class);
        response.header(io.helidon.http.HeaderNames.CONTENT_TYPE, "application/json");
        response.send(score(body, body.length));
    }
}
