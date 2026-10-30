package io.github.samramkumarqa.stockalert.notify;

import java.nio.file.Path;
import java.util.List;

/** A message to deliver: short subject, plain-text body, optional file attachments. */
public record Alert(String subject, String body, List<Path> attachments) {

    public Alert {
        attachments = List.copyOf(attachments);
    }
}
