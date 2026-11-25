package io.github.samramkumarqa.stockalert.rule;

/**
 * A stock's price at the baseline time, at the check time and, optionally, at a confirm time
 * in between ({@code NaN} when the rule doesn't use one).
 */
public record Observation(String symbol, double baselinePrice, double checkPrice, double confirmPrice) {

    public Observation(String symbol, double baselinePrice, double checkPrice) {
        this(symbol, baselinePrice, checkPrice, Double.NaN);
    }

    /** Percentage change from baseline to check, e.g. 2.0 for +2%. */
    public double changePct() {
        return (checkPrice - baselinePrice) / baselinePrice * 100.0;
    }

    /** Still rising: the check price is at or above the confirm price (always true without one). */
    public boolean stillRising() {
        return Double.isNaN(confirmPrice) || checkPrice >= confirmPrice;
    }
}
