package io.github.samramkumarqa.stockalert.notify;

import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.samramkumarqa.stockalert.Scanner;
import io.github.samramkumarqa.stockalert.rule.Observation;

/** Builds the human-readable alert text from a scan result. */
public final class AlertFormatter {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("H:mm", Locale.ENGLISH);
    /** Warn in the alert when more than this share of symbols could not be priced. */
    static final double FAILURE_WARN_RATE = 0.10;

    private AlertFormatter() {
    }

    public static Alert format(Scanner.Result r, double thresholdPct, List<Path> attachments) {
        String window = r.baselineTime().format(HM) + " → " + r.checkTime().format(HM) + " IST";
        String pct = trim(thresholdPct);
        List<String> lines = new ArrayList<>();
        String subject;

        if (r.signals().isEmpty()) {
            subject = "No buy alerts · " + r.date().format(DAY);
            lines.add("No stock rose more than " + pct + "% between " + window + ".");
        } else {
            subject = "📈 BUY ALERT · " + r.signals().size() + " stock" + (r.signals().size() == 1 ? "" : "s")
                    + " · " + r.date().format(DAY);
            lines.add("Up more than " + pct + "% from " + window + ":");
            lines.add("");
            int i = 1;
            for (Observation o : r.signals()) {
                lines.add(String.format(Locale.ENGLISH, "%d. %s  +%.2f%%  ₹%,.2f → ₹%,.2f",
                        i++, o.symbol(), o.changePct(), o.baselinePrice(), o.checkPrice()));
            }
        }
        lines.add("");
        lines.add(String.format(Locale.ENGLISH, "Checked %d of %d stocks.", r.observations().size(), r.symbolsRequested()));
        if (r.failureRate() > FAILURE_WARN_RATE) {
            lines.add(String.format(Locale.ENGLISH,
                    "⚠ %d stocks could not be priced (%.0f%%). Results may be incomplete; see the run log.",
                    r.failures().size(), r.failureRate() * 100));
        }
        lines.add("Alert only, no orders were placed. Data: Yahoo Finance (NSE).");
        return new Alert(subject, String.join("\n", lines), attachments);
    }

    public static Alert failure(String what, Throwable error) {
        return new Alert("⚠ stock-alert-bot failed", what + "\n\n" + error, List.of());
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
