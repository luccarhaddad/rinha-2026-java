package com.rinha.fraud.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rinha.fraud.model.FraudRequest;
import com.rinha.fraud.util.DateUtil;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JsonParserTest {
    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void matchesJacksonForAllExamples() throws Exception {
        JsonNode arr = M.readTree(getClass().getResourceAsStream("/example-payloads.json"));
        FraudRequest r = new FraudRequest();
        for (JsonNode node : arr) {
            byte[] body = M.writeValueAsBytes(node);
            r.reset();
            JsonParser.parse(body, body.length, r);

            JsonNode tx = node.get("transaction");
            assertEquals(tx.get("amount").asDouble(), r.amount, 1e-9);
            assertEquals(tx.get("installments").asInt(), r.installments);

            JsonNode cust = node.get("customer");
            assertEquals(cust.get("avg_amount").asDouble(), r.customerAvgAmount, 1e-9);
            assertEquals(cust.get("tx_count_24h").asInt(), r.txCount24h);

            JsonNode term = node.get("terminal");
            assertEquals(term.get("is_online").asBoolean(), r.isOnline);
            assertEquals(term.get("card_present").asBoolean(), r.cardPresent);
            assertEquals(term.get("km_from_home").asDouble(), r.kmFromHome, 1e-9);

            JsonNode merch = node.get("merchant");
            assertEquals(merch.get("avg_amount").asDouble(), r.merchantAvgAmount, 1e-9);
            assertEquals(Integer.parseInt(merch.get("mcc").asText()), r.mcc);

            boolean known = false;
            String mid = merch.get("id").asText();
            for (JsonNode km : cust.get("known_merchants")) {
                if (km.asText().equals(mid)) { known = true; break; }
            }
            assertEquals(!known, r.unknownMerchant, "unknownMerchant for " + node.get("id").asText());

            JsonNode last = node.get("last_transaction");
            if (last == null || last.isNull()) {
                assertFalse(r.hasLastTx);
            } else {
                assertTrue(r.hasLastTx);
                assertEquals(last.get("km_from_current").asDouble(), r.lastTxKmFromCurrent, 1e-9);
                long expSec = java.time.OffsetDateTime.parse(last.get("timestamp").asText()).toEpochSecond();
                assertEquals(expSec, DateUtil.epochSeconds(r.buf, r.lastTxTimestampOff));
            }

            long expReqSec = java.time.OffsetDateTime.parse(tx.get("requested_at").asText()).toEpochSecond();
            assertEquals(expReqSec, DateUtil.epochSeconds(r.buf, r.requestedAtOff));
        }
    }
}
