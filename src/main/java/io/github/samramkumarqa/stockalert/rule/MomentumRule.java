package io.github.samramkumarqa.stockalert.rule;

import java.util.Comparator;
import java.util.List;

/**
 * Opening-range momentum rule: flag every stock whose price rose by
 * <em>more than</em> {@code thresholdPct} between the baseline and the check time.
 * Pure logic, no I/O, so it is fully unit-tested.
 */
public final class MomentumRule {

    private final double thresholdPct;

    public MomentumRule(double thresholdPct) {
        if (thresholdPct <= 0 || Double.isNaN(thresholdPct)) {
            throw new IllegalArgumentException("thresholdPct must be positive: " + thresholdPct);
        }
        this.thresholdPct = thresholdPct;
    }

    public double thresholdPct() {
        return thresholdPct;
    }

    public boolean triggers(Observation o) {
        return o.baselinePrice() > 0 && o.checkPrice() > 0 && o.changePct() > thresholdPct;
    }

    /** Stocks that trigger, biggest gain first. */
    public List<Observation> signals(List<Observation> observations) {
        return observations.stream()
                .filter(this::triggers)
                .sorted(Comparator.comparingDouble(Observation::changePct).reversed()
                        .thenComparing(Observation::symbol))
                .toList();
    }
}
