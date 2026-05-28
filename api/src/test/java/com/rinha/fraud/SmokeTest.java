package com.rinha.fraud;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeTest {
    @Test
    void fixturesPresent() {
        assertTrue(getClass().getResourceAsStream("/example-references.json") != null);
        assertTrue(getClass().getResourceAsStream("/example-payloads.json") != null);
    }
}
