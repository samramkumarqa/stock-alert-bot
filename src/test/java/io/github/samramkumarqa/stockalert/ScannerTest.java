package io.github.samramkumarqa.stockalert;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.samramkumarqa.stockalert.market.PriceSeries;
import io.github.samramkumarqa.stockalert.market.PriceSource;
import io.github.samramkumarqa.stockalert.rule.MomentumRule;
import io.github.samramkumarqa.stockalert.rule.Observation;

class ScannerTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 29);
    private static final LocalTime T930 = LocalTime.of(9, 30);
    private static final LocalTime T945 = LocalTime.of(9, 45);

    /** Fake source: a bar closing at 09:30 with p0 and one closing at 09:45 with p1. */
    private static PriceSource fake(Map<String, double[]> prices) {
        return (symbol, date) -> {
            double[] p = prices.get(symbol);
            if (p == null) {
                throw new IOException("HTTP 404");
            }
            return new PriceSeries(symbol, List.of(
                    new PriceSeries.Bar(date.atTime(9, 29).atZone(IST).toInstant(), p[0]),
                    new PriceSeries.Bar(date.atTime(9, 44).atZone(IST).toInstant(), p[1])));
        };
    }

    @Test
    void findsStocksUpMoreThanThresholdBetween930And945() throws Exception {
        Scanner scanner = new Scanner(fake(Map.of(
                "TATAMOTORS", new double[] {700, 720},   // +2.86%
                "INFY", new double[] {1500, 1510},       // +0.67%
                "SBIN", new double[] {800, 813})),       // +1.625%
                new MomentumRule(1.5), IST, 4);

        Scanner.Result r = scanner.scan(List.of("INFY", "SBIN", "TATAMOTORS"), DAY, T930, T945);

        assertThat(r.signals()).extracting(Observation::symbol).containsExactly("TATAMOTORS", "SBIN");
        assertThat(r.observations()).hasSize(3);
        assertThat(r.failures()).isEmpty();
    }

    @Test
    void oneBadSymbolDoesNotStopTheScan() throws Exception {
        Scanner scanner = new Scanner(fake(Map.of("GOOD", new double[] {100, 103})), new MomentumRule(1.5), IST, 2);

        Scanner.Result r = scanner.scan(List.of("GOOD", "BAD"), DAY, T930, T945);

        assertThat(r.signals()).extracting(Observation::symbol).containsExactly("GOOD");
        assertThat(r.failures()).singleElement().asString().startsWith("BAD:");
        assertThat(r.failureRate()).isEqualTo(0.5);
    }

    @Test
    void reportsSymbolsWithNoTradesNearTheBaseline() throws Exception {
        PriceSource onlyLateBars = (symbol, date) -> new PriceSeries(symbol, List.of(
                new PriceSeries.Bar(date.atTime(9, 44).atZone(IST).toInstant(), 50)));
        Scanner.Result r = new Scanner(onlyLateBars, new MomentumRule(1.5), IST, 1)
                .scan(List.of("QUIET"), DAY, T930, T945);

        assertThat(r.observations()).isEmpty();
        assertThat(r.failures()).containsExactly("QUIET: no trades near 09:30");
    }

    @Test
    void neverExceedsConfiguredConcurrency() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        PriceSource slow = (symbol, date) -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(20);
            } finally {
                inFlight.decrementAndGet();
            }
            return fake(Map.of(symbol, new double[] {1, 1})).intraday(symbol, date);
        };
        List<String> symbols = java.util.stream.IntStream.range(0, 40).mapToObj(i -> "S" + i).toList();

        new Scanner(slow, new MomentumRule(1.5), IST, 3).scan(symbols, DAY, T930, T945);

        assertThat(peak.get()).isBetween(1, 3);
    }
}
