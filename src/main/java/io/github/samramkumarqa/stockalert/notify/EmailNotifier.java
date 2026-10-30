package io.github.samramkumarqa.stockalert.notify;

import java.nio.file.Path;
import java.util.Properties;

import jakarta.activation.DataHandler;
import jakarta.activation.FileDataSource;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

/** Sends the alert by SMTP (Gmail app password by default) with the CSV report attached. */
public final class EmailNotifier implements Notifier {

    private final Session session;
    private final String from;
    private final String to;

    public EmailNotifier(String host, int port, String user, String password, String to) {
        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.connectiontimeout", "15000");
        props.put("mail.smtp.timeout", "15000");
        this.session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, password);
            }
        });
        this.from = user;
        this.to = to;
    }

    @Override
    public String name() {
        return "email";
    }

    @Override
    public void send(Alert alert) throws MessagingException {
        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(from));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        msg.setSubject(alert.subject(), "UTF-8");

        Multipart parts = new MimeMultipart();
        MimeBodyPart text = new MimeBodyPart();
        text.setText(alert.body(), "UTF-8");
        parts.addBodyPart(text);
        for (Path file : alert.attachments()) {
            MimeBodyPart att = new MimeBodyPart();
            att.setDataHandler(new DataHandler(new FileDataSource(file.toFile())));
            att.setFileName(file.getFileName().toString());
            parts.addBodyPart(att);
        }
        msg.setContent(parts);
        Transport.send(msg);
    }
}
