#!/usr/bin/env python3
"""Compare morning momentum rules on recent NSE data (Nifty 500, Yahoo 1-minute bars).

Step 1  features: for every stock and trading day, the prices each rule needs
        (09:15 open, 09:30, 09:45, opening-range high and breakout, 10:30, 15:15,
        previous close, 20-day average turnover).
Step 2  rules:    each rule picks at most TOP_N stocks per day; we measure what
        happened next, from the alert price to 10:30 and to 15:15.

Rules (threshold 1.5%, top 5 per day by gain unless stated):
  A  09:15 open -> 09:30            alert 09:31, entry = 09:30 price   (current bot)
  B  09:30 -> 09:45                  alert 09:46, entry = 09:45 price   (previous bot)
  C  09:15 open -> 09:45, and 09:45 >= 09:30 (still rising)            alert 09:46
  D  A's stocks, but alert only when price breaks the 09:15-09:30 high
     between 09:30 and 10:00; first 5 breakouts of the day; entry = breakout price

Filters applied on top of each rule:
  gap     skip stocks that opened more than 3% above the previous close
  liquid  skip stocks with 20-day average turnover below Rs 20 crore

Price at time T = close of the last 1-minute bar that finished by T (as in the bot);
at 09:15 it is the open of the 09:15 bar. No brokerage or slippage is deducted.

Usage:
  python analysis/compare_rules.py fetch   --universe data/nifty500.csv --out analysis/out
  python analysis/compare_rules.py report  --features analysis/out/features.csv --out analysis/out
"""
from __future__ import annotations

import argparse
import datetime as dt
import math
import sys
import time
from pathlib import Path
from zoneinfo import ZoneInfo

import pandas as pd

IST = ZoneInfo("Asia/Kolkata")
THRESHOLD = 0.015
TOP_N = 5
GAP_MAX = 0.03
TURNOVER_MIN_CR = 20.0
BATCH = 100
STALE = dt.timedelta(minutes=5)

T = {k: dt.time(*map(int, v.split(":"))) for k, v in {
    "open": "09:15", "t930": "09:30", "t945": "09:45", "brk_end": "10:00",
    "t1030": "10:30", "t1515": "15:15"}.items()}


# ---------------------------------------------------------------- prices ----

def price_at(bars: pd.DataFrame, t: dt.datetime) -> float:
    """bars: index = bar start (tz-aware), columns Open/High/Close. NaN if unknown."""
    if bars.empty:
        return math.nan
    ends = bars.index + pd.Timedelta(minutes=1)
    done = bars[ends <= t]
    if not done.empty and done.index[-1] + pd.Timedelta(minutes=1) >= t - STALE:
        return float(done["Close"].iloc[-1])
    if t in bars.index:
        return float(bars.loc[t, "Open"])
    return math.nan


def day_features(sym: str, day: dt.date, bars: pd.DataFrame) -> dict:
    at = lambda name: dt.datetime.combine(day, T[name], IST)  # noqa: E731
    bars = bars.dropna(subset=["Close"])
    bars = bars[bars["Close"] > 0]
    f = {"date": day.isoformat(), "symbol": sym}
    for name in ("open", "t930", "t945", "t1030", "t1515"):
        f["p_" + name] = price_at(bars, at(name))

    # Opening range: highs of the 09:15..09:29 bars.
    rng = bars[(bars.index >= at("open")) & (bars.index < at("t930"))]
    orh = float(rng["High"].max()) if not rng.empty else math.nan
    f["or_high"] = orh
    f["brk_time"], f["brk_price"] = "", math.nan
    if not math.isnan(orh):
        window = bars[(bars.index >= at("t930")) & (bars.index < at("brk_end"))]
        hit = window[window["High"] > orh]
        if not hit.empty:
            first = hit.iloc[0]
            # A buy-stop at the range high fills there, or at the bar's open if it gapped through.
            f["brk_time"] = hit.index[0].strftime("%H:%M")
            f["brk_price"] = max(orh, float(first["Open"]))
    return f


# ------------------------------------------------------------- fetching ----

def load_universe(path: Path) -> list[str]:
    df = pd.read_csv(path, encoding="utf-8-sig")
    df.columns = [c.strip() for c in df.columns]
    if "Series" in df:
        df = df[df["Series"].str.strip() == "EQ"]
    syms = [s.strip() for s in df["Symbol"].astype(str) if not s.strip().upper().startswith("DUMMY")]
    return list(dict.fromkeys(syms))


def trading_days(days_back: int) -> list[dt.date]:
    today = dt.datetime.now(IST).date()
    out = []
    for i in range(days_back, -1, -1):
        d = today - dt.timedelta(days=i)
        if d.weekday() < 5:
            out.append(d)
    return out


