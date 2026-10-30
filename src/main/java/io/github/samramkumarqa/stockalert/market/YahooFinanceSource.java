package io.github.samramkumarqa.stockalert.market;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads NSE 1-minute bars from Yahoo Finance's public chart endpoint
 * ({@code SYMBOL.NS}). No API key or login is needed. Yahoo lists NSE data as real-time.
 * <p>
 * The endpoint is unofficial, so requests are throttled by the caller and retried here
 * with backoff on 429/5xx.
 */
public final class YahooFinanceSource implements PriceSource {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceSource.class);
    private static final int MAX_ATTEMPTS = 3;

    private final HttpClient http;
    private final String baseUrl;
    private final ZoneId zone;
    private final Duration backoff;

    public YahooFinanceSource(HttpClient http, String baseUrl, ZoneId zone) {
        this(http, baseUrl, zone, Duration.ofSeconds(2));
    }

    YahooFinanceSource(HttpClient http, String baseUrl, ZoneId zone, Duration backoff) {
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.zone = zone;
        this.backoff = backoff;
    }

    public static String yahooTicker(String nseSymbol) {
        return nseSymbol + ".NS";
    }

    URI chartUri(String nseSymbol, LocalDate date) {
        long from = date.atTime(LocalTime.of(9, 0)).atZone(zone).toEpochSecond();
        long to = date.atTime(LocalTime.of(15, 45)).atZone(zone).toEpochSecond();
        String ticker = URLEncoder.encode(yahooTicker(nseSymbol), StandardCharsets.UTF_8);
        return URI.create(baseUrl + "/v8/finance/chart/" + ticker
                + "?interval=1m&period1=" + from + "&period2=" + to + "&includePrePost=false");
    }

    @Override
    public PriceSeries intraday(String nseSymbol, LocalDate date) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(chartUri(nseSymbol, date))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", Universe.BROWSER_UA)
                .header("Accept", "application/json")
                .GET()
                .build();

        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                int code = res.statusCode();
                if (code == 200) {
                    return YahooChartParser.parse(nseSymbol, res.body());
                }
                if (code == 404) {
                    throw new IOException("Yahoo has no data for " + yahooTicker(nseSymbol) + " (404)");
                }
                last = new IOException("HTTP " + code + " for " + yahooTicker(nseSymbol));
                if (code != 429 && code < 500) {
                    throw last;
                }
            } catch (java.net.http.HttpTimeoutException e) {
                last = e;
            }
            if (attempt < MAX_ATTEMPTS) {
                long wait = backoff.toMillis() * attempt;
                log.debug("Retrying {} in {} ms ({})", nseSymbol, wait, last.getMessage());
                Thread.sleep(wait);
            }
        }
        throw last;
    }
}
