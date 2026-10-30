# stock-alert-bot

[![CI](https://github.com/samramkumarqa/stock-alert-bot/actions/workflows/ci.yml/badge.svg)](https://github.com/samramkumarqa/stock-alert-bot/actions/workflows/ci.yml)

Every NSE trading morning, this bot finds the **Nifty 500 stocks that rose more than 1.5% between 9:30 and 9:45 IST** and sends a **BUY alert** to Telegram and email.

It only sends alerts and never places orders. Treat it as a screener, not as investment advice.

```
📈 BUY ALERT · 3 stocks · Thu 29 Oct 2026

Up more than 1.5% from 9:30 → 9:45 IST:

1. TATAMOTORS  +2.86%  ₹700.00 → ₹720.00
2. M&M  +2.00%  ₹3,000.00 → ₹3,060.00
3. SBIN  +1.63%  ₹800.00 → ₹813.00

Checked 498 of 500 stocks.
```

## How it works

```
GitHub Actions (09:05 IST, Mon–Fri)
  └─ App waits until 09:46 IST
      ├─ Universe        Nifty 500 list from NSE (cached copy as fallback)
      ├─ YahooFinance    1-minute bars for SYMBOL.NS (8 requests in parallel, retry on 429/5xx)
      ├─ PriceSeries     price at 09:30 = close of the 09:29 bar; same for 09:45
      ├─ MomentumRule    (P945 − P930) / P930 × 100  >  1.5
      ├─ Notifiers       Telegram · Email (+ CSV) · console
      └─ CsvReport       reports/scan-YYYY-MM-DD.csv (uploaded as a workflow artifact)
```

Design choices:

- **Yahoo Finance chart API.** It's free, needs no login or token, and Yahoo lists NSE data as real-time. Because it returns the whole morning's 1-minute bars, a single fetch after 9:45 gives both prices.
- **Exact prices even if the job starts late.** GitHub often starts scheduled jobs late. That doesn't matter here: the 9:30 and 9:45 prices are read from the bars, not "now", so a late start still yields the exact prices and only delays the alert.
- **Telegram for alerts.** It's free and gives an instant push notification, with no SMS registration or per-message fees.
- **Secrets only in env vars and GitHub Secrets**, never in code.
- **Market calendar.** Weekends and NSE holidays (`src/main/resources/nse-holidays.txt`) are skipped.

## Setup (10 minutes)

1. **Telegram bot**
   - In Telegram, message **@BotFather** → `/newbot` → copy the token.
   - Send any message to your new bot.
   - Open `https://api.telegram.org/bot<TOKEN>/getUpdates` and copy `chat.id`.
2. **Gmail (optional):** create an App Password under Google Account → Security → App passwords.
3. **GitHub secrets:** in the repo, go to Settings → Secrets and variables → Actions → *New repository secret*:

   | Secret | Example |
   |---|---|
   | `TELEGRAM_BOT_TOKEN` | `123456:ABC-...` |
   | `TELEGRAM_CHAT_ID` | `987654321` |
   | `SMTP_USER` | `you@gmail.com` |
   | `SMTP_PASSWORD` | Gmail app password |
   | `ALERT_EMAIL_TO` | `you@gmail.com` |

   To change the threshold, add a repository **variable** `THRESHOLD_PCT` (default `1.5`).
4. **Test it:** go to Actions → *Morning scan* → *Run workflow* → mode `backtest`, a recent trading date, tick *notify*. You should get the alert for that day within a minute.

After that, it runs by itself every weekday.

## Run locally

```bash
mvn verify                                   # tests + build
cp .env.example .env && $EDITOR .env         # add your tokens
set -a; source .env; set +a

java -jar target/stock-alert-bot.jar backtest 2026-10-29            # console only
java -jar target/stock-alert-bot.jar backtest 2026-10-29 --notify   # also Telegram/email
java -jar target/stock-alert-bot.jar                                # live: waits for 09:46 IST
```

Flags: `--dry-run` (live mode, console only), `--force` (run on a weekend or holiday).
Yahoo keeps 1-minute bars for roughly the last 30 days, so backtests work within that window.

Docker:

```bash
docker build -t stock-alert-bot .
docker run --rm --env-file .env -v "$PWD/reports:/app/reports" stock-alert-bot backtest 2026-10-29
```

## Configuration

| Env var | Default | Meaning |
|---|---|---|
| `THRESHOLD_PCT` | `1.5` | Alert when the gain is **more than** this |
| `BASELINE_TIME` / `CHECK_TIME` | `09:30` / `09:45` | The window (IST) |
| `FETCH_DELAY_SECONDS` | `60` | Wait after `CHECK_TIME` so the last bar has closed |
| `FETCH_CONCURRENCY` | `8` | Parallel Yahoo requests |
| `SEND_WHEN_NO_SIGNALS` | `true` | Send a "no alerts today" message, so you know it ran |
| `UNIVERSE_URLS` | NSE Nifty 500 CSV | Comma-separated; use a Nifty 50 / 200 CSV URL for a smaller list |
| `UNIVERSE_FILE` | `data/nifty500.csv` | Cache, or your own list |
| `HOLIDAYS_FILE` | bundled | Custom holiday list |

## Tests

```
rule/MomentumRuleTest          threshold boundaries (1.49 / 1.50 / 1.51 %), sorting, bad prices
market/PriceSeriesTest         which bar is "the price at 09:30", stale/illiquid stocks
market/YahooFinanceSourceTest  real response fixture, URL encoding (M&M), 429 retry, 5xx give-up, 404
market/UniverseTest            NSE CSV parsing, download → cache → fallback
market/MarketCalendarTest      weekends, NSE holidays, custom file
ScannerTest                    end-to-end scan with a fake source, partial failures, concurrency cap
notify/TelegramNotifierTest    request shape, token never leaks in errors, 4096-char splitting
notify/AlertFormatterTest      message text, failure warning
report/CsvReportTest           report contents
```

HTTP tests use an in-process JDK `HttpServer` stub, so the suite never calls Yahoo or Telegram.

## Maintenance

- **Every December:** add next year's NSE holidays to `nse-holidays.txt`. The bot logs a warning if the year is missing.
- **Keep the schedule alive:** GitHub pauses scheduled workflows in repos with no commits for 60 days. Re-enable it from the Actions tab, or push any commit.
- **Data source:** Yahoo's endpoint is unofficial. If it changes, only `YahooFinanceSource`/`YahooChartParser` need updating, because the rest depends on the `PriceSource` interface.

---
This project is a rework of [TradingBOT2](https://github.com/samramkumarqa/TradingBOT2).
