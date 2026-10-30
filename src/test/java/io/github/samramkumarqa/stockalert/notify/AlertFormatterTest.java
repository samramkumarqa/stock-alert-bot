package io.github.samramkumarqa.stockalert.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.github.samramkumarqa.stockalert.Scanner;
import io.github.samramkumarqa.stockalert.rule.Observation;

class AlertFormatterTest {

    private static Scanner.Result result(List<Observation> obs, List<Observation> signals, List<String> failures,
            int requested) {
        return new Scanner.Result(LocalDate.of(2026, 10, 29), LocalTime.of(9, 30), LocalTime.of(9, 45),
                requested, obs, signals, failures);
    }

    @Test
    void buyAlertListsEachStockWithGainAndBothPrices() {
        Observation tm = new Observation("TATAMOTORS", 700, 720);
        Observation sbin = new Observation("SBIN", 800, 813);
        Alert a = AlertFormatter.format(result(List.of(tm, sbin), List.of(tm, sbin), List.of(), 2), 1.5, 5, List.of());

        assertThat(a.subject()).isEqualTo("📈 BUY ALERT · 2 stocks · Thu 29 Oct 2026");
        assertThat(a.body())
                .contains("Up more than 1.5% from 9:30 → 9:45 IST:")
                .contains("1. TATAMOTORS  +2.86%  ₹700.00 → ₹720.00")
                .contains("2. SBIN  +1.63%  ₹800.00 → ₹813.00")
                .contains("Checked 2 of 2 stocks.")
                .contains("no orders were placed")
                .doesNotContain("more above");
    }

    @Test
    void onlyTopFiveGainersAreListedWhenManyQualify() {
        // 12 qualifying stocks, already sorted by the rule: G0 (+12%) ... G11 (+2.2%)
        List<Observation> signals = IntStream.range(0, 12)
                .mapToObj(i -> new Observation("G" + i, 100, 112 - i * 0.9)).toList();

        Alert a = AlertFormatter.format(result(signals, signals, List.of(), 500), 1.5, 5, List.of());

        assertThat(a.subject()).isEqualTo("📈 BUY ALERT · Top 5 of 12 stocks · Thu 29 Oct 2026");
        assertThat(a.body())
                .contains("Top 5 gainers, each up more than 1.5% from 9:30 → 9:45 IST:")
                .contains("1. G0 ").contains("5. G4 ")
                .doesNotContain("G5 ")
                .contains("+7 more above 1.5% (full list in the CSV report).");
    }

    @Test
    void zeroMeansNoLimit() {
        List<Observation> signals = IntStream.range(0, 8)
                .mapToObj(i -> new Observation("G" + i, 100, 110)).toList();
        Alert a = AlertFormatter.format(result(signals, signals, List.of(), 8), 1.5, 0, List.of());

        assertThat(a.subject()).contains("8 stocks");
        assertThat(a.body()).contains("8. G7 ");
    }

    @Test
    void quietDayStillReportsSoYouKnowItRan() {
        Alert a = AlertFormatter.format(result(List.of(new Observation("INFY", 1500, 1501)), List.of(), List.of(), 1),
                1.5, 5, List.of());

        assertThat(a.subject()).startsWith("No buy alerts");
        assertThat(a.body()).contains("No stock rose more than 1.5% between 9:30 → 9:45 IST.");
    }

    @Test
    void warnsWhenManyStocksCouldNotBePriced() {
        List<String> failures = Collections.nCopies(60, "X: HTTP 429");
        Alert a = AlertFormatter.format(result(List.of(), List.of(), failures, 500), 1.5, 5, List.of());

        assertThat(a.body()).contains("⚠ 60 stocks could not be priced (12%)");
    }

    @Test
    void wholeNumberThresholdHasNoDecimal() {
        Alert a = AlertFormatter.format(result(List.of(), List.of(), List.of(), 0), 2.0, 5, List.of());
        assertThat(a.body()).contains("more than 2% between");
    }
}
