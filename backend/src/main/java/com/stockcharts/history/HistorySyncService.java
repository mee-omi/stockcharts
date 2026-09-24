package com.stockcharts.history;

import com.stockcharts.candle.CandleService;
import com.stockcharts.candle.Timeframe;
import com.stockcharts.config.AppProperties;
import com.stockcharts.instrument.InstrumentService;
import com.stockcharts.marketdata.MarketDataClient;
import com.stockcharts.marketdata.dto.ProviderInfo;
import com.stockcharts.screener.IndexUniverses;
import com.stockcharts.screener.Watchlist;
import com.stockcharts.screener.WatchlistRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the local candle store filled from the active broker: after each trading day
 * ({@code stockcharts.history-sync.cron}, default 15:45 IST Mon–Fri) and once after startup, every
 * tracked symbol (watchlists + configured index universes + live symbols) is brought up to date
 * per configured timeframe. Syncs are incremental — only the gap since the newest stored bar (and
 * any missing history at the start of the lookback) is downloaded — and run one at a time on a
 * background thread.
 */
@Service
public class HistorySyncService {

    private static final Logger log = LoggerFactory.getLogger(HistorySyncService.class);
    static final int MAX_FAILURES_REPORTED = 20;

    /** Snapshot of the current or last run. */
    public record Status(boolean running, Instant startedAt, Instant finishedAt, String trigger, String provider,
                         int symbols, int done, long candlesStored, int failures, List<String> failureSamples,
                         String skippedReason) {
    }

    public record Plan(List<String> symbols, Map<Timeframe, Duration> timeframes) {
    }

    private final CandleService candles;
    private final InstrumentService instruments;
    private final WatchlistRepository watchlists;
    private final MarketDataClient marketData;
    private final AppProperties props;
    private final Counter storedCounter;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "history-sync");
        t.setDaemon(true);
        return t;
    });
    private volatile Status status = new Status(false, null, null, null, null, 0, 0, 0, 0, List.of(), null);

    public HistorySyncService(CandleService candles, InstrumentService instruments, WatchlistRepository watchlists,
                              MarketDataClient marketData, AppProperties props, MeterRegistry meters) {
        this.candles = candles;
        this.instruments = instruments;
        this.watchlists = watchlists;
        this.marketData = marketData;
        this.props = props;
        this.storedCounter = Counter.builder("stockcharts.history.candles.stored")
                .description("Candles stored by the scheduled history sync").register(meters);
    }

    public Status status() {
        return status;
    }

    /** Parses "1d:1825,1m:30" into timeframe → lookback. */
    static Map<Timeframe, Duration> parseTimeframes(String spec) {
        Map<Timeframe, Duration> out = new LinkedHashMap<>();
        for (String part : spec == null ? new String[0] : spec.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            String[] kv = p.split(":");
            if (kv.length != 2) {
                throw new IllegalArgumentException("Bad history-sync timeframe '" + p + "' (expected tf:days)");
            }
            out.put(Timeframe.fromCode(kv[0].trim()), Duration.ofDays(Long.parseLong(kv[1].trim())));
        }
        return out;
    }

    /** Symbols to sync: watchlists, configured index constituents, configured live symbols. */
    public Plan plan() {
        AppProperties.HistorySync cfg = props.getHistorySync();
        LinkedHashSet<String> symbols = new LinkedHashSet<>();
        for (Watchlist w : watchlists.findAll()) {
            symbols.addAll(w.getSymbols());
        }
        for (String index : cfg.getIndices()) {
            IndexUniverses.find(index).ifPresent(d -> symbols.addAll(d.symbols()));
        }
        if (props.getLiveSymbols() != null) {
            props.getLiveSymbols().forEach(s -> symbols.add(s.trim().toUpperCase()));
        }
        List<String> known = symbols.stream().filter(instruments::exists).limit(cfg.getMaxSymbols()).toList();
        return new Plan(known, parseTimeframes(cfg.getTimeframes()));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        AppProperties.HistorySync cfg = props.getHistorySync();
        if (cfg.isEnabled() && cfg.isOnStartup()) {
            // Give the instrument sync (also on startup) a head start.
            executor.schedule(() -> runNow("startup"), 30, TimeUnit.SECONDS);
        }
    }

    @Scheduled(cron = "${stockcharts.history-sync.cron:0 45 15 * * MON-FRI}", zone = "Asia/Kolkata")
    @SchedulerLock(name = "historySync", lockAtMostFor = "PT3H")
    public void scheduled() {
        if (props.getHistorySync().isEnabled()) {
            runNow("schedule");
        }
    }

    /** Start a run in the background; false if one is already running. */
    public boolean trigger(String reason) {
        if (running.get()) {
            return false;
        }
        executor.execute(() -> runNow(reason));
        return true;
    }

    /** Run synchronously (used by the scheduler, the trigger, and tests). */
    public Status runNow(String trigger) {
        if (!running.compareAndSet(false, true)) {
            return status;
        }
        Instant started = Instant.now();
        try {
            String provider = activeProvider();
            if ("mock".equals(provider) && !props.getHistorySync().isIncludeMock()) {
                status = new Status(false, started, Instant.now(), trigger, provider, 0, 0, 0, 0, List.of(),
                        "mock provider generates history on demand (set include-mock to sync anyway)");
                return status;
            }
            Plan plan = plan();
            List<String> failures = Collections.synchronizedList(new ArrayList<>());
            long stored = 0;
            int failed = 0;
            int done = 0;
            status = new Status(true, started, null, trigger, provider, plan.symbols().size(), 0, 0, 0, List.of(), null);
            for (String symbol : plan.symbols()) {
                for (Map.Entry<Timeframe, Duration> e : plan.timeframes().entrySet()) {
                    int n = candles.sync(symbol, e.getKey(), e.getValue());
                    if (n < 0) {
                        failed++;
                        if (failures.size() < MAX_FAILURES_REPORTED) {
                            failures.add(symbol + " " + e.getKey().getCode());
                        }
                    } else {
                        stored += n;
                        storedCounter.increment(n);
                    }
                }
                done++;
                status = new Status(true, started, null, trigger, provider, plan.symbols().size(), done, stored,
                        failed, List.copyOf(failures), null);
            }
            status = new Status(false, started, Instant.now(), trigger, provider, plan.symbols().size(), done, stored,
                    failed, List.copyOf(failures), null);
            log.info("History sync ({}) finished: {} symbols, {} candles stored, {} failures",
                    trigger, done, stored, failed);
            return status;
        } catch (Exception e) {
            log.warn("History sync ({}) failed: {}", trigger, e.getMessage());
            status = new Status(false, started, Instant.now(), trigger, null, 0, 0, 0, 1, List.of(e.getMessage()), null);
            return status;
        } finally {
            running.set(false);
        }
    }

    private String activeProvider() {
        try {
            ProviderInfo info = marketData.getProvider();
            return info == null ? null : info.active();
        } catch (Exception e) {
            return null;
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