def split_by_ticker(df: pd.DataFrame, tickers: list[str]) -> dict[str, pd.DataFrame]:
    res = {}
    if df is None or df.empty:
        return res
    multi = getattr(df.columns, "nlevels", 1) > 1
    for t in tickers:
        try:
            sub = df[t] if multi else df
        except KeyError:
            continue
        sub = sub[["Open", "High", "Close"] + (["Volume"] if "Volume" in sub else [])].dropna(subset=["Close"])
        if not sub.empty:
            sub.index = sub.index.tz_convert(IST)
            res[t] = sub
    return res


def fetch(universe: Path, out: Path, days_back: int) -> None:
    import yfinance as yf

    symbols = load_universe(universe)
    tickers = [s + ".NS" for s in symbols]
    out.mkdir(parents=True, exist_ok=True)
    print(f"{len(symbols)} symbols", file=sys.stderr)

    # Daily bars: previous close and turnover history.
    daily = yf.download(tickers, period="4mo", interval="1d", group_by="ticker",
                        auto_adjust=False, progress=False, threads=True, multi_level_index=True)
    daily_by = {}
    for t in tickers:
        try:
            d = daily[t].dropna(subset=["Close"])
        except KeyError:
            continue
        d.index = pd.to_datetime(d.index).date
        daily_by[t] = d

    rows = []
    for day in trading_days(days_back):
        start = dt.datetime.combine(day, dt.time(9, 0), IST)
        end = dt.datetime.combine(day, dt.time(15, 45), IST)
        got = {}
        for i in range(0, len(tickers), BATCH):
            chunk = tickers[i:i + BATCH]
            try:
                df = yf.download(chunk, start=start, end=end, interval="1m", group_by="ticker",
                                 auto_adjust=False, prepost=False, progress=False, threads=True,
                                 multi_level_index=True)
                got.update(split_by_ticker(df, chunk))
            except Exception as e:  # noqa: BLE001 - keep going on a bad batch
                print(f"{day} batch {i}: {e}", file=sys.stderr)
            time.sleep(1.5)
        print(f"{day}: bars for {len(got)} symbols", file=sys.stderr)
        if len(got) < 50:      # holiday, or outside Yahoo's 30-day 1m window
            continue
        for t, bars in got.items():
            f = day_features(t[:-3], day, bars)
            d = daily_by.get(t)
            if d is not None:
                prior = d[[x < day for x in d.index]]
                f["prev_close"] = float(prior["Close"].iloc[-1]) if not prior.empty else math.nan
                last20 = prior.tail(20)
                f["turnover_cr"] = float((last20["Close"] * last20["Volume"]).mean() / 1e7) if not last20.empty else math.nan
            rows.append(f)
    feats = pd.DataFrame(rows)
    feats.to_csv(out / "features.csv", index=False)
    print(f"features: {len(feats)} rows, {feats['date'].nunique() if len(feats) else 0} days", file=sys.stderr)


# ---------------------------------------------------------------- rules ----

def picks_for_rule(day: pd.DataFrame, rule: str) -> pd.DataFrame:
    """Return the picked rows with columns: symbol, gain, entry, entry_time."""
    d = day.copy()
    if rule == "A":
        d["gain"] = d.p_t930 / d.p_open - 1
        d = d[d.gain > THRESHOLD]
        d["entry"], d["entry_time"] = d.p_t930, "09:31"
        return d.sort_values(["gain", "symbol"], ascending=[False, True]).head(TOP_N)
    if rule == "B":
        d["gain"] = d.p_t945 / d.p_t930 - 1
        d = d[d.gain > THRESHOLD]
        d["entry"], d["entry_time"] = d.p_t945, "09:46"
        return d.sort_values(["gain", "symbol"], ascending=[False, True]).head(TOP_N)
    if rule == "C":
        d["gain"] = d.p_t945 / d.p_open - 1
        d = d[(d.gain > THRESHOLD) & (d.p_t945 >= d.p_t930)]
        d["entry"], d["entry_time"] = d.p_t945, "09:46"
        return d.sort_values(["gain", "symbol"], ascending=[False, True]).head(TOP_N)
    if rule == "D":
        d["gain"] = d.p_t930 / d.p_open - 1
        d = d[(d.gain > THRESHOLD) & d.brk_price.notna() & (d.brk_time.astype(str) != "")]
        d["entry"], d["entry_time"] = d.brk_price, d.brk_time
        return d.sort_values(["brk_time", "gain"], ascending=[True, False]).head(TOP_N)
    raise ValueError(rule)


def apply_filter(day: pd.DataFrame, flt: str) -> pd.DataFrame:
    keep = pd.Series(True, index=day.index)
    if flt in ("gap", "both"):
        gap = day.p_open / day.prev_close - 1
        keep &= ~(gap > GAP_MAX)          # unknown previous close: keep
    if flt in ("liquid", "both"):
        keep &= ~(day.turnover_cr < TURNOVER_MIN_CR)
    return day[keep]


