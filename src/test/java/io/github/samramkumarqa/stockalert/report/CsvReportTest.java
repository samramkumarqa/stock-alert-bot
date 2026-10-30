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
    void writesAllObservationsSortedByGainAndMarksQualifiedAndAlerted(@TempDir Path dir) throws Exception {
        Observation big = new Observation("TATAMOTORS", 700, 720);   // +2.857%  alerted (top 1)
        Observation up = new Observation("SBIN", 800, 813);          // +1.625%  qualified, beyond top 1
        Observation flat = new Observation("INFY", 1500, 1500);
        Scanner.Result r = new Scanner.Result(LocalDate.of(2026, 10, 29), LocalTime.of(9, 30), LocalTime.of(9, 45),
                3, List.of(flat, up, big), List.of(big, up), List.of());

        Path file = CsvReport.write(r, 1, dir.resolve("reports"));

        assertThat(file.getFileName().toString()).isEqualTo("scan-2026-10-29.csv");
        List<String> lines = Files.readAllLines(file);
        assertThat(lines.get(0)).endsWith("symbol,price_09:30,price_09:45,change_pct,above_threshold,buy_alert");
        assertThat(lines.get(1)).isEqualTo("TATAMOTORS,700.00,720.00,2.857,YES,YES");
        assertThat(lines.get(2)).isEqualTo("SBIN,800.00,813.00,1.625,YES,");
        assertThat(lines.get(3)).isEqualTo("INFY,1500.00,1500.00,0.000,,");
    }
}
