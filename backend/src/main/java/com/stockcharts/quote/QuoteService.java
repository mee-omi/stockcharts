package com.stockcharts.quote;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stockcharts.candle.Candle;
import com.stockcharts.candle.CandleService;
import com.stockcharts.candle.Timeframe;
import com.stockcharts.instrument.Instrument;
import com.stockcharts.instrument.InstrumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Last price and day change per symbol, for the watchlist panel. Derived from stored candles: the
 * newest 1m bar (kept fresh by the live tick aggregator) or the latest daily close, against the
 * previous daily close. Results are cached briefly so a panel polling many symbols stays cheap.
 */
@Service
public class QuoteService {

    private static final Logger log = LoggerFactory.getLogger(QuoteService.class);
    public static final int MAX_SYMBOLS = 100;

    public record Quote(String symbol, String name, String type, Double ltp, Double prevClose,
                        Double change, Double changePct, Long time) {
    }

    private final CandleService candles;
    private final InstrumentRepository instruments;
    private final Cache<String, Quote> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(2))
            .maximumSize(5_000)
            .build();

    public QuoteService(CandleService candles, InstrumentRepository instruments) {
        this.candles = candles;
        this.instruments = instruments;
    }

    public List<Quote> quotes(List<String> symbolsRaw) {
        LinkedHashSet<String> symbols = new LinkedHashSet<>();
        for (String s : symbolsRaw) {
            if (s != null && !s.isBlank()) {
                symbols.add(s.trim().toUpperCase());
            }
        }
        if (symbols.size() > MAX_SYMBOLS) {
            throw new IllegalArgumentException("At most " + MAX_SYMBOLS + " symbols per request");
        }
        List<Quote> out = new ArrayList<>(symbols.size());
        for (String sym : symbols) {
            Optional<Instrument> inst = instruments.findById(sym);
            if (inst.isEmpty()) {
                continue; // unknown symbols are skipped rather than failing the whole panel
            }
            out.add(cache.get(sym, k -> compute(inst.get())));
        }
        return out;
    }

    private Quote compute(Instrument inst) {
        String sym = inst.getSymbol();
        Double ltp = null;
        Double prevClose = null;
        Long time = null;
        try {
            Instant now = Instant.now();
            List<Candle> daily = candles.getCandles(sym, Timeframe.ONE_DAY, now.minus(Duration.ofDays(10)), now);
            if (!daily.isEmpty()) {
                Candle last = daily.get(daily.size() - 1);
                ltp = last.getClose();
                time = last.getOpenTime().getEpochSecond();
                if (daily.size() >= 2) {
                    prevClose = daily.get(daily.size() - 2).getClose();
                }
            }
            Optional<Candle> minute = candles.latest(sym, Timeframe.ONE_MINUTE);
            if (minute.isPresent() && (time == null || minute.get().getOpenTime().getEpochSecond() >= time)) {
                ltp = minute.get().getClose();
                time = minute.get().getOpenTime().getEpochSecond();
            }
        } catch (Exception e) {
            log.debug("Quote failed for {}: {}", sym, e.getMessage());
        }
        Double change = ltp != null && prevClose != null ? round(ltp - prevClose) : null;
        Double pct = change != null && prevClose != 0 ? round(change / prevClose * 100) : null;
        return new Quote(sym, inst.getName(), inst.getInstrumentType(), ltp, prevClose, change, pct, time);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
