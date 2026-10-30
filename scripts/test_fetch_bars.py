"""Offline tests for fetch_bars.to_rows (no network)."""
import datetime as dt

import numpy as np
import pandas as pd

from fetch_bars import IST, to_rows


def frame(data: dict[str, list[float]], start="09:15") -> pd.DataFrame:
    """Build a DataFrame shaped like yf.download(group_by='ticker', multi_level_index=True)."""
    n = len(next(iter(data.values())))
    h, m = map(int, start.split(":"))
    idx = pd.DatetimeIndex(
        [dt.datetime(2026, 10, 29, h, m, tzinfo=IST) + dt.timedelta(minutes=i) for i in range(n)],
        name="Datetime")
    cols = pd.MultiIndex.from_product([list(data), ["Open", "High", "Low", "Close", "Volume"]],
                                      names=["Ticker", "Price"])
    df = pd.DataFrame(index=idx, columns=cols, dtype=float)
    for t, closes in data.items():
        df[(t, "Close")] = closes
    return df


def test_flattens_each_ticker_and_strips_ns_suffix():
    df = frame({"SBIN.NS": [800.0, 801.5], "M&M.NS": [3000.0, 3010.0]})
    rows = to_rows(df, ["SBIN", "M&M"])

    nine_fifteen = int(dt.datetime(2026, 10, 29, 9, 15, tzinfo=IST).timestamp())
    assert ("SBIN", nine_fifteen, 800.0) in rows
    assert ("M&M", nine_fifteen + 60, 3010.0) in rows
    assert len(rows) == 4


def test_skips_minutes_without_trades_and_missing_tickers():
    df = frame({"INFY.NS": [1500.0, np.nan, 1502.0]})
    rows = to_rows(df, ["INFY", "NOSUCH"])
    assert [r[2] for r in rows] == [1500.0, 1502.0]


def test_empty_download_gives_no_rows():
    assert to_rows(pd.DataFrame(), ["SBIN"]) == []
    assert to_rows(None, ["SBIN"]) == []
