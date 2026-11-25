"""Offline tests for compare_rules: synthetic bars with hand-computed answers."""
import datetime as dt
import math

import pandas as pd
import pytest

from compare_rules import IST, day_features, evaluate, picks_for_rule, price_at

DAY = dt.date(2026, 11, 23)


def at(hhmm: str) -> dt.datetime:
    h, m = map(int, hhmm.split(":"))
    return dt.datetime.combine(DAY, dt.time(h, m), IST)


def bars(spec: dict[str, tuple[float, float, float]]) -> pd.DataFrame:
    """spec: {"09:15": (open, high, close), ...}"""
    idx = pd.DatetimeIndex([at(k) for k in spec])
    return pd.DataFrame([v for v in spec.values()], index=idx, columns=["Open", "High", "Close"]).sort_index()


def flat_day(open_: float, p930: float, p945: float, p1030: float, p1515: float,
             or_high: float | None = None, breakout: tuple[str, float, float] | None = None) -> pd.DataFrame:
    """A full session where closes jump to given levels at given times."""
    spec = {}
    t = at("09:15")
    while t <= at("15:30"):
        hhmm = t.strftime("%H:%M")
        c = (open_ if hhmm < "09:29" else p930 if hhmm < "09:44" else p945 if hhmm < "10:29"
             else p1030 if hhmm < "15:14" else p1515)
        spec[hhmm] = (c, c, c)
        t += dt.timedelta(minutes=1)
    spec["09:15"] = (open_, max(open_, spec["09:15"][2]), spec["09:15"][2])
    if or_high is not None:
        o, _, c = spec["09:20"]
        spec["09:20"] = (o, or_high, c)
    if breakout:
        hhmm, o, h = breakout
        spec[hhmm] = (o, h, spec[hhmm][2])
    return bars(spec)


def test_price_at_uses_close_of_finished_bar_and_open_at_market_open():
    b = bars({"09:15": (98, 100, 99.5), "09:29": (100, 101, 100.5), "09:30": (101, 102, 101.5)})
    assert price_at(b, at("09:15")) == 98            # nothing closed yet: opening price
    assert price_at(b, at("09:30")) == 100.5         # close of the 09:29 bar
    assert math.isnan(price_at(b, at("09:50")))      # last close 09:31, >5 min stale


def test_day_features_finds_opening_range_and_first_breakout():
    b = flat_day(100, 102, 103, 104, 105, or_high=102.5, breakout=("09:37", 102.4, 103.0))
    f = day_features("X", DAY, b)
    assert (f["p_open"], f["p_t930"], f["p_t945"], f["p_t1030"], f["p_t1515"]) == (100, 102, 103, 104, 105)
    assert f["or_high"] == 102.5
    assert f["brk_time"] == "09:37"
    assert f["brk_price"] == 102.5                   # buy-stop fills at the range high


def test_breakout_that_gaps_through_fills_at_bar_open():
    b = flat_day(100, 102, 103, 104, 105, or_high=102.5, breakout=("09:31", 103.0, 103.5))
    assert day_features("X", DAY, b)["brk_price"] == 103.0


def row(sym, p_open, p930, p945, brk_time="", brk_price=math.nan, p1030=None, p1515=None,
        prev_close=None, turnover=100.0):
    return {"date": DAY.isoformat(), "symbol": sym, "p_open": p_open, "p_t930": p930, "p_t945": p945,
            "p_t1030": p1030 or p945, "p_t1515": p1515 or p945, "or_high": p930,
            "brk_time": brk_time, "brk_price": brk_price,
            "prev_close": prev_close or p_open, "turnover_cr": turnover}


def test_rules_pick_the_right_stocks():
    day = pd.DataFrame([
        row("FADE", 100, 104, 102),       # A: +4%   C: +2% but fading -> excluded
        row("TREND", 100, 102, 103.5),    # A: +2%   B: +1.47% (no)  C: +3.5% rising
        row("LATE", 100, 100.5, 102.6),   # B: +2.09%  C: +2.6%
        row("FLAT", 100, 100.5, 100.6),
    ])
    pick = lambda r: list(picks_for_rule(day, r)["symbol"])  # noqa: E731
    assert pick("A") == ["FADE", "TREND"]
    assert pick("B") == ["LATE"]
    assert pick("C") == ["TREND", "LATE"]


def test_rule_d_orders_by_breakout_time_and_needs_a_breakout():
    day = pd.DataFrame([
        row("BIG", 100, 105, 105, brk_time="09:50", brk_price=105.2),
        row("EARLY", 100, 102, 103, brk_time="09:31", brk_price=102.1),
        row("NOBRK", 100, 103, 101),
    ])
    assert list(picks_for_rule(day, "D")["symbol"]) == ["EARLY", "BIG"]


def test_evaluate_measures_from_entry_price_and_applies_filters():
    feats = pd.DataFrame([
        row("UP", 100, 102, 103, p1030=104.04, p1515=107.1, prev_close=99),       # A: +2%
        row("GAPPER", 100, 103, 103, p1515=97.85, prev_close=95),                   # A: +3%, gap +5.3%
        row("OTHER", 100, 100, 100, p1515=101),
    ])
    summary, picks = evaluate(feats)
    a = summary[(summary.rule == "A") & (summary["filter"] == "none")].iloc[0]
    assert a.picks == 2
    # UP: 102 -> 107.1 = +5%;  GAPPER: 103 -> 97.85 = -5%;  average 0
    assert a.avg_to_1515_pct == pytest.approx(0.0, abs=1e-9)
    assert a.hit_rate_1515_pct == 50

    g = summary[(summary.rule == "A") & (summary["filter"] == "gap")].iloc[0]
    assert g.picks == 1 and g.avg_to_1515_pct == pytest.approx(5.0)
    assert g.avg_to_1030_pct == pytest.approx(2.0)   # 102 -> 104.04

    # Market from 09:30 to 15:15: (+5% + -5% + +1%) / 3
    up = picks[(picks.rule == "A") & (picks["filter"] == "gap")].iloc[0]
    assert up.mkt_1515 == pytest.approx((0.05 - 0.05 + 0.01) / 3)


def test_illiquid_filter():
    feats = pd.DataFrame([row("THIN", 100, 103, 103, turnover=5), row("OK", 100, 102, 102)])
    summary, _ = evaluate(feats)
    liq = summary[(summary.rule == "A") & (summary["filter"] == "liquid")].iloc[0]
    assert liq.picks == 1
