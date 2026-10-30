package io.github.samramkumarqa.stockalert.notify;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends alerts through a Telegram bot (free, instant phone push).
 * Setup: create a bot with @BotFather, send it any message, then read your chat id from
 * {@code https://api.telegram.org/bot<TOKEN>/getUpdates}.
 */
public final class TelegramNotifier implements Notifier {

    /** Telegram's hard limit is 4096 characters per message. */
    static final int MAX_LEN = 4000;

    private final HttpClient http;
    private final String baseUrl;
    private final String token;
    private final String chatId;

    public TelegramNotifier(HttpClient http, String baseUrl, String token, String chatId) {
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.chatId = chatId;
    }

    @Override
    public String name() {
        return "telegram";
    }

    @Override
    public void send(Alert alert) throws IOException, InterruptedException {
        for (String part : split(alert.subject() + "\n\n" + alert.body(), MAX_LEN)) {
            String form = "chat_id=" + enc(chatId) + "&text=" + enc(part) + "&disable_web_page_preview=true";
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/bot" + token + "/sendMessage"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                // Never include the token in the error message.
                throw new IOException("Telegram sendMessage failed: HTTP " + res.statusCode() + " " + res.body());
            }
        }
    }

    /** Splits on line boundaries so no stock row is cut in half. */
    static List<String> split(String text, int max) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            while (line.length() > max) {
                if (!cur.isEmpty()) {
                    parts.add(cur.toString());
                    cur.setLength(0);
                }
                parts.add(line.substring(0, max));
                line = line.substring(max);
            }
            if (cur.length() + line.length() + 1 > max && !cur.isEmpty()) {
                parts.add(cur.toString());
                cur.setLength(0);
            }
            if (!cur.isEmpty()) {
                cur.append('\n');
            }
            cur.append(line);
        }
        if (!cur.isEmpty()) {
            parts.add(cur.toString());
        }
        return parts;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
