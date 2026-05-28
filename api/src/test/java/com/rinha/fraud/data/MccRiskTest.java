package com.rinha.fraud.data;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MccRiskTest {
    @Test
    void knownMcc() {
        MccRisk m = MccRisk.defaults();
        assertEquals(0.15f, m.risk("5411"), 1e-6);
        assertEquals(0.85f, m.risk("7995"), 1e-6);
    }
    @Test
    void unknownMccDefaultsToHalf() {
        assertEquals(0.5f, MccRisk.defaults().risk("9999"), 1e-6);
        assertEquals(0.5f, MccRisk.defaults().risk("0000"), 1e-6);
    }
}
