package com.rinha.fraud.util;

/** Parses fixed-format ISO-8601 UTC timestamps "YYYY-MM-DDThh:mm:ssZ" from a byte slice, no allocation. */
public final class DateUtil {
    private DateUtil() {}

    private static int d2(byte[] b, int i) { return (b[i] - '0') * 10 + (b[i + 1] - '0'); }
    private static int d4(byte[] b, int i) {
        return (b[i] - '0') * 1000 + (b[i + 1] - '0') * 100 + (b[i + 2] - '0') * 10 + (b[i + 3] - '0');
    }

    public static int hour(byte[] b, int off) { return d2(b, off + 11); }

    /** Days since 1970-01-01 (Howard Hinnant's days_from_civil). */
    private static long epochDays(byte[] b, int off) {
        int y = d4(b, off);
        int m = d2(b, off + 5);
        int d = d2(b, off + 8);
        y -= (m <= 2) ? 1 : 0;
        long era = (y >= 0 ? y : y - 399) / 400;
        long yoe = y - era * 400;
        long doy = (153L * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    /** Monday=0 .. Sunday=6. */
    public static int dayOfWeek(byte[] b, int off) {
        long ed = epochDays(b, off);
        return (int) (((ed % 7) + 3 + 7) % 7); // 1970-01-01 (ed=0) is Thursday=3
    }

    public static long epochSeconds(byte[] b, int off) {
        long days = epochDays(b, off);
        int hh = d2(b, off + 11), mm = d2(b, off + 14), ss = d2(b, off + 17);
        return days * 86400L + hh * 3600L + mm * 60L + ss;
    }
}
