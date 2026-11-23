// Copyright 2026 Democratized Data Foundation
//
// Use of this software is governed by the Business Source License
// included in the file licenses/BSL.txt.
//
// As of the Change Date specified in that file, in accordance with
// the Business Source License, use of this software will be governed
// by the Apache License, Version 2.0, included in the file
// licenses/APL.txt.

package source.defra;

import java.math.BigDecimal;
import java.time.Duration;

/** Timeouts as the C API takes them: whole milliseconds, 0 for none. */
final class Durations {

    private static final BigDecimal NANOS_PER_MILLI = BigDecimal.valueOf(1_000_000);
    private static final BigDecimal MAX_MILLIS = BigDecimal.valueOf(Long.MAX_VALUE);

    private Durations() {
    }

    /**
     * {@code timeout} in milliseconds; null or zero is no timeout. A positive
     * timeout under a millisecond is one millisecond, since 0 would mean none.
     */
    static long millis(Duration timeout) {
        if (timeout == null || timeout.isZero()) {
            return 0;
        }
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative: " + timeout);
        }
        try {
            return Math.max(1, timeout.toMillis());
        } catch (ArithmeticException tooLong) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * A Go duration string ("1m30s", "250ms", "1.5h") in milliseconds, under
     * the same rules as {@link #millis(Duration)}; null or empty is no timeout.
     */
    static long goMillis(String timeout) throws DefraException {
        if (timeout == null || timeout.isEmpty()) {
            return 0;
        }
        String s = timeout.startsWith("+") ? timeout.substring(1) : timeout;
        if (s.equals("0")) {
            return 0;
        }
        if (s.isEmpty() || s.startsWith("-")) {
            throw invalid();
        }
        BigDecimal nanos = BigDecimal.ZERO;
        int i = 0;
        while (i < s.length()) {
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                i++;
            }
            BigDecimal value;
            try {
                value = new BigDecimal(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw invalid();
            }
            int unitStart = i;
            while (i < s.length() && !Character.isDigit(s.charAt(i)) && s.charAt(i) != '.') {
                i++;
            }
            nanos = nanos.add(value.multiply(BigDecimal.valueOf(unitNanos(s.substring(unitStart, i)))));
        }
        if (nanos.signum() == 0) {
            return 0;
        }
        BigDecimal millis = nanos.divide(NANOS_PER_MILLI, 0, java.math.RoundingMode.DOWN);
        if (millis.compareTo(MAX_MILLIS) >= 0) {
            return Long.MAX_VALUE;
        }
        return Math.max(1, millis.longValue());
    }

    private static long unitNanos(String unit) throws DefraException {
        switch (unit) {
            case "ns": return 1L;
            case "us":
            case "µs":
            case "μs": return 1_000L;
            case "ms": return 1_000_000L;
            case "s": return 1_000_000_000L;
            case "m": return 60_000_000_000L;
            case "h": return 3_600_000_000_000L;
            default: throw invalid();
        }
    }

    private static DefraException invalid() {
        return new DefraException("invalid timeout: expected a duration such as \"30s\" or \"1m30s\"");
    }
}
