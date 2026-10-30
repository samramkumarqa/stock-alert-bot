package io.github.samramkumarqa.stockalert.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarketCalendarTest {

    private final MarketCalendar bundled = MarketCalendar.load(Optional.empty());

    @Test
    void weekdaysAreTradingDays() {
        assertThat(bundled.isTradingDay(LocalDate.of(2026, 10, 29))).isTrue();   // Thursday
    }

    @Test
    void weekendsAreClosed() {
        assertThat(bundled.isTradingDay(LocalDate.of(2026, 10, 31))).isFalse();  // Saturday
        assertThat(bundled.isTradingDay(LocalDate.of(2026, 11, 1))).isFalse();   // Sunday
    }

    @Test
    void bundledHolidaysAreClosed() {
        assertThat(bundled.isTradingDay(LocalDate.of(2026, 11, 10))).isFalse();  // Diwali Balipratipada
        assertThat(bundled.isTradingDay(LocalDate.of(2026, 12, 25))).isFalse();  // Christmas
        assertThat(bundled.coversYear(2026)).isTrue();
        assertThat(bundled.coversYear(2031)).isFalse();
    }

    @Test
    void customHolidayFileOverridesBundledList(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("h.txt");
        Files.writeString(file, "# comment\n\n2027-01-26  Republic Day\n");
        MarketCalendar cal = MarketCalendar.load(Optional.of(file));

        assertThat(cal.isTradingDay(LocalDate.of(2027, 1, 26))).isFalse();
        assertThat(cal.isTradingDay(LocalDate.of(2026, 12, 25))).isTrue();      // not in the custom file
    }
}
