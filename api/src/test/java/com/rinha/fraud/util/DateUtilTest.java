package com.rinha.fraud.util;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DateUtilTest {
    private static byte[] b(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    @Test
    void hourAndDow() {
        byte[] t = b("2026-03-11T18:45:53Z"); // 2026-03-11 is a Wednesday
        assertEquals(18, DateUtil.hour(t, 0));
        assertEquals(2,  DateUtil.dayOfWeek(t, 0)); // Mon=0 -> Wed=2
    }

    @Test
    void epochSecondsMatchesJdk() {
        String[] samples = {"2026-03-11T18:45:53Z", "2026-03-11T14:58:35Z",
                            "1970-01-01T00:00:00Z", "2026-03-14T05:15:12Z"};
        for (String s : samples) {
            long expected = OffsetDateTime.parse(s).toEpochSecond();
            assertEquals(expected, DateUtil.epochSeconds(b(s), 0), "for " + s);
        }
    }

    @Test
    void minutesBetween() {
        long a = DateUtil.epochSeconds(b("2026-03-11T18:45:53Z"), 0);
        long c = DateUtil.epochSeconds(b("2026-03-11T14:58:35Z"), 0);
        assertEquals(227, (a - c) / 60);
    }

    @Test
    void dowKnownDays() {
        assertEquals(3, DateUtil.dayOfWeek(b("1970-01-01T00:00:00Z"), 0)); // Thursday
        assertEquals(6, DateUtil.dayOfWeek(b("2026-03-15T00:00:00Z"), 0)); // Sunday
    }
}