RULE_NAMES = {
    "A": "A · 9:15 open → 9:30 (now)",
    "B": "B · 9:30 → 9:45 (before)",
    "C": "C · 9:15 → 9:45, still rising",
    "D": "D · A + breakout of 9:15–9:30 high",
}
FILTER_NAMES = {"none": "no filter", "gap": "skip gap-ups >3%", "liquid": "skip < ₹20 cr/day",
                "both": "both filters"}


def evaluate(feats: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame]:
    feats = feats.copy()
    feats["brk_time"] = feats["brk_time"].fillna("").astype(str)
    picks_all = []
    for date, day in feats.groupby("date"):
        priced = day.dropna(subset=["p_open", "p_t930", "p_t945", "p_t1515"])
        # Market drift: average move of every stock from the same alert time to 15:15.
        mkt_931 = (priced.p_t1515 / priced.p_t930 - 1).mean()
        mkt_946 = (priced.p_t1515 / priced.p_t945 - 1).mean()
        for flt in FILTER_NAMES:
            base = apply_filter(priced, flt)
            for rule in RULE_NAMES:
                p = picks_for_rule(base, rule)
                if p.empty:
                    picks_all.append({"date": date, "rule": rule, "filter": flt, "symbol": ""})
                    continue
                for _, r in p.iterrows():
                    mkt = mkt_931 if r.entry_time <= "09:31" else mkt_946
                    picks_all.append({
                        "date": date, "rule": rule, "filter": flt, "symbol": r.symbol,
                        "gain": r.gain, "entry_time": r.entry_time, "entry": r.entry,
                        "ret_1030": r.p_t1030 / r.entry - 1 if r.entry_time < "10:30" else math.nan,
                        "ret_1515": r.p_t1515 / r.entry - 1,
                        "mkt_1515": mkt,
                    })
    picks = pd.DataFrame(picks_all)
    days = feats["date"].nunique()

    rows = []
    for (rule, flt), g in picks.groupby(["rule", "filter"], sort=False):
        real = g[g.symbol != ""]
        per_day = real.groupby("date")["ret_1515"].mean()
        rows.append({
            "rule": rule, "filter": flt,
            "days": days,
            "days_with_alerts": real["date"].nunique(),
            "picks": len(real),
            "avg_to_1030_pct": 100 * real["ret_1030"].mean(),
            "avg_to_1515_pct": 100 * real["ret_1515"].mean(),
            "hit_rate_1515_pct": 100 * (real["ret_1515"] > 0).mean() if len(real) else math.nan,
            "vs_market_1515_pct": 100 * (real["ret_1515"] - real["mkt_1515"]).mean(),
            "worst_day_pct": 100 * per_day.min() if len(per_day) else math.nan,
            "best_day_pct": 100 * per_day.max() if len(per_day) else math.nan,
        })
    return pd.DataFrame(rows), picks


def fmt(v: float, signed: bool = True) -> str:
    if v is None or (isinstance(v, float) and math.isnan(v)):
        return "–"
    return f"{v:+.2f}%" if signed else f"{v:.0f}%"


def report(features: Path, out: Path) -> None:
    feats = pd.read_csv(features)
    summary, picks = evaluate(feats)
    out.mkdir(parents=True, exist_ok=True)
    summary.to_csv(out / "summary.csv", index=False)
    picks.to_csv(out / "picks.csv", index=False)

    dates = sorted(feats["date"].unique())
    lines = [f"# Rule comparison · {dates[0]} → {dates[-1]} · {len(dates)} trading days · "
             f"{feats['symbol'].nunique()} stocks", "",
             "| Rule | Filter | Days with alerts | Picks | Avg to 10:30 | Avg to 3:15 PM | "
             "Up at 3:15 PM | vs market | Worst day | Best day |",
             "|---|---|---|---|---|---|---|---|---|---|"]
    for _, r in summary.iterrows():
        lines.append(f"| {RULE_NAMES[r.rule]} | {FILTER_NAMES[r['filter']]} | {r.days_with_alerts}/{r.days} | "
                     f"{r.picks} | {fmt(r.avg_to_1030_pct)} | {fmt(r.avg_to_1515_pct)} | "
                     f"{fmt(r.hit_rate_1515_pct, False)} | {fmt(r.vs_market_1515_pct)} | "
                     f"{fmt(r.worst_day_pct)} | {fmt(r.best_day_pct)} |")
    (out / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("fetch")
    f.add_argument("--universe", type=Path, required=True)
    f.add_argument("--out", type=Path, required=True)
    f.add_argument("--days-back", type=int, default=29)
    r = sub.add_parser("report")
    r.add_argument("--features", type=Path, required=True)
    r.add_argument("--out", type=Path, required=True)
    a = p.parse_args(argv)
    if a.cmd == "fetch":
        fetch(a.universe, a.out, a.days_back)
    else:
        report(a.features, a.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
