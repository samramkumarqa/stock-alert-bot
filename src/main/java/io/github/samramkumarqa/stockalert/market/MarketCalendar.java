package io.github.samramkumarqa.stockalert.market;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Knows which days NSE's equity segment is open. */
public final class MarketCalendar {

    private final Set<LocalDate> holidays;

    public MarketCalendar(Set<LocalDate> holidays) {
        this.holidays = Set.copyOf(holidays);
    }

    /** Loads holidays from the given file, or the bundled nse-holidays.txt. */
    public static MarketCalendar load(Optional<Path> file) {
        try (InputStream in = file.isPresent()
                ? Files.newInputStream(file.get())
                : MarketCalendar.class.getResourceAsStream("/nse-holidays.txt")) {
            if (in == null) {
                throw new IllegalStateException("nse-holidays.txt missing from classpath");
            }
            return new MarketCalendar(parse(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Set<LocalDate> parse(InputStream in) throws IOException {
        Set<LocalDate> dates = new TreeSet<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                dates.add(LocalDate.parse(trimmed.split("\\s+", 2)[0]));
            }
        }
        return dates;
    }

    public boolean isTradingDay(LocalDate date) {
        DayOfWeek d = date.getDayOfWeek();
        return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY && !holidays.contains(date);
    }

    public boolean coversYear(int year) {
        return holidays.stream().anyMatch(d -> d.getYear() == year);
    }
}
