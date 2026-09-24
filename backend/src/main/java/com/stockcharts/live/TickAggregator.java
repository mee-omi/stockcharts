package com.stockcharts.live;

import com.stockcharts.candle.Candle;
import com.stockcharts.candle.CandleService;
import com.stockcharts.candle.Timeframe;
import com.stockcharts.marketdata.dto.CandleDto;
import com.stockcharts.marketdata.dto.TickDto;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Folds live ticks into the in-progress 1-minute candle per symbol, persisting each update and
 * producing a {@link LiveUpdate} to broadcast. Three tick shapes are understood:
 * <ul>
 *   <li><b>bar snapshot</b> (polling providers): the provider's own 1m bar replaces ours as-is;</li>
 *   <li><b>day volume</b> (exchange websockets): the bar's volume grows by the increase in the
 *       day's cumulative volume since the previous tick;</li>
 *   <li><b>per-tick volume</b> (mock): added to the bar.</li>
 * </ul>
 */
@Component
public class TickAggregator {

    private static final long BUCKET_MS = 60_000L; // 1-minute base candle

    private final CandleService candleService;
    private final ConcurrentHashMap<String, Bar> current = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> lastDayVolume = new ConcurrentHashMap<>();

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
        Bar bar = tick.bar() != null ? fromSnapshot(symbol, tick.bar()) : fromPrice(symbol, tick);

        Instant openTime = Instant.ofEpochMilli(bar.bucketStartMs);
        candleService.upsert(new Candle(symbol, Timeframe.ONE_MINUTE, openTime,
                bar.open, bar.high, bar.low, bar.close, bar.volume));

        return new LiveUpdate(symbol, openTime.getEpochSecond(),
                bar.open, bar.high, bar.low, bar.close, bar.volume, tick.ltp());
    }

    private Bar fromSnapshot(String symbol, CandleDto c) {
        Bar bar = new Bar();
        bar.bucketStartMs = c.timestamp() - (c.timestamp() % BUCKET_MS);
        bar.open = c.open();
        bar.high = c.high();
        bar.low = c.low();
        bar.close = c.close();
        bar.volume = c.volume();
        current.put(symbol, bar);
        return bar;
    }

    private Bar fromPrice(String symbol, TickDto tick) {
        long bucket = tick.timestamp() - (tick.timestamp() % BUCKET_MS);
        double increment = volumeIncrement(symbol, tick);
        Bar bar = current.get(symbol);
        if (bar == null || bar.bucketStartMs != bucket) {
            bar = new Bar();
            bar.bucketStartMs = bucket;
            bar.open = bar.high = bar.low = bar.close = tick.ltp();
            bar.volume = increment;
            seedFromStored(symbol, bar);
            current.put(symbol, bar);
        } else {
            bar.high = Math.max(bar.high, tick.ltp());
            bar.low = Math.min(bar.low, tick.ltp());
            bar.close = tick.ltp();
            bar.volume += increment;
        }
        return bar;
    }

    /**
     * If history already holds this minute (e.g. the stream (re)connected mid-minute), continue
     * that bar — keeping its true open, range and volume — instead of overwriting it.
     */
    private void seedFromStored(String symbol, Bar bar) {
        candleService.latest(symbol, Timeframe.ONE_MINUTE)
                .filter(c -> c.getOpenTime().toEpochMilli() == bar.bucketStartMs)
                .ifPresent(c -> {
                    bar.open = c.getOpen();
                    bar.high = Math.max(c.getHigh(), bar.high);
                    bar.low = Math.min(c.getLow(), bar.low);
                    bar.volume += c.getVolume();
                });
    }

    /** Volume traded since the previous tick for this symbol. */
    private double volumeIncrement(String symbol, TickDto tick) {
        if (tick.dayVolume() == null) {
            return tick.volume();
        }
        Double previous = lastDayVolume.put(symbol, tick.dayVolume());
        if (previous == null || tick.dayVolume() < previous) {
            return 0; // first tick after (re)connect, or a new trading day
        }
        return tick.dayVolume() - previous;
    }
}
