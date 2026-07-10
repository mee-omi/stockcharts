package com.stockcharts.candle;

import com.stockcharts.config.AppProperties;
import com.stockcharts.marketdata.MarketDataClient;
import com.stockcharts.marketdata.dto.CandleDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    /** Fetch from the data service and upsert when storage is empty, stale, or missing history. */
    private void ensureCoverage(String symbol, Timeframe tf, Instant from, Instant to) {
        long step = tf.getMillis();
        Optional<Candle> latest = repository.findFirstBySymbolAndTimeframeOrderByOpenTimeDesc(symbol, tf);
        Optional<Candle> earliest = repository.findFirstBySymbolAndTimeframeOrderByOpenTimeAsc(symbol, tf);

        boolean stale = latest.isEmpty()
                || latest.get().getOpenTime().isBefore(to.minusMillis(2 * step));
        // Live tick aggregation keeps the newest 1m candle fresh, which would otherwise hide the
        // fact that older history was never fetched — so also backfill when the earliest stored
        // candle doesn't reach back to the requested `from`.
        boolean missingHistory = earliest.isEmpty()
                || earliest.get().getOpenTime().isAfter(from.plusMillis(2 * step));
        if (!stale && !missingHistory) {
            return;
        }
        try {
            List<CandleDto> dtos = marketData.getHistorical(
                    symbol, tf.getInterval(), from.toEpochMilli(), to.toEpochMilli());
            if (dtos == null || dtos.isEmpty()) {
                return;
            }
            List<Candle> candles = dtos.stream()
                    .map(d -> new Candle(symbol, tf, Instant.ofEpochMilli(d.timestamp()),
                            d.open(), d.high(), d.low(), d.close(), d.volume()))
                    .toList();
            repository.saveAll(candles);
            log.debug("Backfilled {} {} candles for {}", candles.size(), tf.getCode(), symbol);
        } catch (Exception e) {
            log.warn("Backfill failed for {} {}: {}", symbol, tf.getCode(), e.getMessage());
        }
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
