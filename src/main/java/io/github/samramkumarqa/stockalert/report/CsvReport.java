package io.github.samramkumarqa.stockalert.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.samramkumarqa.stockalert.Scanner;
import io.github.samramkumarqa.stockalert.rule.MomentumRule;
import io.github.samramkumarqa.stockalert.rule.Observation;

/**
 * Writes every observation of a scan to {@code reports/scan-YYYY-MM-DD.csv} (opens in Excel).
 * {@code above_threshold} marks every qualifying stock; {@code buy_alert} marks the top N that were alerted.
 */
public final class CsvReport {

    private CsvReport() {
    }

    public static Path write(Scanner.Result r, int maxAlerts, Path dir) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("scan-" + r.date() + ".csv");
        Set<String> qualified = r.signals().stream().map(Observation::symbol).collect(Collectors.toSet());
        Set<String> alerted = MomentumRule.top(r.signals(), maxAlerts).stream()
                .map(Observation::symbol).collect(Collectors.toSet());

        List<String> rows = new ArrayList<>();
        rows.add("symbol,price_" + r.baselineTime() + ",price_" + r.checkTime()
                + ",change_pct,above_threshold,buy_alert");
        r.observations().stream()
                .sorted((a, b) -> Double.compare(b.changePct(), a.changePct()))
                .forEach(o -> rows.add(String.format(Locale.ENGLISH, "%s,%.2f,%.2f,%.3f,%s,%s",
                        o.symbol(), o.baselinePrice(), o.checkPrice(), o.changePct(),
                        qualified.contains(o.symbol()) ? "YES" : "",
                        alerted.contains(o.symbol()) ? "YES" : "")));
        // Excel opens UTF-8 correctly only with a BOM.
        Files.writeString(file, "﻿" + String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
        return file;
    }
}
