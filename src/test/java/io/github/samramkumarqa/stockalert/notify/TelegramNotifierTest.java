package io.github.samramkumarqa.stockalert.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.samramkumarqa.stockalert.StubServer;

class TelegramNotifierTest {

    @Test
    void postsMessageToBotEndpoint() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.enqueue(200, "{\"ok\":true}");
            new TelegramNotifier(HttpClient.newHttpClient(), stub.baseUrl(), "123:ABC", "987")
                    .send(new Alert("📈 BUY ALERT", "1. SBIN +1.62%", List.of()));

            StubServer.Request req = stub.requests().get(0);
            assertThat(req.method()).isEqualTo("POST");
            assertThat(req.pathAndQuery()).isEqualTo("/bot123:ABC/sendMessage");
            String body = URLDecoder.decode(req.body(), StandardCharsets.UTF_8);
            assertThat(body).contains("chat_id=987").contains("📈 BUY ALERT\n\n1. SBIN +1.62%");
        }
    }

    @Test
    void errorMessageNeverLeaksTheToken() {
        try (StubServer stub = new StubServer()) {
            stub.enqueue(401, "{\"ok\":false,\"description\":\"Unauthorized\"}");
            TelegramNotifier n = new TelegramNotifier(HttpClient.newHttpClient(), stub.baseUrl(), "SECRET-TOKEN", "1");

            assertThatThrownBy(() -> n.send(new Alert("s", "b", List.of())))
                    .hasMessageContaining("401")
                    .hasMessageNotContaining("SECRET-TOKEN");
        }
    }

    @Test
    void longAlertsAreSplitOnLineBoundaries() {
        String line = "x".repeat(30);
        String text = String.join("\n", java.util.Collections.nCopies(300, line));   // ~9300 chars

        List<String> parts = TelegramNotifier.split(text, TelegramNotifier.MAX_LEN);

        assertThat(parts).hasSizeGreaterThan(1).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(4000));
        assertThat(String.join("\n", parts)).isEqualTo(text);
        assertThat(parts).allSatisfy(p -> assertThat(p.split("\n")).allSatisfy(l -> assertThat(l).hasSize(30)));
    }

    @Test
    void shortAlertIsOneMessage() {
        assertThat(TelegramNotifier.split("a\nb", 4000)).containsExactly("a\nb");
    }
}
