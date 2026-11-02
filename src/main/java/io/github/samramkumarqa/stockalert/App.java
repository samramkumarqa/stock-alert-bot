package io.github.samramkumarqa.stockalert;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.samramkumarqa.stockalert.config.AppConfig;
import io.github.samramkumarqa.stockalert.market.MarketCalendar;
import io.github.samramkumarqa.stockalert.market.PriceSource;
import io.github.samramkumarqa.stockalert.market.YfinanceSource;
import io.github.samramkumarqa.stockalert.market.Universe;
import io.github.samramkumarqa.stockalert.market.YahooFinanceSource;
import io.github.samramkumarqa.stockalert.notify.Alert;
import io.github.samramkumarqa.stockalert.notify.AlertFormatter;
import io.github.samramkumarqa.stockalert.notify.ConsoleNotifier;
import io.github.samramkumarqa.stockalert.notify.EmailNotifier;
import io.github.samramkumarqa.stockalert.notify.Notifier;
import io.github.samramkumarqa.stockalert.notify.TelegramNotifier;
import io.github.samramkumarqa.stockalert.report.CsvReport;
import io.github.samramkumarqa.stockalert.rule.MomentumRule;

/**
 * Entry point.
 * <pre>
 *   java -jar stock-alert-bot.jar                    # live: wait for 9:45 IST, scan, alert
 *   java -jar stock-alert-bot.jar backtest 2026-10-29 # replay a past day (Yahoo keeps ~30 days of 1m bars)
 *   flags: --dry-run (console only)  --force (run even on a holiday/weekend)
 * </pre>
 */
