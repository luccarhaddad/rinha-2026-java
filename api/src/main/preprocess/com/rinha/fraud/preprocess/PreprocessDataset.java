package com.rinha.fraud.preprocess;

import com.fasterxml.jackson.core.*;
import com.rinha.fraud.vec.Quantizer;

import java.io.*;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;

/** Build-time tool: references.json.gz -> SoA int8 dataset.i8bin + labels.bin. */
public final class PreprocessDataset {
    private static final int DIMS = 14;

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: PreprocessDataset <references.json.gz> <dataset.i8bin> <labels.bin>");
            System.exit(2);
        }
        run(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
    }

    public static void run(Path gz, Path binOut, Path labelsOut) throws Exception {
        long start = System.nanoTime();
        int n = countRecords(gz);

        byte[] soa = new byte[DIMS * n];
        byte[] labels = new byte[n];
        long frauds = 0;

        JsonFactory f = new JsonFactory();
        try (JsonParser p = f.createParser(new GZIPInputStream(Files.newInputStream(gz)))) {
            expect(p.nextToken(), JsonToken.START_ARRAY);
            int rec = 0;
            float[] v = new float[DIMS];
            while (p.nextToken() == JsonToken.START_OBJECT) {
                String label = null;
                while (p.nextToken() != JsonToken.END_OBJECT) {
                    String field = p.currentName();
                    p.nextToken();
                    if ("vector".equals(field)) {
                        int d = 0;
                        while (p.nextToken() != JsonToken.END_ARRAY) v[d++] = p.getFloatValue();
                    } else if ("label".equals(field)) {
                        label = p.getText();
                    } else {
                        p.skipChildren();
                    }
                }
                for (int d = 0; d < DIMS; d++) soa[d * n + rec] = Quantizer.qb(v[d]);
                boolean isFraud = "fraud".equals(label);
                labels[rec] = (byte) (isFraud ? 1 : 0);
                if (isFraud) frauds++;
                rec++;
            }
        }

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(binOut)))) {
            writeLEInt(out, n);
            writeLEInt(out, DIMS);
            out.write(soa);
        }
        Files.write(labelsOut, labels);

        double secs = (System.nanoTime() - start) / 1e9;
        System.out.printf("records=%d frauds=%.2f%% bin=%dB labels=%dB time=%.1fs%n",
                n, 100.0 * frauds / n, 8L + (long) DIMS * n, n, secs);
    }

    private static int countRecords(Path gz) throws IOException {
        JsonFactory f = new JsonFactory();
        int count = 0;
        try (JsonParser p = f.createParser(new GZIPInputStream(Files.newInputStream(gz)))) {
            expect(p.nextToken(), JsonToken.START_ARRAY);
            while (p.nextToken() == JsonToken.START_OBJECT) { p.skipChildren(); count++; }
        }
        return count;
    }

    private static void writeLEInt(DataOutputStream o, int v) throws IOException {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >> 24) & 0xFF);
    }
    private static void expect(JsonToken got, JsonToken want) {
        if (got != want) throw new IllegalStateException("expected " + want + " got " + got);
    }
    private PreprocessDataset() {}
}
