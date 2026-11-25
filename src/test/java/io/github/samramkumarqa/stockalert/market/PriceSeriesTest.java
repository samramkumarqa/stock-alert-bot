package io.github.samramkumarqa.stockalert.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

class PriceSeriesTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 29);

    private static Instant at(String hhmm) {
        return DAY.atTime(LocalTime.parse(hhmm)).atZone(IST).toInstant();
    }

    private static PriceSeries.Bar bar(String start, double close) {
        return new PriceSeries.Bar(at(start), close);
    }

    @Test
    void priceAtTimeIsCloseOfTheBarThatEndsThen() {
        PriceSeries s = new PriceSeries("X", List.of(
                bar("09:28", 99), bar("09:29", 100), bar("09:30", 105)));

        // The 09:29 bar closes at 09:30; the 09:30 bar is still open at 09:30.
        assertThat(s.priceAt(at("09:30"))).hasValue(100);
        assertThat(s.priceAt(at("09:31"))).hasValue(105);
    }

    @Test
    void usesLastTradeWhenStockIsQuietForAFewMinutes() {
        PriceSeries s = new PriceSeries("ILLIQUID", List.of(bar("09:26", 50)));
        assertThat(s.priceAt(at("09:30"))).hasValue(50);   // ended 09:27, 3 min stale: ok
    }

    @Test
    void refusesPricesThatAreTooStale() {
        PriceSeries s = new PriceSeries("ILLIQUID", List.of(bar("09:20", 50)));
        assertThat(s.priceAt(at("09:30"))).isEmpty();      // ended 09:21, 9 min stale
    }

    @Test
    void priceAtMarketOpenIsTheOpeningPrice() {
        // NSE opens at 09:15 with the pre-open auction price; nothing has closed yet.
        PriceSeries s = new PriceSeries("X", List.of(
                new PriceSeries.Bar(at("09:15"), 98.0, 99.5), bar("09:16", 100)));

        assertThat(s.priceAt(at("09:15"))).hasValue(98.0);   // open of the 09:15 bar
        assertThat(s.priceAt(at("09:16"))).hasValue(99.5);   // close of the 09:15 bar
    }

    @Test
    void emptyWhenNoBarStartsOrEndsNearT() {
        PriceSeries s = new PriceSeries("LATE", List.of(bar("09:17", 10)));   // first trade only at 09:17
        assertThat(s.priceAt(at("09:15"))).isEmpty();
    }

    @Test
    void closedBarWinsOverAnOpeningBarAtTheSameInstant() {
        PriceSeries s = new PriceSeries("X", List.of(
                new PriceSeries.Bar(at("09:29"), 101, 100), new PriceSeries.Bar(at("09:30"), 105, 106)));
        assertThat(s.priceAt(at("09:30"))).hasValue(100);
    }

    @Test
    void barsAreSortedEvenIfInputIsNot() {
        PriceSeries s = new PriceSeries("X", List.of(bar("09:44", 2), bar("09:29", 1)));
        assertThat(s.priceAt(at("09:30"))).hasValue(1);
        assertThat(s.priceAt(at("09:45"))).hasValue(2);
    }
}
