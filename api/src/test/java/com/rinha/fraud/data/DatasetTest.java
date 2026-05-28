package com.rinha.fraud.data;

import com.rinha.fraud.preprocess.PreprocessDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class DatasetTest {
    @Test
    void loadsAndReadsSoA(@TempDir Path dir) throws Exception {
        Path gz = dir.resolve("ref.json.gz");
        String json = "[{\"vector\":[0,0.5,1,-1,0,0,0,0,0,0,0,0,0,0],\"label\":\"legit\"}," +
                      "{\"vector\":[1,1,1,1,1,1,1,1,1,1,1,1,1,1],\"label\":\"fraud\"}]";
        try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(gz)), StandardCharsets.UTF_8)) {
            w.write(json);
        }
        Path bin = dir.resolve("dataset.i8bin"), labels = dir.resolve("labels.bin");
        PreprocessDataset.run(gz, bin, labels);

        Dataset ds = Dataset.load(bin, labels);
        assertEquals(2, ds.n);
        assertEquals(14, ds.dims);
        assertEquals(128, ds.byteAt(0, 0));
        assertEquals(191, ds.byteAt(1, 0));
        assertEquals(255, ds.byteAt(2, 0));
        assertEquals(0,   ds.byteAt(3, 0));
        assertEquals(255, ds.byteAt(0, 1));
        assertArrayEquals(new byte[]{0,1}, ds.labels);
    }
}
