package io.github.samramkumarqa.stockalert;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.samramkumarqa.stockalert.market.PriceSeries;
import io.github.samramkumarqa.stockalert.market.PriceSource;
import io.github.samramkumarqa.stockalert.rule.MomentumRule;
import io.github.samramkumarqa.stockalert.rule.Observation;

/** Fetches every symbol's bars and applies the rule. */
public final class Scanner {

    private static final Logger log = LoggerFactory.getLogger(Scanner.class);

    public record Result(
            LocalDate date,
            LocalTime baselineTime,
            LocalTime checkTime,
            int symbolsRequested,
            List<Observation> observations,
            List<Observation> signals,
            List<String> failures) {

        public double failureRate() {
            return symbolsRequested == 0 ? 0 : (double) failures.size() / symbolsRequested;
        }
    }

    private final PriceSource source;
    private final MomentumRule rule;
    private final ZoneId zone;
    private final int concurrency;

    public Scanner(PriceSource source, MomentumRule rule, ZoneId zone, int concurrency) {
        this.source = source;
        this.rule = rule;
        this.zone = zone;
        this.concurrency = Math.max(1, concurrency);
    }

    public Result scan(List<String> symbols, LocalDate date, LocalTime baselineTime, LocalTime checkTime)
            throws InterruptedException {
        Instant baseline = date.atTime(baselineTime).atZone(zone).toInstant();
        Instant check = date.atTime(checkTime).atZone(zone).toInstant();

        List<Observation> observations = Collections.synchronizedList(new ArrayList<>());
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        Semaphore permits = new Semaphore(concurrency);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (String symbol : symbols) {
                futures.add(pool.submit(() -> {
                    permits.acquire();
                    try {
                        PriceSeries series = source.intraday(symbol, date);
                        OptionalDouble p0 = series.priceAt(baseline);
                        OptionalDouble p1 = series.priceAt(check);
                        if (p0.isEmpty() || p1.isEmpty()) {
                            failures.add(symbol + ": no trades near " + (p0.isEmpty() ? baselineTime : checkTime));
                        } else {
                            observations.add(new Observation(symbol, p0.getAsDouble(), p1.getAsDouble()));
                        }
                    } catch (Exception e) {
                        failures.add(symbol + ": " + e.getMessage());
                    } finally {
                        permits.release();
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    log.warn("Unexpected task failure", e.getCause());
                }
            }
        }

        List<Observation> sorted = observations.stream()
                .sorted((a, b) -> a.symbol().compareTo(b.symbol())).toList();
        List<String> sortedFailures = failures.stream().sorted().toList();
        log.info("Scanned {} symbols: {} priced, {} failed, {} signals",
                symbols.size(), sorted.size(), sortedFailures.size(), rule.signals(sorted).size());
        return new Result(date, baselineTime, checkTime, symbols.size(), sorted, rule.signals(sorted), sortedFailures);
    }
}
