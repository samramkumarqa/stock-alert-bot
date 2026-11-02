package io.github.samramkumarqa.stockalert;

import static io.github.samramkumarqa.stockalert.StubServer.fixture;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.samramkumarqa.stockalert.config.AppConfig;

/** Live-mode timing behaviour, with Yahoo and Telegram stubbed on localhost. */
class AppTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);

    private static Clock at(LocalTime t) {
        return Clock.fixed(MONDAY.atTime(t).atZone(IST).toInstant(), IST);
    }

    private static Map<String, String> env(StubServer stub, Path dir) {
        Map<String, String> env = new HashMap<>();
        env.put("PRICE_SOURCE", "yahoo-http");
        env.put("YAHOO_BASE_URL", stub.baseUrl());
        env.put("UNIVERSE_URLS", stub.baseUrl() + "/list.csv");
        env.put("UNIVERSE_FILE", dir.resolve("data/n500.csv").toString());
        env.put("REPORT_DIR", dir.resolve("reports").toString());
        env.put("TELEGRAM_BOT_TOKEN", "T");
        env.put("TELEGRAM_CHAT_ID", "1");
        env.put("TELEGRAM_BASE_URL", stub.baseUrl());
        env.put("FETCH_DELAY_SECONDS", "0");
        return env;
    }

    @Test
    void lateStartSendsAWarningInsteadOfAStaleBuyList(@TempDir Path dir) throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.enqueue(200, "{\"ok\":true}");   // only the Telegram warning should be requested

            int code = App.run(new String[] {"live"}, AppConfig.from(env(stub, dir)), at(LocalTime.of(17, 7)));

            assertThat(code).isZero();
            assertThat(stub.requests()).singleElement().satisfies(r -> {
                assertThat(r.pathAndQuery()).endsWith("/sendMessage");
                assertThat(URLDecoder.decode(r.body(), StandardCharsets.UTF_8))
                        .contains("started late").contains("17:07");
            });
            assertThat(dir.resolve("reports/alert-sent-2026-11-02")).doesNotExist();
        }
    }

    @Test
    void onTimeRunAlertsAndLeavesTheOncePerDayMarker(@TempDir Path dir) throws Exception {
        String csv = "Company Name,Industry,Symbol,Series,ISIN Code\n" + IntStream.range(0, 50)
                .mapToObj(i -> "Co,Ind,S" + i + ",EQ,I").collect(Collectors.joining("\n"));
        try (StubServer stub = new StubServer()) {
            stub.enqueue(200, csv);
            // The fixture is dated 2026-10-29, so these symbols have no bars for 2 Nov: the run still
            // completes, reports them unpriced, and sends the (empty) summary to Telegram.
            for (int i = 0; i < 50; i++) {
                stub.enqueue(200, fixture("yahoo-chart-reliance.json"));
            }
            stub.enqueue(200, "{\"ok\":true}");

            int code = App.run(new String[] {"live"}, AppConfig.from(env(stub, dir)), at(LocalTime.of(9, 46)));

            assertThat(code).isEqualTo(2);   // everything unpriced -> red run, but the alert path still ran
            assertThat(Files.readString(dir.resolve("reports/alert-sent-2026-11-02"))).isNotBlank();
        }
    }

    @Test
    void forceOverridesTheLateGuard(@TempDir Path dir) throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.enqueue(500, "universe down");   // proves the scan was attempted
            stub.enqueue(200, "{\"ok\":true}");   // failure notice to Telegram

            int code = App.run(new String[] {"live", "--force"}, AppConfig.from(env(stub, dir)),
                    at(LocalTime.of(17, 7)));

            assertThat(code).isEqualTo(1);
            assertThat(stub.requests().get(0).pathAndQuery()).isEqualTo("/list.csv");
        }
    }
}
