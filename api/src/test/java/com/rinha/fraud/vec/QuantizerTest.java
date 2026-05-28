package com.rinha.fraud.vec;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class QuantizerTest {
    @Test
    void mapsRange() {
        assertEquals(0,   Quantizer.q(-1f));
        assertEquals(128, Quantizer.q(0f));
        assertEquals(255, Quantizer.q(1f));
    }
    @Test
    void clampsOutOfRange() {
        assertEquals(0,   Quantizer.q(-5f));
        assertEquals(255, Quantizer.q(2f));
    }
    @Test
    void roundsToNearest() {
        assertEquals(191, Quantizer.q(0.5f));
    }
}
