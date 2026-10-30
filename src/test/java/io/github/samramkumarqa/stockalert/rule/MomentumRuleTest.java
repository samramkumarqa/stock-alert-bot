package io.github.samramkumarqa.stockalert.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MomentumRuleTest {

    private final MomentumRule rule = new MomentumRule(1.5);

    @ParameterizedTest(name = "{0} -> {1} triggers={2}")
    @CsvSource({
            "100.00, 101.49, false",   // +1.49%
            "100.00, 101.50, false",   // exactly +1.5% is NOT "more than"
            "100.00, 101.51, true",    // +1.51%
            "200.00, 210.00, true",    // +5%
            "100.00,  98.00, false",   // falling stock
            "100.00, 100.00, false"    // flat
    })
    void triggersOnlyAboveThreshold(double base, double now, boolean expected) {
        assertThat(rule.triggers(new Observation("X", base, now))).isEqualTo(expected);
    }

    @Test
    void ignoresBadPrices() {
        assertThat(rule.triggers(new Observation("ZERO", 0, 10))).isFalse();
        assertThat(rule.triggers(new Observation("NEG", 10, -1))).isFalse();
    }

    @Test
    void signalsAreSortedByBiggestGainThenSymbol() {
        List<Observation> obs = List.of(
                new Observation("AAA", 100, 102),     // +2%
                new Observation("BBB", 100, 105),     // +5%
                new Observation("CCC", 100, 101),     // +1%  (not a signal)
                new Observation("ABC", 50, 51));      // +2%  ties with AAA

        assertThat(rule.signals(obs)).extracting(Observation::symbol)
                .containsExactly("BBB", "AAA", "ABC");
    }

    @Test
    void changePctIsRelativeToBaseline() {
        assertThat(new Observation("X", 250, 255).changePct()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void rejectsNonPositiveThreshold() {
        assertThatThrownBy(() -> new MomentumRule(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MomentumRule(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
