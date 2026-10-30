package io.github.samramkumarqa.stockalert.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the Java side of the yfinance bridge without Python or network:
 * a small shell script stands in for fetch_bars.py and writes the CSV it would produce.
 */
class YfinanceSourceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 29);
    private static final long T0929 = DAY.atTime(9, 29).atZone(IST).toEpochSecond();
    private static final long T0944 = DAY.atTime(9, 44).atZone(IST).toEpochSecond();

    /** A fake fetcher: copies a canned CSV to the --out path and exits with the given code. */
    private static Path fakeFetcher(Path dir, String csv, int exitCode) throws IOException {
        Path canned = dir.resolve("canned.csv");
        Files.writeString(canned, csv);
        Path script = dir.resolve("fake_fetch.sh");
        Files.writeString(script, """
                while [ $# -gt 0 ]; do
                  case "$1" in --out) out="$2"; shift;; esac; shift
                done
                echo "fetched from fake"
                [ %d -eq 0 ] && cp "%s" "$out"
                exit %d
                """.formatted(exitCode, canned, exitCode));
        return script;
    }

    @Test
    void servesBarsWrittenByTheFetcher(@TempDir Path dir) throws Exception {
        String csv = "symbol,epoch,close\n"
                + "SBIN," + T0929 + ",800.0\n"
                + "SBIN," + T0944 + ",813.0\n"
                + "M&M," + T0929 + ",3000.0\n";
        YfinanceSource src = new YfinanceSource("sh", fakeFetcher(dir, csv, 0), dir.resolve("work"),
                Duration.ofSeconds(30));

        src.prefetch(List.of("SBIN", "M&M", "INFY"), DAY);

        PriceSeries sbin = src.intraday("SBIN", DAY);
        assertThat(sbin.priceAt(DAY.atTime(9, 30).atZone(IST).toInstant())).hasValue(800.0);
        assertThat(sbin.priceAt(DAY.atTime(9, 45).atZone(IST).toInstant())).hasValue(813.0);
        assertThat(src.intraday("M&M", DAY).bars()).hasSize(1);
        assertThatThrownBy(() -> src.intraday("INFY", DAY)).hasMessageContaining("no bars for INFY.NS");
        assertThat(Files.readAllLines(dir.resolve("work/symbols-2026-10-29.txt"))).containsExactly("SBIN", "M&M", "INFY");
    }

    @Test
    void reportsFetcherFailureWithItsOutput(@TempDir Path dir) throws Exception {
        YfinanceSource src = new YfinanceSource("sh", fakeFetcher(dir, "", 1), dir.resolve("work"),
                Duration.ofSeconds(30));

        assertThatThrownBy(() -> src.prefetch(List.of("SBIN"), DAY))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exit 1")
                .hasMessageContaining("fetched from fake")
                .hasMessageContaining("requirements.txt");
    }

    @Test
    void explainsMissingScript(@TempDir Path dir) {
        YfinanceSource src = new YfinanceSource("python3", dir.resolve("nope.py"), dir, Duration.ofSeconds(5));
        assertThatThrownBy(() -> src.prefetch(List.of("SBIN"), DAY)).hasMessageContaining("FETCH_SCRIPT");
    }

    @Test
    void refusesToServeAnotherDay(@TempDir Path dir) throws Exception {
        YfinanceSource src = new YfinanceSource("sh", fakeFetcher(dir, "symbol,epoch,close\n", 0), dir,
                Duration.ofSeconds(30));
        src.prefetch(List.of(), DAY);
        assertThatThrownBy(() -> src.intraday("SBIN", DAY.plusDays(1))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void parsesCsvIgnoringHeaderAndBlankLines() {
        Map<String, List<PriceSeries.Bar>> m = YfinanceSource.parseCsv(List.of(
                "symbol,epoch,close", "", "BAJAJ-AUTO,1761710340,9000.5", "BAJAJ-AUTO,1761710400,9010.25"));
        assertThat(m).containsOnlyKeys("BAJAJ-AUTO");
        assertThat(m.get("BAJAJ-AUTO")).extracting(PriceSeries.Bar::close).containsExactly(9000.5, 9010.25);
    }
}
