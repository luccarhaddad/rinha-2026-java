package com.rinha.fraud.http;

import com.rinha.fraud.data.MccRisk;
import com.rinha.fraud.data.Norm;
import com.rinha.fraud.faiss.FaissIndex;
import com.rinha.fraud.model.FraudRequest;
import com.rinha.fraud.vec.Vectorizer;
import io.helidon.webserver.http.Handler;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Production handler: scores via FAISS IVF1024,SQ8 over the prebuilt index.
 * Parallel to {@link FraudHandler} (brute-force, kept for tests/fallback).
 * App.java picks one based on KERNEL env.
 */
public final class FaissFraudHandler implements Handler {
    private final FaissIndex faiss;
    private final byte[] labels;
    private final MccRisk mcc;
    private final ConcurrentLinkedQueue<Scratch> pool = new ConcurrentLinkedQueue<>();

    public FaissFraudHandler(FaissIndex faiss, byte[] labels, MccRisk mcc) {
        this.faiss = faiss;
        this.labels = labels;
        this.mcc = mcc;
    }

    private static final class Scratch implements AutoCloseable {
        final FraudRequest req = new FraudRequest();
        final float[] vec = new float[14];
        final FaissIndex.Scratch faissScratch = new FaissIndex.Scratch();

        @Override public void close() { faissScratch.close(); }
    }

    private Scratch acquire() {
        Scratch s = pool.poll();
        return s != null ? s : new Scratch();
    }
    private void release(Scratch s) { pool.offer(s); }

    /** Pure scoring: body bytes -> response bytes. */
    public byte[] score(byte[] body, int len) {
        Scratch s = acquire();
        try {
            s.req.reset();
            JsonParser.parse(body, len, s.req);
            Vectorizer.vectorize(s.req, s.vec, Norm.DEFAULT, mcc);
            faiss.search(s.vec, FaissIndex.Scratch.K, s.faissScratch);
            int frauds = 0;
            for (int i = 0; i < FaissIndex.Scratch.K; i++) {
                long ordinal = s.faissScratch.labels[i];
                if (ordinal >= 0) frauds += labels[(int) ordinal];
            }
            return FraudHandler.serialize(frauds);
        } finally {
            release(s);
        }
    }

    @Override
    public void handle(ServerRequest request, ServerResponse response) {
        byte[] body = request.content().as(byte[].class);
        response.header(io.helidon.http.HeaderNames.CONTENT_TYPE, "application/json");
        response.send(score(body, body.length));
    }
}
