package io.github.samramkumarqa.stockalert.market;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/** Something that can give a day's 1-minute bars for an NSE symbol. */
public interface PriceSource {

    /**
     * Called once before a scan with every symbol, so batch sources can download in bulk.
     * Per-symbol sources need not override it.
     */
    default void prefetch(List<String> nseSymbols, LocalDate date) throws IOException, InterruptedException {
    }

    PriceSeries intraday(String nseSymbol, LocalDate date) throws IOException, InterruptedException;
}
