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

    /** One minute of trading: price at the start of the minute ({@code open}) and at its end ({@code close}). */
    public record Bar(Instant start, double open, double close) {

        /** A bar whose open is unknown; it stands in with the close. */
        public Bar(Instant start, double close) {
            this(start, close, close);
        }
    }

    public PriceSeries {
        bars = bars.stream().sorted(Comparator.comparing(Bar::start)).toList();
    }

    /**
     * Price at instant {@code t}: the close of the latest bar that had finished by {@code t}
     * (bar start + 1 min &lt;= t), provided it finished no more than {@link #MAX_STALENESS} earlier.
     * Example: the price at 09:30 is the close of the 09:29 bar.
     * <p>
     * At the market open there is no earlier bar, so the price at 09:15 is the <em>open</em> of the
     * 09:15 bar, i.e. the opening price set by NSE's pre-open auction.
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
        if (best != null && !best.start().plus(BAR).isBefore(t.minus(MAX_STALENESS))) {
            return OptionalDouble.of(best.close());
        }
        // Nothing (recent) closed yet: use the open of a bar starting exactly at t.
        for (Bar b : bars) {
            if (b.start().equals(t)) {
                return OptionalDouble.of(b.open());
            }
        }
        return OptionalDouble.empty();
    }
}
