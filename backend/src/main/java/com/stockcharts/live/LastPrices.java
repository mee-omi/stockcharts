package com.stockcharts.live;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** The latest traded price per symbol from the live feed. */
@Component
public class LastPrices {

    private final ConcurrentHashMap<String, PriceTick> last = new ConcurrentHashMap<>();

    public void update(PriceTick tick) {
        last.merge(tick.symbol(), tick, (a, b) -> b.epochMs() >= a.epochMs() ? b : a);
    }

    /** The last tick for {@code symbol} if it is at most {@code maxAgeMs} old. */
    public Optional<PriceTick> get(String symbol, long maxAgeMs) {
        PriceTick t = last.get(symbol.toUpperCase());
        return t != null && System.currentTimeMillis() - t.epochMs() <= maxAgeMs ? Optional.of(t) : Optional.empty();
    }
}
