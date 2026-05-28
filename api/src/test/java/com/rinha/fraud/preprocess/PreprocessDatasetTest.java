package com.rinha.fraud.preprocess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class PreprocessDatasetTest {
    @Test
    void roundTrip(@TempDir Path dir) throws Exception {
        Path gz = dir.resolve("ref.json.gz");
        String json = "[{\"vector\":[0,0.5,1,-1,0,0,0,0,0,0,0,0,0,0],\"label\":\"legit\"}," +
                      "{\"vector\":[1,1,1,1,1,1,1,1,1,1,1,1,1,1],\"label\":\"fraud\"}]";
        try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(gz)), StandardCharsets.UTF_8)) {
            w.write(json);
        }
        Path bin = dir.resolve("dataset.i8bin");
        Path labels = dir.resolve("labels.bin");
        PreprocessDataset.run(gz, bin, labels);

        byte[] data = Files.readAllBytes(bin);
        int n = (data[0]&0xFF) | (data[1]&0xFF)<<8 | (data[2]&0xFF)<<16 | (data[3]&0xFF)<<24;
        int dims = (data[4]&0xFF) | (data[5]&0xFF)<<8 | (data[6]&0xFF)<<16 | (data[7]&0xFF)<<24;
        assertEquals(2, n);
        assertEquals(14, dims);
        assertEquals(8 + 2*14, data.length);

        // SoA: dim d, record r at offset 8 + d*N + r
        assertEquals(128, data[8 + 0*2 + 0] & 0xFF);
        assertEquals(191, data[8 + 1*2 + 0] & 0xFF);
        assertEquals(255, data[8 + 2*2 + 0] & 0xFF);
        assertEquals(0,   data[8 + 3*2 + 0] & 0xFF);
        assertEquals(255, data[8 + 0*2 + 1] & 0xFF);

        byte[] lab = Files.readAllBytes(labels);
        assertArrayEquals(new byte[]{0,1}, lab);
    }
}
