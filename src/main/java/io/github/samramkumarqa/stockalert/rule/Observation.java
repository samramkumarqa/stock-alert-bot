package io.github.samramkumarqa.stockalert.rule;

/** A stock's price at the baseline time and at the check time. */
public record Observation(String symbol, double baselinePrice, double checkPrice) {

    /** Percentage change from baseline to check, e.g. 2.0 for +2%. */
    public double changePct() {
        return (checkPrice - baselinePrice) / baselinePrice * 100.0;
    }
}
