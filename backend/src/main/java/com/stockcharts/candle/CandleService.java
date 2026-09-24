package com.stockcharts.candle;

import com.stockcharts.config.AppProperties;
import com.stockcharts.marketdata.MarketDataClient;
import com.stockcharts.marketdata.dto.CandleDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves candles with a read-through cache: stored candles come from TimescaleDB; on a
 * gap the range is backfilled from the Python data service and persisted.
 */
@Service
public class CandleService {

    private static final Logger log = LoggerFactory.getLogger(CandleService.class);

    private final MarketDataClient marketData;
    private final CandleRepository repository;
    private final AppProperties props;

    public CandleService(MarketDataClient marketData, CandleRepository repository, AppProperties props) {
        this.marketData = marketData;
        this.repository = repository;
        this.props = props;
    }

    /** Raw entities for a (symbol, timeframe, range), backfilling first if needed. */
    @Transactional
    public List<Candle> getCandles(String symbolRaw, Timeframe tf, Instant from, Instant to) {
        String symbol = symbolRaw.toUpperCase();
        if (from == null || to == null) {
            to = Instant.now();
            long span = tf == Timeframe.ONE_DAY
                    ? 365L * 24 * 3600 * 1000
                    : (long) props.getBackfillDays() * 24 * 3600 * 1000;
            from = to.minusMillis(span);
        }
        ensureCoverage(symbol, tf, from, to);
        return repository.findBySymbolAndTimeframeAndOpenTimeBetweenOrderByOpenTimeAsc(symbol, tf, from, to);
    }

    public List<CandleResponse> getCandleResponses(String symbol, Timeframe tf, Instant from, Instant to) {
        return getCandles(symbol, tf, from, to).stream().map(CandleResponse::from).toList();
    }

    /**
     * Fetch only what storage lacks: the full range when nothing is stored; otherwise the tail
     * (from the newest stored bar, which may still be forming, to {@code to}) when stale, and the
     * head (from {@code from} to the oldest stored bar) when history doesn't reach back far enough.
     * Repeat attempts for the same gap are throttled so chart reloads don't hammer broker APIs.
     */
    private void ensureCoverage(String symbol, Timeframe tf, Instant from, Instant to) {
        long step = tf.getMillis();
        Optional<Candle> latest = repository.findFirstBySymbolAndTimeframeOrderByOpenTimeDesc(symbol, tf);
        Optional<Candle> earliest = repository.findFirstBySymbolAndTimeframeOrderByOpenTimeAsc(symbol, tf);
        if (latest.isEmpty() || earliest.isEmpty()) {
            fetchAndStore(symbol, tf, from, to);
            return;
        }
        // Live tick aggregation keeps the newest 1m candle fresh, which would otherwise hide the
        // fact that older history was never fetched — hence the separate head check.
        if (earliest.get().getOpenTime().isAfter(from.plusMillis(2 * step))
                && attempt(symbol, tf, "head", HEAD_RETRY)) {
            fetchAndStore(symbol, tf, from, earliest.get().getOpenTime());
        }
        if (latest.get().getOpenTime().isBefore(to.minusMillis(2 * step))
                && attempt(symbol, tf, "tail", tailRetry(tf))) {
            fetchAndStore(symbol, tf, latest.get().getOpenTime(), to);
        }
    }

    /** Head gaps usually mean the broker has no older data (e.g. listing date) — retry rarely. */
    private static final Duration HEAD_RETRY = Duration.ofHours(1);

    private static Duration tailRetry(Timeframe tf) {
        long half = tf.getMillis() / 2;
        return Duration.ofMillis(Math.max(30_000, Math.min(half, Duration.ofMinutes(15).toMillis())));
    }

    private final ConcurrentHashMap<String, Instant> lastAttempt = new ConcurrentHashMap<>();

    /** True (and records the attempt) unless the same gap was tried within {@code minGap}. */
    private boolean attempt(String symbol, Timeframe tf, String kind, Duration minGap) {
        Instant now = Instant.now();
        String key = symbol + "|" + tf.getCode() + "|" + kind;
        Instant prev = lastAttempt.get(key);
        if (prev != null && prev.plus(minGap).isAfter(now)) {
            return false;
        }
        lastAttempt.put(key, now);
        return true;
    }

    /** Fetch [from, to] from the data service and upsert it; returns the number of candles stored. */
    private int fetchAndStore(String symbol, Timeframe tf, Instant from, Instant to) {
        try {
            List<CandleDto> dtos = marketData.getHistorical(
                    symbol, tf.getInterval(), from.toEpochMilli(), to.toEpochMilli());
            if (dtos == null || dtos.isEmpty()) {
                return 0;
            }
            List<Candle> candles = dtos.stream()
                    .map(d -> new Candle(symbol, tf, Instant.ofEpochMilli(d.timestamp()),
                            d.open(), d.high(), d.low(), d.close(), d.volume()))
                    .toList();
            repository.saveAll(candles);
            log.debug("Backfilled {} {} candles for {}", candles.size(), tf.getCode(), symbol);
            return candles.size();
        } catch (Exception e) {
            log.warn("Backfill failed for {} {}: {}", symbol, tf.getCode(), e.getMessage());
            return -1;
        }
    }

    /**
     * Scheduled-sync entry point: make storage cover the last {@code lookback} for this symbol and
     * timeframe, fetching only the missing head and the tail since the newest stored bar
     * (ignoring the read-path throttle). Returns candles stored, or -1 if a fetch failed.
     */
    @Transactional
    public int sync(String symbolRaw, Timeframe tf, Duration lookback) {
        String symbol = symbolRaw.toUpperCase();
        Instant to = Instant.now();
        Instant from = to.minus(lookback);
        Optional<Candle> latest = repository.findFirstBySymbolAndTimeframeOrderByOpenTimeDesc(symbol, tf);
        Optional<Candle> earliest = repository.findFirstBySymbolAndTimeframeOrderByOpenTimeAsc(symbol, tf);
        if (latest.isEmpty() || earliest.isEmpty()) {
            return fetchAndStore(symbol, tf, from, to);
        }
        int stored = 0;
        boolean failed = false;
        if (earliest.get().getOpenTime().isAfter(from.plusMillis(2 * tf.getMillis()))) {
            int n = fetchAndStore(symbol, tf, from, earliest.get().getOpenTime());
            failed |= n < 0;
            stored += Math.max(0, n);
        }
        int n = fetchAndStore(symbol, tf, latest.get().getOpenTime(), to);
        failed |= n < 0;
        stored += Math.max(0, n);
        return failed ? -1 : stored;
    }

    /** Upsert a single candle (used by the live tick aggregator). */
    @Transactional
    public void upsert(Candle candle) {
        repository.save(candle);
    }

    public Optional<Candle> latest(String symbol, Timeframe tf) {
        return repository.findFirstBySymbolAndTimeframeOrderByOpenTimeDesc(symbol.toUpperCase(), tf);
    }
}
