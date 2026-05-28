package com.rinha.fraud.vec;

import com.rinha.fraud.data.MccRisk;
import com.rinha.fraud.data.Norm;
import com.rinha.fraud.http.JsonParser;
import com.rinha.fraud.model.FraudRequest;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class VectorizerTest {
    private static final MccRisk MCC = MccRisk.defaults();

    private float[] vec(String json) {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        FraudRequest r = new FraudRequest(); r.reset();
        JsonParser.parse(b, b.length, r);
        float[] out = new float[14];
        Vectorizer.vectorize(r, out, Norm.DEFAULT, MCC);
        return out;
    }

    @Test
    void legitGolden() {
        String j = """
            {"id":"tx-1329056812","transaction":{"amount":41.12,"installments":2,"requested_at":"2026-03-11T18:45:53Z"},
            "customer":{"avg_amount":82.24,"tx_count_24h":3,"known_merchants":["MERC-003","MERC-016"]},
            "merchant":{"id":"MERC-016","mcc":"5411","avg_amount":60.25},
            "terminal":{"is_online":false,"card_present":true,"km_from_home":29.23},
            "last_transaction":null}""";
        float[] exp = {0.0041f,0.1667f,0.05f,0.7826f,0.3333f,-1f,-1f,0.0292f,0.15f,0f,1f,0f,0.15f,0.006f};
        assertArrayEquals(exp, vec(j), 1e-3f);
    }

    @Test
    void fraudGolden() {
        String j = """
            {"id":"tx-3330991687","transaction":{"amount":9505.97,"installments":10,"requested_at":"2026-03-14T05:15:12Z"},
            "customer":{"avg_amount":81.28,"tx_count_24h":20,"known_merchants":["MERC-008","MERC-007","MERC-005"]},
            "merchant":{"id":"MERC-068","mcc":"7802","avg_amount":54.86},
            "terminal":{"is_online":false,"card_present":true,"km_from_home":952.27},
            "last_transaction":null}""";
        float[] exp = {0.9506f,0.8333f,1.0f,0.2174f,0.8333f,-1f,-1f,0.9523f,1.0f,0f,1f,1f,0.75f,0.0055f};
        assertArrayEquals(exp, vec(j), 1e-3f);
    }
}
