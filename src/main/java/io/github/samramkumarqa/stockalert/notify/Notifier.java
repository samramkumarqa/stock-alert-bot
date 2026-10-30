package io.github.samramkumarqa.stockalert.notify;

/** A delivery channel for alerts. */
public interface Notifier {

    String name();

    void send(Alert alert) throws Exception;
}
