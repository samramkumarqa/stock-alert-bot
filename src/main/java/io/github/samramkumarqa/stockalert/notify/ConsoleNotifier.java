package io.github.samramkumarqa.stockalert.notify;

import java.io.PrintStream;

/** Prints alerts to stdout; always on, so the GitHub Actions log shows the result. */
public final class ConsoleNotifier implements Notifier {

    private final PrintStream out;

    public ConsoleNotifier(PrintStream out) {
        this.out = out;
    }

    @Override
    public String name() {
        return "console";
    }

    @Override
    public void send(Alert alert) {
        out.println("==== " + alert.subject() + " ====");
        out.println(alert.body());
        alert.attachments().forEach(p -> out.println("[attachment] " + p));
    }
}
