package io.github.samramkumarqa.stockalert.market;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The list of NSE symbols to scan (Nifty 500 by default).
 * Downloads the official constituents CSV, caches it, and falls back to the
 * cache when the download fails.
 */
public final class Universe {

    private static final Logger log = LoggerFactory.getLogger(Universe.class);
    static final String BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/130.0 Safari/537.36";

    private Universe() {
    }

    public static List<String> load(String urls, Path cache, HttpClient http) {
        for (String url : urls.split(",")) {
            if (url.isBlank()) {
                continue;
            }
            try {
                String csv = download(url.strip(), http);
                List<String> symbols = parseCsv(csv);
                if (symbols.size() >= 50) {
                    Files.createDirectories(cache.toAbsolutePath().getParent());
                    Files.writeString(cache, csv, StandardCharsets.UTF_8);
                    log.info("Loaded {} symbols from {}", symbols.size(), url);
                    return symbols;
                }
                log.warn("Only {} symbols parsed from {}, ignoring", symbols.size(), url);
            } catch (IOException | RuntimeException e) {
                log.warn("Could not download universe from {}: {}", url, e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        if (Files.exists(cache)) {
            try {
                List<String> symbols = parseCsv(Files.readString(cache, StandardCharsets.UTF_8));
                log.info("Using cached universe {} ({} symbols)", cache, symbols.size());
                return symbols;
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read universe cache " + cache, e);
            }
        }
        throw new IllegalStateException(
                "No stock list available: download failed and no cache at " + cache
                + ". Set UNIVERSE_FILE to a Nifty CSV, or UNIVERSE_URLS.");
    }

    private static String download(String url, HttpClient http) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "text/csv,*/*")
                .GET()
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("HTTP " + res.statusCode());
        }
        return res.body();
    }

    /**
     * Parses an NSE index constituents CSV
     * (Company Name,Industry,Symbol,Series,ISIN Code). Keeps EQ-series rows only.
     */
    public static List<String> parseCsv(String csv) {
        String[] lines = csv.replace("﻿", "").split("\\R");
        if (lines.length == 0) {
            return List.of();
        }
        List<String> header = Arrays.stream(lines[0].split(",")).map(String::strip).toList();
        int symbolCol = header.indexOf("Symbol");
        int seriesCol = header.indexOf("Series");
        if (symbolCol < 0) {
            throw new IllegalArgumentException("CSV has no 'Symbol' column: " + lines[0]);
        }
        Set<String> symbols = new LinkedHashSet<>();
        for (int i = 1; i < lines.length; i++) {
            String[] cols = lines[i].split(",", -1);
            if (cols.length <= symbolCol) {
                continue;
            }
            String series = seriesCol >= 0 && cols.length > seriesCol ? cols[seriesCol].strip() : "EQ";
            String symbol = cols[symbolCol].strip();
            if (!symbol.isEmpty() && "EQ".equalsIgnoreCase(series)) {
                symbols.add(symbol);
            }
        }
        return new ArrayList<>(symbols);
    }
}
