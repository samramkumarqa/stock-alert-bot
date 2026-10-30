package io.github.samramkumarqa.stockalert.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;

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
        Alert a = AlertFormatter.format(result(List.of(tm, sbin), List.of(tm, sbin), List.of(), 2), 1.5, List.of());

        assertThat(a.subject()).isEqualTo("📈 BUY ALERT · 2 stocks · Thu 29 Oct 2026");
        assertThat(a.body())
                .contains("Up more than 1.5% from 9:30 → 9:45 IST:")
                .contains("1. TATAMOTORS  +2.86%  ₹700.00 → ₹720.00")
                .contains("2. SBIN  +1.63%  ₹800.00 → ₹813.00")
                .contains("Checked 2 of 2 stocks.")
                .contains("no orders were placed");
    }

    @Test
    void quietDayStillReportsSoYouKnowItRan() {
        Alert a = AlertFormatter.format(result(List.of(new Observation("INFY", 1500, 1501)), List.of(), List.of(), 1),
                1.5, List.of());

        assertThat(a.subject()).startsWith("No buy alerts");
        assertThat(a.body()).contains("No stock rose more than 1.5% between 9:30 → 9:45 IST.");
    }

    @Test
    void warnsWhenManyStocksCouldNotBePriced() {
        List<String> failures = Collections.nCopies(60, "X: HTTP 429");
        Alert a = AlertFormatter.format(result(List.of(), List.of(), failures, 500), 1.5, List.of());

        assertThat(a.body()).contains("⚠ 60 stocks could not be priced (12%)");
    }

    @Test
    void wholeNumberThresholdHasNoDecimal() {
        Alert a = AlertFormatter.format(result(List.of(), List.of(), List.of(), 0), 2.0, List.of());
        assertThat(a.body()).contains("more than 2% between");
    }
}
