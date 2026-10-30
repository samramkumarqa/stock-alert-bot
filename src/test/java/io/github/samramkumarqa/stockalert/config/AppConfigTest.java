package io.github.samramkumarqa.stockalert.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AppConfigTest {

    @Test
    void defaultsMatchTheStrategy() {
        AppConfig c = AppConfig.from(Map.of());

        assertThat(c.baselineTime()).isEqualTo(LocalTime.of(9, 30));
        assertThat(c.checkTime()).isEqualTo(LocalTime.of(9, 45));
        assertThat(c.thresholdPct()).isEqualTo(1.5);
        assertThat(c.maxAlerts()).isEqualTo(5);
        assertThat(c.zone().getId()).isEqualTo("Asia/Kolkata");
        assertThat(c.telegramEnabled()).isFalse();
        assertThat(c.emailEnabled()).isFalse();
    }

    @Test
    void readsOverridesAndTreatsBlankAsUnset() {
        AppConfig c = AppConfig.from(Map.of(
                "THRESHOLD_PCT", "2.25",
                "CHECK_TIME", "10:00",
                "TELEGRAM_BOT_TOKEN", "t",
                "TELEGRAM_CHAT_ID", "  ",
                "SMTP_USER", "me@gmail.com",
                "SMTP_PASSWORD", "app-pass",
                "ALERT_EMAIL_TO", "me@gmail.com"));

        assertThat(c.thresholdPct()).isEqualTo(2.25);
        assertThat(c.checkTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(c.telegramEnabled()).isFalse();   // chat id blank
        assertThat(c.emailEnabled()).isTrue();
    }
}
