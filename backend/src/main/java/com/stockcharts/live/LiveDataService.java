package com.stockcharts.live;

import com.stockcharts.config.AppProperties;
import com.stockcharts.marketdata.MarketDataClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Subscribes to the Python SSE tick stream, aggregates ticks into live candles, and
 * broadcasts each update to STOMP subscribers at /topic/ticks/{symbol}.
 */
@Service
public class LiveDataService {

    private static final Logger log = LoggerFactory.getLogger(LiveDataService.class);

    private final MarketDataClient marketData;
    private final TickAggregator aggregator;
    private final SimpMessagingTemplate messaging;
    private final AppProperties props;

    private Disposable subscription;

    public LiveDataService(MarketDataClient marketData, TickAggregator aggregator,
                           SimpMessagingTemplate messaging, AppProperties props) {
        this.marketData = marketData;
        this.aggregator = aggregator;
        this.messaging = messaging;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (props.getLiveSymbols() == null || props.getLiveSymbols().isEmpty()) {
            log.info("No live symbols configured; live stream disabled");
            return;
        }
        log.info("Starting live tick stream for {}", props.getLiveSymbols());
        subscription = marketData.streamTicks(props.getLiveSymbols())
                .doOnError(e -> log.warn("Tick stream error: {}", e.getMessage()))
                // Reconnect with backoff if the data service drops or restarts.
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(2))
                        .maxBackoff(Duration.ofSeconds(30)))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(this::handleTick,
                        e -> log.error("Live tick stream terminated", e));
    }

    private void handleTick(com.stockcharts.marketdata.dto.TickDto tick) {
        try {
            LiveUpdate update = aggregator.onTick(tick);
            messaging.convertAndSend("/topic/ticks/" + update.symbol(), update);
        } catch (Exception e) {
            log.debug("Failed to process tick for {}: {}", tick.symbol(), e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }

    /** Reconnect the live stream — used after the data source is switched at runtime. */
    public void restart() {
        stop();
        start();
    }
}
