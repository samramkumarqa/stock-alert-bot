package io.github.samramkumarqa.stockalert.market;

import static io.github.samramkumarqa.stockalert.StubServer.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.samramkumarqa.stockalert.StubServer;

class YahooFinanceSourceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 29);

    private final StubServer stub = new StubServer();
    private final YahooFinanceSource source =
            new YahooFinanceSource(HttpClient.newHttpClient(), stub.baseUrl(), IST, Duration.ofMillis(10));

    @AfterEach
    void stop() {
        stub.close();
    }

    @Test
    void readsBaselineAndCheckPricesFromOneMinuteBars() throws Exception {
        stub.enqueue(200, fixture("yahoo-chart-reliance.json"));

        PriceSeries s = source.intraday("RELIANCE", DAY);

        assertThat(s.priceAt(DAY.atTime(LocalTime.of(9, 30)).atZone(IST).toInstant())).hasValue(1400.00);
        assertThat(s.priceAt(DAY.atTime(LocalTime.of(9, 45)).atZone(IST).toInstant())).hasValue(1428.70);
    }

    @Test
    void skipsMinutesWithNoTrades() throws Exception {
        stub.enqueue(200, fixture("yahoo-chart-reliance.json"));
        PriceSeries s = source.intraday("RELIANCE", DAY);
        assertThat(s.bars()).hasSize(35);   // 36 minutes in the fixture, 09:37 is null
    }

    @Test
    void requestsNseTickerWithOneMinuteIntervalForThatDay() throws Exception {
        stub.enqueue(200, fixture("yahoo-chart-reliance.json"));
        source.intraday("M&M", DAY);

        String path = stub.requests().get(0).pathAndQuery();
        assertThat(path).startsWith("/v8/finance/chart/M%26M.NS?interval=1m");
        long from = DAY.atTime(9, 0).atZone(IST).toEpochSecond();
        assertThat(path).contains("period1=" + from);
    }

    @Test
    void retriesWhenRateLimited() throws Exception {
        stub.enqueue(429, "Too Many Requests").enqueue(200, fixture("yahoo-chart-reliance.json"));

        assertThat(source.intraday("RELIANCE", DAY).bars()).isNotEmpty();
        assertThat(stub.requests()).hasSize(2);
    }

    @Test
    void givesUpAfterThreeServerErrors() {
        stub.enqueue(503, "").enqueue(503, "").enqueue(503, "");
        assertThatThrownBy(() -> source.intraday("RELIANCE", DAY))
                .isInstanceOf(IOException.class).hasMessageContaining("503");
        assertThat(stub.requests()).hasSize(3);
    }

    @Test
    void doesNotRetryUnknownSymbol() {
        stub.enqueue(404, fixture("yahoo-chart-error.json"));
        assertThatThrownBy(() -> source.intraday("NOSUCH", DAY)).hasMessageContaining("404");
        assertThat(stub.requests()).hasSize(1);
    }

    @Test
    void surfacesYahooErrorPayload() {
        assertThatThrownBy(() -> YahooChartParser.parse("NOSUCH", fixture("yahoo-chart-error.json")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("delisted");
    }
}
