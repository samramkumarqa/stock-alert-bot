package io.github.samramkumarqa.stockalert.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.samramkumarqa.stockalert.Scanner;
import io.github.samramkumarqa.stockalert.rule.Observation;

class CsvReportTest {

    @Test
    void writesAllObservationsSortedByGainAndMarksAlerts(@TempDir Path dir) throws Exception {
        Observation up = new Observation("SBIN", 800, 813);
        Observation flat = new Observation("INFY", 1500, 1500);
        Scanner.Result r = new Scanner.Result(LocalDate.of(2026, 10, 29), LocalTime.of(9, 30), LocalTime.of(9, 45),
                2, List.of(flat, up), List.of(up), List.of());

        Path file = CsvReport.write(r, dir.resolve("reports"));

        assertThat(file.getFileName().toString()).isEqualTo("scan-2026-10-29.csv");
        List<String> lines = Files.readAllLines(file);
        assertThat(lines.get(0)).endsWith("symbol,price_09:30,price_09:45,change_pct,buy_alert");
        assertThat(lines.get(1)).isEqualTo("SBIN,800.00,813.00,1.625,YES");
        assertThat(lines.get(2)).isEqualTo("INFY,1500.00,1500.00,0.000,");
    }
}
