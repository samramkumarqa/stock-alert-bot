package io.github.samramkumarqa.stockalert.market;

import java.io.IOException;
import java.time.LocalDate;

/** Something that can give a day's 1-minute bars for an NSE symbol. */
public interface PriceSource {

    PriceSeries intraday(String nseSymbol, LocalDate date) throws IOException, InterruptedException;
}
