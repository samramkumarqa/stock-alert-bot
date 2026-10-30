package io.github.samramkumarqa.stockalert.config;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

/**
 * All runtime settings. Everything comes from environment variables so that
 * no secret ever lives in the source tree (see README for the full list).
 */
public record AppConfig(
        ZoneId zone,
        LocalTime baselineTime,
        LocalTime checkTime,
        double thresholdPct,
        int maxAlerts,
        Duration fetchDelay,
        int fetchConcurrency,
        String priceSource,
        String python,
        Path fetchScript,
        String yahooBaseUrl,
        String universeUrls,
        Path universeCache,
        Optional<Path> holidaysFile,
        Path reportDir,
        boolean sendWhenNoSignals,
        Optional<String> telegramToken,
        Optional<String> telegramChatId,
        String telegramBaseUrl,
        Optional<String> smtpUser,
        Optional<String> smtpPassword,
        String smtpHost,
        int smtpPort,
        Optional<String> emailTo) {

    public static final String DEFAULT_UNIVERSE_URLS =
            "https://archives.nseindia.com/content/indices/ind_nifty500list.csv,"
            + "https://www.niftyindices.com/IndexConstituent/ind_nifty500list.csv";

    public static AppConfig fromEnv() {
        return from(System.getenv());
    }

    public static AppConfig from(Map<String, String> env) {
        Env e = new Env(env);
        return new AppConfig(
                ZoneId.of(e.get("MARKET_TIMEZONE", "Asia/Kolkata")),
                LocalTime.parse(e.get("BASELINE_TIME", "09:30")),
                LocalTime.parse(e.get("CHECK_TIME", "09:45")),
                Double.parseDouble(e.get("THRESHOLD_PCT", "1.5")),
                Integer.parseInt(e.get("MAX_ALERTS", "5")),
                Duration.ofSeconds(Long.parseLong(e.get("FETCH_DELAY_SECONDS", "60"))),
                Integer.parseInt(e.get("FETCH_CONCURRENCY", "8")),
                e.get("PRICE_SOURCE", "yfinance"),
                e.get("PYTHON", "python3"),
                Path.of(e.get("FETCH_SCRIPT", "scripts/fetch_bars.py")),
                e.get("YAHOO_BASE_URL", "https://query1.finance.yahoo.com"),
                e.get("UNIVERSE_URLS", DEFAULT_UNIVERSE_URLS),
                Path.of(e.get("UNIVERSE_FILE", "data/nifty500.csv")),
                e.opt("HOLIDAYS_FILE").map(Path::of),
                Path.of(e.get("REPORT_DIR", "reports")),
                Boolean.parseBoolean(e.get("SEND_WHEN_NO_SIGNALS", "true")),
                e.opt("TELEGRAM_BOT_TOKEN"),
                e.opt("TELEGRAM_CHAT_ID"),
                e.get("TELEGRAM_BASE_URL", "https://api.telegram.org"),
                e.opt("SMTP_USER"),
                e.opt("SMTP_PASSWORD"),
                e.get("SMTP_HOST", "smtp.gmail.com"),
                Integer.parseInt(e.get("SMTP_PORT", "587")),
                e.opt("ALERT_EMAIL_TO"));
    }

    public boolean telegramEnabled() {
        return telegramToken.isPresent() && telegramChatId.isPresent();
    }

    public boolean emailEnabled() {
        return smtpUser.isPresent() && smtpPassword.isPresent() && emailTo.isPresent();
    }

    private record Env(Map<String, String> values) {
        String get(String key, String fallback) {
            return opt(key).orElse(fallback);
        }

        Optional<String> opt(String key) {
            String v = values.get(key);
            return v == null || v.isBlank() ? Optional.empty() : Optional.of(v.trim());
        }
    }
}
