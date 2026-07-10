package com.stockcharts.live;

import com.stockcharts.candle.Candle;
import com.stockcharts.candle.CandleService;
import com.stockcharts.candle.Timeframe;
import com.stockcharts.marketdata.dto.TickDto;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Folds live ticks into the in-progress 1-minute candle per symbol, persisting each
 * update and producing a {@link LiveUpdate} to broadcast.
 */
@Component
public class TickAggregator {

    private static final long BUCKET_MS = 60_000L; // 1-minute base candle

    private final CandleService candleService;
    private final ConcurrentHashMap<String, Bar> current = new ConcurrentHashMap<>();

    public TickAggregator(CandleService candleService) {
        this.candleService = candleService;
    }

    /** Mutable in-progress bar. */
    private static final class Bar {
        long bucketStartMs;
        double open, high, low, close, volume;
    }

    public synchronized LiveUpdate onTick(TickDto tick) {
        String symbol = tick.symbol().toUpperCase();
        long bucket = tick.timestamp() - (tick.timestamp() % BUCKET_MS);
        Bar bar = current.get(symbol);

        if (bar == null || bar.bucketStartMs != bucket) {
            bar = new Bar();
            bar.bucketStartMs = bucket;
            bar.open = bar.high = bar.low = bar.close = tick.ltp();
            bar.volume = tick.volume();
            current.put(symbol, bar);
        } else {
            bar.high = Math.max(bar.high, tick.ltp());
            bar.low = Math.min(bar.low, tick.ltp());
            bar.close = tick.ltp();
            bar.volume += tick.volume();
        }

        Instant openTime = Instant.ofEpochMilli(bar.bucketStartMs);
        candleService.upsert(new Candle(symbol, Timeframe.ONE_MINUTE, openTime,
                bar.open, bar.high, bar.low, bar.close, bar.volume));

        return new LiveUpdate(symbol, openTime.getEpochSecond(),
                bar.open, bar.high, bar.low, bar.close, bar.volume, tick.ltp());
    }
}