public final class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);
    /** Above this share of unpriced symbols the run exits non-zero so GitHub Actions shows red. */
    private static final double FAIL_RATE_ERROR = 0.5;

    public static void main(String[] args) throws Exception {
        System.exit(run(args, AppConfig.fromEnv(), Clock.systemUTC()));
    }

    static int run(String[] args, AppConfig cfg, Clock clock) throws Exception {
        Set<String> flags = Set.copyOf(List.of(args).stream().filter(a -> a.startsWith("--")).toList());
        List<String> positional = List.of(args).stream().filter(a -> !a.startsWith("--")).toList();
        boolean dryRun = flags.contains("--dry-run");
        boolean force = flags.contains("--force");
        String mode = positional.isEmpty() ? "live" : positional.get(0);

        LocalDate today = LocalDate.now(clock.withZone(cfg.zone()));
        LocalDate date = switch (mode) {
            case "live" -> today;
            case "backtest" -> {
                if (positional.size() < 2) {
                    throw new IllegalArgumentException("usage: backtest YYYY-MM-DD");
                }
                yield LocalDate.parse(positional.get(1));
            }
            default -> throw new IllegalArgumentException("unknown mode '" + mode + "' (use live or backtest)");
        };
        boolean notify = mode.equals("live") ? !dryRun : flags.contains("--notify");
        List<Notifier> notifiers = notifiers(cfg, notify);

        MarketCalendar calendar = MarketCalendar.load(cfg.holidaysFile());
        if (!calendar.coversYear(date.getYear())) {
            log.warn("Holiday list has no entries for {}; update src/main/resources/nse-holidays.txt", date.getYear());
        }
        if (!calendar.isTradingDay(date) && !force) {
            log.info("{} is not an NSE trading day, nothing to do.", date);
            if ("true".equals(System.getenv("GITHUB_ACTIONS"))) {
                System.out.println("::notice title=Skipped::" + date + " (" + date.getDayOfWeek()
                        + ") is not an NSE trading day, so no scan and no alert. To test, run mode=backtest "
                        + "with a past trading date and tick notify.");
            }
            return 0;
        }

        try {
            if (mode.equals("live")) {
                Instant due = date.atTime(cfg.checkTime()).atZone(cfg.zone()).toInstant().plus(cfg.fetchDelay());
                Duration late = Duration.between(due, clock.instant());
                if (!force && late.compareTo(cfg.maxLate()) > 0) {
                    // A 9:45 alert that arrives hours later is noise; say what happened instead.
                    String when = LocalTime.now(clock.withZone(cfg.zone())).withNano(0).toString();
                    log.warn("Started {} min after the alert time; skipping the scan.", late.toMinutes());
                    if ("true".equals(System.getenv("GITHUB_ACTIONS"))) {
                        System.out.println("::warning title=Too late::Run started at " + when + " IST, "
                                + late.toMinutes() + " min after the alert time; no buy alert sent.");
                    }
                    deliver(AlertFormatter.late(date, when), notifiers);
                    return 0;
                }
                waitUntil(due, clock);
            }

            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            List<String> symbols = Universe.load(cfg.universeUrls(), cfg.universeCache(), http);
            MomentumRule rule = new MomentumRule(cfg.thresholdPct());
            Scanner scanner = new Scanner(priceSource(cfg, http), rule, cfg.zone(), cfg.fetchConcurrency());

            Scanner.Result result = scanner.scan(symbols, date, cfg.baselineTime(), cfg.checkTime());
            result.failures().stream().limit(20).forEach(f -> log.warn("Unpriced: {}", f));
            if ("true".equals(System.getenv("GITHUB_ACTIONS"))) {
                // Shows up as annotations on the workflow run page.
                System.out.printf("::notice title=Scan summary::%d of %d priced, %d failed, %d above threshold%n",
                        result.observations().size(), result.symbolsRequested(), result.failures().size(),
                        result.signals().size());
                if (!result.signals().isEmpty()) {
                    String top = MomentumRule.top(result.signals(), cfg.maxAlerts()).stream()
                            .map(o -> String.format(java.util.Locale.ENGLISH, "%s +%.2f%%", o.symbol(), o.changePct()))
                            .collect(java.util.stream.Collectors.joining(", "));
                    System.out.println("::notice title=Top gainers " + date + "::" + top);
                }
                result.failures().stream().limit(5)
                        .forEach(f -> System.out.println("::warning title=Unpriced::" + f.replace("\n", " ")));
            }

            Path csv = CsvReport.write(result, cfg.maxAlerts(), cfg.reportDir());
            log.info("Report written to {}", csv);

            if (!result.signals().isEmpty() || cfg.sendWhenNoSignals() || !notify) {
                int sent = deliver(AlertFormatter.format(result, rule.thresholdPct(), cfg.maxAlerts(), List.of(csv)),
                        notifiers);
                if (mode.equals("live") && sent > 0) {
                    // The workflow caches this marker so a second trigger the same day doesn't alert twice.
                    Files.writeString(cfg.reportDir().resolve("alert-sent-" + date), clock.instant().toString());
                }
            }
            return result.failureRate() > FAIL_RATE_ERROR ? 2 : 0;
        } catch (Exception e) {
            log.error("Run failed", e);
            deliver(AlertFormatter.failure("Scan for " + date + " did not complete.", e), notifiers);
            return 1;
        }
    }

    /**
     * {@code yfinance} (default): Python fetcher, works from GitHub runners.
     * {@code yahoo-http}: pure-Java client; Yahoo answers 429 to it from cloud IPs, may work from home.
     */
    static PriceSource priceSource(AppConfig cfg, HttpClient http) {
        return switch (cfg.priceSource()) {
            case "yfinance" -> new YfinanceSource(cfg.python(), cfg.fetchScript(),
                    cfg.universeCache().toAbsolutePath().getParent(), Duration.ofMinutes(15));
            case "yahoo-http" -> new YahooFinanceSource(http, cfg.yahooBaseUrl(), cfg.zone());
            default -> throw new IllegalArgumentException(
                    "PRICE_SOURCE must be yfinance or yahoo-http, not '" + cfg.priceSource() + "'");
        };
    }

    static List<Notifier> notifiers(AppConfig cfg, boolean external) {
        List<Notifier> list = new ArrayList<>();
        list.add(new ConsoleNotifier(System.out));
        if (!external) {
            return list;
        }
        if (cfg.telegramEnabled()) {
            list.add(new TelegramNotifier(HttpClient.newHttpClient(), cfg.telegramBaseUrl(),
                    cfg.telegramToken().orElseThrow(), cfg.telegramChatId().orElseThrow()));
        }
        if (cfg.emailEnabled()) {
            list.add(new EmailNotifier(cfg.smtpHost(), cfg.smtpPort(),
                    cfg.smtpUser().orElseThrow(), cfg.smtpPassword().orElseThrow(), cfg.emailTo().orElseThrow()));
        }
        if (list.size() == 1) {
            log.warn("No Telegram or email configured; alerts go to the console only.");
        }
        return list;
    }

    /**
     * Sends to every channel; one failing channel never blocks the others.
     * @return how many external channels (Telegram/email, not the console) accepted the alert
     */
    static int deliver(Alert alert, List<Notifier> notifiers) {
        int external = 0;
        for (Notifier n : notifiers) {
            try {
                n.send(alert);
                log.info("Alert sent via {}", n.name());
                if (!(n instanceof ConsoleNotifier)) {
                    external++;
                }
            } catch (Exception e) {
                log.error("Could not send via {}: {}", n.name(), e.getMessage());
            }
        }
        return external;
    }

    private static void waitUntil(Instant target, Clock clock) throws InterruptedException {
        Duration wait = Duration.between(clock.instant(), target);
        if (wait.isNegative() || wait.isZero()) {
            if (wait.compareTo(Duration.ofMinutes(-15)) < 0) {
                log.warn("Started {} min after the check time; prices are still read for the exact times, "
                        + "but the alert is late.", -wait.toMinutes());
            }
            return;
        }
        log.info("Waiting {} min {} s until {}", wait.toMinutes(), wait.toSecondsPart(), target);
        Thread.sleep(wait.toMillis());
    }

    private App() {
    }
}
