package io.github.samramkumarqa.stockalert.market;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gets Yahoo Finance bars through {@code scripts/fetch_bars.py} (the yfinance library).
 * <p>
 * Yahoo answers HTTP 429 to plain HTTP clients from cloud machines such as GitHub runners;
 * yfinance looks like a real browser and is accepted. The script runs once per scan
 * ({@link #prefetch}) and writes {@code symbol,epoch,close} rows that this class serves.
 */
public final class YfinanceSource implements PriceSource {

    private static final Logger log = LoggerFactory.getLogger(YfinanceSource.class);

    private final String python;
    private final Path script;
    private final Path workDir;
    private final Duration timeout;
    private Map<String, List<PriceSeries.Bar>> bars = Map.of();
    private LocalDate loadedFor;

    public YfinanceSource(String python, Path script, Path workDir, Duration timeout) {
        this.python = python;
        this.script = script;
        this.workDir = workDir;
        this.timeout = timeout;
    }

    @Override
    public void prefetch(List<String> nseSymbols, LocalDate date) throws IOException, InterruptedException {
        if (!Files.isRegularFile(script)) {
            throw new IOException("Fetcher script not found: " + script.toAbsolutePath()
                    + " (run from the repo root or set FETCH_SCRIPT)");
        }
        Files.createDirectories(workDir);
        Path symbols = workDir.resolve("symbols-" + date + ".txt");
        Path out = workDir.resolve("bars-" + date + ".csv");
        Files.write(symbols, nseSymbols, StandardCharsets.UTF_8);
        Files.deleteIfExists(out);

        Process p = new ProcessBuilder(python, script.toString(),
                "--symbols", symbols.toString(), "--date", date.toString(), "--out", out.toString())
                .redirectErrorStream(true)
                .start();
        Deque<String> tail = new ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                log.info("[fetch_bars] {}", line);
                tail.addLast(line);
                if (tail.size() > 5) {
                    tail.removeFirst();
                }
            }
        }
        if (!p.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("fetch_bars.py timed out after " + timeout.toMinutes() + " min");
        }
        if (p.exitValue() != 0 || !Files.exists(out)) {
            throw new IOException("fetch_bars.py failed (exit " + p.exitValue() + "): " + String.join(" | ", tail)
                    + ". Is yfinance installed? pip install -r scripts/requirements.txt");
        }
        bars = parseCsv(Files.readAllLines(out, StandardCharsets.UTF_8));
        loadedFor = date;
        log.info("Loaded bars for {} of {} symbols", bars.size(), nseSymbols.size());
    }

    @Override
    public PriceSeries intraday(String nseSymbol, LocalDate date) throws IOException {
        if (!date.equals(loadedFor)) {
            throw new IllegalStateException("prefetch() was not called for " + date);
        }
        List<PriceSeries.Bar> b = bars.get(nseSymbol);
        if (b == null || b.isEmpty()) {
            throw new IOException("Yahoo returned no bars for " + YahooFinanceSource.yahooTicker(nseSymbol));
        }
        return new PriceSeries(nseSymbol, b);
    }

    /** Parses {@code symbol,epoch,close} lines (header optional). */
    static Map<String, List<PriceSeries.Bar>> parseCsv(List<String> lines) {
        Map<String, List<PriceSeries.Bar>> map = new HashMap<>();
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("symbol,")) {
                continue;
            }
            // Symbols never contain commas, so split from the right is safe.
            int c2 = line.lastIndexOf(',');
            int c1 = line.lastIndexOf(',', c2 - 1);
            if (c1 <= 0 || c2 <= c1) {
                continue;
            }
            String symbol = line.substring(0, c1);
            long epoch = Long.parseLong(line.substring(c1 + 1, c2).strip());
            double close = Double.parseDouble(line.substring(c2 + 1).strip());
            map.computeIfAbsent(symbol, k -> new ArrayList<>())
                    .add(new PriceSeries.Bar(Instant.ofEpochSecond(epoch), close));
        }
        return map;
    }
}
