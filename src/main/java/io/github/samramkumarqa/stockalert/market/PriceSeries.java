package io.github.samramkumarqa.stockalert.market;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

/**
 * One trading day of 1-minute bars for a symbol.
 * A bar's {@code start} is the minute it opened; its close is the price at {@code start + 1 min}.
 */
public record PriceSeries(String symbol, List<Bar> bars) {

    public static final Duration BAR = Duration.ofMinutes(1);
    /** How stale a bar may be and still stand in for "the price at time T" (illiquid stocks). */
    public static final Duration MAX_STALENESS = Duration.ofMinutes(5);

    public record Bar(Instant start, double close) {
    }

    public PriceSeries {
        bars = bars.stream().sorted(Comparator.comparing(Bar::start)).toList();
    }

    /**
     * Price at instant {@code t}: the close of the latest bar that had finished by {@code t}
     * (bar start + 1 min &lt;= t), provided it finished no more than {@link #MAX_STALENESS} earlier.
     * Example: the price at 09:30 is the close of the 09:29 bar.
     */
    public OptionalDouble priceAt(Instant t) {
        Bar best = null;
        for (Bar b : bars) {
            Instant end = b.start().plus(BAR);
            if (end.isAfter(t)) {
                break;
            }
            best = b;
        }
        if (best == null || best.start().plus(BAR).isBefore(t.minus(MAX_STALENESS))) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(best.close());
    }
}
