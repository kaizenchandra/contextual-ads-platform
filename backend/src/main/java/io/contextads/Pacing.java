package io.contextads;

import java.math.BigInteger;
import java.time.*;

final class Pacing {
    private Pacing() {
    }

    static long allowance(String mode, long dailyLimit, Instant now, Instant start, Instant end) {
        if (now.isBefore(start) || !now.isBefore(end)) return 0;
        if (mode.equals("ACCELERATED")) return dailyLimit;
        if (!mode.equals("EVEN")) throw new IllegalArgumentException("Unknown pacing mode");
        Instant midnight = now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant from = start.isAfter(midnight) ? start : midnight;
        Instant until = end.isBefore(midnight.plus(Duration.ofDays(1))) ? end : midnight.plus(Duration.ofDays(1));
        long elapsed = Duration.between(from, now).toMillis();
        long duration = Duration.between(from, until).toMillis();
        // Exact integer arithmetic: no floating-point money or multiplication overflow.
        return BigInteger.valueOf(dailyLimit).multiply(BigInteger.valueOf(elapsed))
                .divide(BigInteger.valueOf(duration)).longValueExact();
    }
}
