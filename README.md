# stock-alert-bot

[![CI](https://github.com/samramkumarqa/stock-alert-bot/actions/workflows/ci.yml/badge.svg)](https://github.com/samramkumarqa/stock-alert-bot/actions/workflows/ci.yml)

Every NSE trading morning, this bot finds the Nifty 500 stocks that rose more than 1.5% between 9:30 and 9:45 IST. It sends a **BUY alert for the top 5 gainers** to Telegram and email.

It only sends alerts and never places orders. Treat it as a screener, not as investment advice.

```
📈 BUY ALERT · Top 5 of 23 stocks · Thu 29 Oct 2026

Top 5 gainers, each up more than 1.5% from 9:30 → 9:45 IST:

1. TATAMOTORS  +2.86%  ₹700.00 → ₹720.00
2. M&M  +2.00%  ₹3,000.00 → ₹3,060.00
3. SBIN  +1.63%  ₹800.00 → ₹813.00
4. ...
5. ...

+18 more above 1.5% (full list in the CSV report).
Checked 498 of 500 stocks.
```

## How it works

```
cron-job.org → GitHub Actions (09:35 IST, Mon–Fri; GitHub schedule as backup)
  └─ App waits until 09:46 IST
      ├─ Universe        Nifty 500 list from NSE (cached copy as fallback)
      ├─ YfinanceSource  runs scripts/fetch_bars.py once: 1-minute bars for all SYMBOL.NS (yfinance)
      ├─ PriceSeries     price at 09:30 = close of the 09:29 bar; same for 09:45
      ├─ MomentumRule    (P945 − P930) / P930 × 100  >  1.5, then top 5 by gain
      ├─ Notifiers       Telegram · Email (+ CSV) · console
      └─ CsvReport       reports/scan-YYYY-MM-DD.csv: every stock, marked above_threshold / buy_alert
```

Design choices:

- **Yahoo Finance data.** It's free, needs no login or token, and Yahoo lists NSE data as real-time. Because it returns the whole morning's 1-minute bars, a single fetch after 9:45 gives both prices.
- **One small Python step.** Yahoo blocks plain HTTP clients from cloud servers: from a GitHub runner, Java's HTTP client and even `curl` get HTTP 429 on every request. The [yfinance](https://github.com/ranaroussi/yfinance) library looks like a real browser and is accepted, so `scripts/fetch_bars.py` does the download and everything else stays in Java. The pure-Java client is still there (`PRICE_SOURCE=yahoo-http`) for networks where Yahoo allows it.
- **Exact prices, and an on-time trigger.** The 9:30 and 9:45 prices come from the 1-minute bars, not from "now". An external scheduler starts the job, because GitHub's own scheduler can run hours late, and a run that starts too late sends a warning instead of a stale buy list.
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

   To change the threshold or the number of stocks, add repository **variables** `THRESHOLD_PCT` (default `1.5`) and `MAX_ALERTS` (default `5`).
4. **Test it:** go to Actions → *Morning scan* → *Run workflow* → mode `backtest`, a recent trading date, tick *notify*. You should get the alert for that day within a minute.

5. **On-time trigger (needed).** See the next section.

## On-time trigger (cron-job.org)

GitHub's built-in scheduler is "best effort". On 2 Nov 2026, the 09:05 schedule only started at **17:07**. To get the alert at 9:46, a free external scheduler starts the workflow instead, and it fires on the minute.

1. **Create a GitHub token** that can only start this workflow: GitHub → your avatar → Settings → Developer settings → **Fine-grained tokens** → *Generate new token*.
   - Repository access: *Only select repositories* → `stock-alert-bot`
   - Permissions → Repository → **Actions: Read and write** (leave the rest at "No access")
   - Expiration: 1 year. Set yourself a reminder to renew it.
2. **Create a free account at [cron-job.org](https://cron-job.org)** → *Create cronjob*:
   - URL: `https://api.github.com/repos/samramkumarqa/stock-alert-bot/actions/workflows/morning-scan.yml/dispatches`
   - Schedule: custom, **09:35**, Monday–Friday, time zone **Asia/Kolkata**
   - *Advanced* tab: request method **POST**; headers `Authorization: Bearer <your token>`, `Accept: application/vnd.github+json`, `Content-Type: application/json`; request body `{"ref":"main"}`
   - Turn on "notify me on failure".
3. Click **Test run**. GitHub replies `204` and a new run appears under Actions. On a weekend it just logs "not an NSE trading day".

How the two triggers work together:

| | |
|---|---|
| cron-job.org, 09:35 | Primary. The run waits until 09:46, scans, and sends the alert. |
| GitHub schedule, 09:05 | Backup, in case cron-job.org misses a day. |
| Same-day duplicate | The first run that sends saves an `alert-sent-<date>` marker, and later runs that day stop at once. |
| Run starts after 10:16 (`MAX_LATE_MINUTES`=30) | No stale buy list. You get "⚠ Morning scan started late" instead, so you know the trigger failed. |

After that, it runs by itself every weekday.

## Run locally

Needs Java 21 and Python 3.10+.

```bash
pip install -r scripts/requirements.txt      # yfinance (the price fetcher)
mvn verify                                   # tests + build
cp .env.example .env && $EDITOR .env         # add your tokens
set -a; source .env; set +a

java -jar target/stock-alert-bot.jar backtest 2026-10-29            # console only
java -jar target/stock-alert-bot.jar backtest 2026-10-29 --notify   # also Telegram/email
java -jar target/stock-alert-bot.jar                                # live: waits for 09:46 IST
```

Run these from the repo root, so the bot finds `scripts/fetch_bars.py`.
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
| `THRESHOLD_PCT` | `1.5` | A stock qualifies when its gain is **more than** this |
| `MAX_ALERTS` | `5` | Alert only the top N gainers among qualifying stocks (`0` = all) |
| `BASELINE_TIME` / `CHECK_TIME` | `09:30` / `09:45` | The window (IST) |
| `FETCH_DELAY_SECONDS` | `60` | Wait after `CHECK_TIME` so the last bar has closed |
| `PRICE_SOURCE` | `yfinance` | `yfinance` (Python fetcher), or `yahoo-http` (pure Java; blocked from cloud servers) |
| `PYTHON` / `FETCH_SCRIPT` | `python3` / `scripts/fetch_bars.py` | How the bot runs the fetcher |
| `FETCH_CONCURRENCY` | `8` | Parallel requests (`yahoo-http` only) |
| `MAX_LATE_MINUTES` | `30` | In live mode, skip the buy list (send a "started late" warning) if the run starts more than this after the alert time. `--force` overrides it |
| `SEND_WHEN_NO_SIGNALS` | `true` | Send a "no alerts today" message, so you know it ran |
| `UNIVERSE_URLS` | NSE Nifty 500 CSV | Comma-separated; use a Nifty 50 / 200 CSV URL for a smaller list |
| `UNIVERSE_FILE` | `data/nifty500.csv` | Cache, or your own list |
| `HOLIDAYS_FILE` | bundled | Custom holiday list |

## Tests

```
rule/MomentumRuleTest          threshold boundaries (1.49 / 1.50 / 1.51 %), sorting, top-N, bad prices
market/PriceSeriesTest         which bar is "the price at 09:30", stale/illiquid stocks
market/YfinanceSourceTest      Java ↔ Python bridge with a fake fetcher: CSV hand-off, failures, missing script
market/YahooFinanceSourceTest  pure-Java client: response fixture, URL encoding (M&M), 429 retry, 5xx give-up, 404
market/UniverseTest            NSE CSV parsing, download → cache → fallback
market/MarketCalendarTest      weekends, NSE holidays, custom file
AppTest                        live timing: late start skipped with a warning, on-time run leaves the once-a-day marker
ScannerTest                    end-to-end scan with a fake source, partial failures, concurrency cap
notify/TelegramNotifierTest    request shape, token never leaks in errors, 4096-char splitting
notify/AlertFormatterTest      message text, top-5 cut with "+N more", failure warning
report/CsvReportTest           report contents
scripts/test_fetch_bars.py     (pytest) flattening yfinance's DataFrame into bars, gaps, missing tickers
```

No test calls Yahoo or Telegram: HTTP tests use an in-process JDK `HttpServer` stub, and the fetcher tests use synthetic DataFrames.

## Maintenance

- **Every December:** add next year's NSE holidays to `nse-holidays.txt`. The bot logs a warning if the year is missing.
- **Keep the schedule alive:** GitHub pauses scheduled workflows in repos with no commits for 60 days. Re-enable it from the Actions tab, or push any commit.
- **Data source:** Yahoo's endpoint is unofficial. If downloads start failing, first try `pip install -U yfinance`, since that project tracks Yahoo's changes. Swapping to another provider means one new `PriceSource` class; nothing else changes.

---
This project is a rework of [TradingBOT2](https://github.com/samramkumarqa/TradingBOT2).
