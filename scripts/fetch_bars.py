#!/usr/bin/env python3
"""Download one day of NSE 1-minute bars from Yahoo Finance via yfinance.

Why Python for this one step: Yahoo returns HTTP 429 to plain HTTP clients
(Java, curl) from cloud machines such as GitHub runners. yfinance uses
curl_cffi to look like a real browser, which Yahoo accepts.

Output CSV (read by the Java bot):  symbol,epoch,close
  symbol  NSE symbol without the .NS suffix
  epoch   bar START time, Unix seconds
  close   bar close price

Usage:
  python3 scripts/fetch_bars.py --symbols symbols.txt --date 2026-10-29 --out bars.csv
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import sys
import time
from typing import Iterable
from zoneinfo import ZoneInfo

IST = ZoneInfo("Asia/Kolkata")
BATCH = 100


def ticker(symbol: str) -> str:
    return f"{symbol}.NS"


def to_rows(df, symbols: Iterable[str]) -> list[tuple[str, int, float]]:
    """Flatten a yfinance download (group_by='ticker') into (symbol, epoch, close) rows."""
    rows: list[tuple[str, int, float]] = []
    if df is None or df.empty:
        return rows
    multi = getattr(df.columns, "nlevels", 1) > 1
    for symbol in symbols:
        t = ticker(symbol)
        try:
            closes = df[t]["Close"] if multi else df["Close"]
        except KeyError:
            continue
        for ts, close in closes.dropna().items():
            if close > 0:
                rows.append((symbol, int(ts.timestamp()), round(float(close), 4)))
    return rows


def download(symbols: list[str], day: dt.date):
    import yfinance as yf  # imported here so to_rows() is testable without network

    start = dt.datetime.combine(day, dt.time(9, 0), IST)
    end = dt.datetime.combine(day, dt.time(15, 45), IST)
    return yf.download(
        [ticker(s) for s in symbols],
        start=start,
        end=end,
        interval="1m",
        group_by="ticker",
        auto_adjust=False,
        prepost=False,
        threads=True,
        progress=False,
        multi_level_index=True,
    )


def fetch(symbols: list[str], day: dt.date, pause: float = 2.0) -> list[tuple[str, int, float]]:
    rows: list[tuple[str, int, float]] = []
    for i in range(0, len(symbols), BATCH):
        chunk = symbols[i:i + BATCH]
        rows += to_rows(download(chunk, day), chunk)
        time.sleep(pause)

    # One retry for anything that came back empty (transient throttling).
    got = {r[0] for r in rows}
    missing = [s for s in symbols if s not in got]
    if missing and len(missing) < len(symbols):
        print(f"retrying {len(missing)} symbols", file=sys.stderr)
        time.sleep(5)
        for i in range(0, len(missing), BATCH // 2):
            chunk = missing[i:i + BATCH // 2]
            rows += to_rows(download(chunk, day), chunk)
    return rows


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--symbols", required=True, help="file with one NSE symbol per line")
    p.add_argument("--date", required=True, type=dt.date.fromisoformat)
    p.add_argument("--out", required=True)
    a = p.parse_args(argv)

    with open(a.symbols, encoding="utf-8") as f:
        symbols = [line.strip() for line in f if line.strip()]

    rows = fetch(symbols, a.date)
    with open(a.out, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["symbol", "epoch", "close"])
        w.writerows(rows)

    priced = len({r[0] for r in rows})
    print(f"fetched {len(rows)} bars for {priced}/{len(symbols)} symbols", file=sys.stderr)
    return 0 if rows else 1


if __name__ == "__main__":
    sys.exit(main())
