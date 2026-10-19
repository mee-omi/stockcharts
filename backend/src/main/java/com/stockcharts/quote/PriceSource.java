package com.stockcharts.quote;

import com.stockcharts.live.LastPrices;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.OptionalDouble;

/** Best available last price: a fresh live tick, else the newest stored candle close. */
@Component
public class PriceSource {

    /** Ticks older than this are not trusted as "the" price (the feed may have dropped). */
    static final long LIVE_MAX_AGE_MS = 120_000;

    private final LastPrices lastPrices;
    private final QuoteService quotes;

    public PriceSource(LastPrices lastPrices, QuoteService quotes) {
        this.lastPrices = lastPrices;
        this.quotes = quotes;
    }

    public OptionalDouble ltp(String symbol) {
        var live = lastPrices.get(symbol, LIVE_MAX_AGE_MS);
        if (live.isPresent()) {
            return OptionalDouble.of(live.get().ltp());
        }
        List<QuoteService.Quote> q = quotes.quotes(List.of(symbol));
        return !q.isEmpty() && q.get(0).ltp() != null ? OptionalDouble.of(q.get(0).ltp()) : OptionalDouble.empty();
    }
}
