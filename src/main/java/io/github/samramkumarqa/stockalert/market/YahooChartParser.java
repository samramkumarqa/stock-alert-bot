package io.github.samramkumarqa.stockalert.market;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Turns a Yahoo Finance {@code /v8/finance/chart} response into a {@link PriceSeries}. */
public final class YahooChartParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private YahooChartParser() {
    }

    public static PriceSeries parse(String symbol, String body) throws IOException {
        JsonNode chart = JSON.readTree(body).path("chart");
        JsonNode error = chart.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new IOException("Yahoo error for " + symbol + ": "
                    + error.path("code").asText() + " " + error.path("description").asText());
        }
        JsonNode result = chart.path("result").path(0);
        if (result.isMissingNode()) {
            throw new IOException("Yahoo returned no result for " + symbol);
        }
        JsonNode timestamps = result.path("timestamp");
        JsonNode quote = result.path("indicators").path("quote").path(0);
        JsonNode closes = quote.path("close");
        JsonNode opens = quote.path("open");

        List<PriceSeries.Bar> bars = new ArrayList<>();
        for (int i = 0; i < timestamps.size() && i < closes.size(); i++) {
            JsonNode close = closes.get(i);
            // Yahoo leaves nulls for minutes with no trades.
            if (close == null || close.isNull() || close.asDouble() <= 0) {
                continue;
            }
            JsonNode open = opens.get(i);
            double o = open == null || open.isNull() || open.asDouble() <= 0 ? close.asDouble() : open.asDouble();
            bars.add(new PriceSeries.Bar(Instant.ofEpochSecond(timestamps.get(i).asLong()), o, close.asDouble()));
        }
        return new PriceSeries(symbol, bars);
    }
}
