package com.stockcharts.live;

import com.stockcharts.config.AppProperties;
import com.stockcharts.instrument.InstrumentService;
import com.stockcharts.marketdata.MarketDataClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Subscribes to the Python SSE tick stream, aggregates ticks into live candles, and
 * broadcasts each update to STOMP subscribers at /topic/ticks/{symbol}.
 *
 * <p>The streamed set is the configured {@code live-symbols} plus any symbol a client subscribes
 * to (see {@link LiveSubscriptionListener}), capped at {@code live-max-symbols}. Adding a symbol
 * reconnects the stream, debounced so a watchlist subscribing to many symbols at once causes a
 * single reconnect.
 */
@Service
public class LiveDataService {

    private static final Logger log = LoggerFactory.getLogger(LiveDataService.class);
    private static final long RESTART_DEBOUNCE_MS = 750;

    private final MarketDataClient marketData;
    private final TickAggregator aggregator;
    private final SimpMessagingTemplate messaging;
    private final AppProperties props;
    private final InstrumentService instruments;
    private final LastPrices lastPrices;
    private final ApplicationEventPublisher events;
    private final Counter tickCounter;

    private final Set<String> dynamicSymbols = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean restartPending = new AtomicBoolean(false);
    private final ScheduledExecutorService restartExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "live-restart");
        t.setDaemon(true);
        return t;
    });

    private Disposable subscription;

    public LiveDataService(MarketDataClient marketData, TickAggregator aggregator,
                           SimpMessagingTemplate messaging, AppProperties props,
                           InstrumentService instruments, MeterRegistry meters, LastPrices lastPrices,
                           ApplicationEventPublisher events) {
        this.marketData = marketData;
        this.aggregator = aggregator;
        this.messaging = messaging;
        this.props = props;
        this.instruments = instruments;
        this.lastPrices = lastPrices;
        this.events = events;
        this.tickCounter = Counter.builder("stockcharts.live.ticks")
                .description("Live ticks received from the data service").register(meters);
    }

    /** Configured symbols first, then client-requested ones. */
    public List<String> streamedSymbols() {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        if (props.getLiveSymbols() != null) {
            props.getLiveSymbols().forEach(s -> all.add(s.trim().toUpperCase()));
        }
        all.addAll(dynamicSymbols);
        return new ArrayList<>(all);
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!props.isLiveEnabled()) {
            log.info("Live ingestion disabled on this instance (stockcharts.live-enabled=false)");
            return;
        }
        List<String> symbols = streamedSymbols();
        if (symbols.isEmpty()) {
            log.info("No live symbols configured or requested; live stream idle");
            return;
        }
        log.info("Starting live tick stream for {} symbols", symbols.size());
        subscription = marketData.streamTicks(symbols)
                .doOnError(e -> log.warn("Tick stream error: {}", e.getMessage()))
                // Reconnect with backoff if the data service drops or restarts.
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(2))
                        .maxBackoff(Duration.ofSeconds(30)))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(this::handleTick,
                        e -> log.error("Live tick stream terminated", e));
    }

    /**
     * Ask for live ticks on {@code symbolRaw}. Unknown symbols and requests beyond the cap are
     * ignored. Returns true if the symbol is (now) being streamed.
     */
    public boolean request(String symbolRaw) {
        if (symbolRaw == null || symbolRaw.isBlank() || !props.isLiveEnabled()) {
            return false;
        }
        String symbol = symbolRaw.trim().toUpperCase();
        if (streamedSymbols().contains(symbol)) {
            return true;
        }
        if (dynamicSymbols.size() >= props.getLiveMaxSymbols()) {
            log.debug("Live symbol cap ({}) reached; not streaming {}", props.getLiveMaxSymbols(), symbol);
            return false;
        }
        if (!instruments.exists(symbol)) {
            return false;
        }
        if (dynamicSymbols.add(symbol)) {
            scheduleRestart();
        }
        return true;
    }

    private void scheduleRestart() {
        if (restartPending.compareAndSet(false, true)) {
            restartExecutor.schedule(() -> {
                restartPending.set(false);
                restart();
            }, RESTART_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void handleTick(com.stockcharts.marketdata.dto.TickDto tick) {
        tickCounter.increment();
        try {
            LiveUpdate update = aggregator.onTick(tick);
            messaging.convertAndSend("/topic/ticks/" + update.symbol(), update);
            PriceTick price = new PriceTick(update.symbol(), tick.ltp(),
                    tick.timestamp() > 0 ? tick.timestamp() : System.currentTimeMillis());
            lastPrices.update(price);
            events.publishEvent(price);
        } catch (Exception e) {
            log.debug("Failed to process tick for {}: {}", tick.symbol(), e.getMessage());
        }
    }

    public synchronized void stop() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
        subscription = null;
    }

    /** Reconnect the live stream — used after the data source or the symbol set changes. */
    public synchronized void restart() {
        stop();
        start();
    }

    /** Forget client-requested symbols (the instrument universe changed). */
    public void resetRequestedSymbols() {
        dynamicSymbols.clear();
    }

    @PreDestroy
    void shutdown() {
        restartExecutor.shutdownNow();
        stop();
    }
}
