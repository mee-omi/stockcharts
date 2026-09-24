package com.stockcharts.alert;

import com.stockcharts.candle.Candle;
import com.stockcharts.candle.CandleService;
import com.stockcharts.candle.Timeframe;
import com.stockcharts.expr.CompiledExpr;
import com.stockcharts.indicator.SeriesEvaluator;
import com.stockcharts.instrument.InstrumentService;
import com.stockcharts.ta.PriceSeries;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * CRUD for alerts plus the periodic evaluator. Active alerts are grouped by (symbol, timeframe)
 * so each series is loaded once per cycle; each alert's expression is evaluated at the latest bar.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    static final int MAX_ACTIVE = 500;

    private final AlertRepository alerts;
    private final AlertEventRepository events;
    private final CandleService candles;
    private final InstrumentService instruments;
    private final SeriesEvaluator evaluator;
    private final AlertNotifier notifier;
    private final Counter triggered;

    public AlertService(AlertRepository alerts, AlertEventRepository events, CandleService candles,
                        InstrumentService instruments, SeriesEvaluator evaluator, AlertNotifier notifier,
                        MeterRegistry meters) {
        this.alerts = alerts;
        this.events = events;
        this.candles = candles;
        this.instruments = instruments;
        this.evaluator = evaluator;
        this.notifier = notifier;
        this.triggered = Counter.builder("stockcharts.alerts.triggered")
                .description("Alerts fired").register(meters);
    }

    // ---- CRUD ----

    public List<Alert> list() {
        return alerts.findAllByOrderByCreatedAtDesc();
    }

    public Alert get(long id) {
        return alerts.findById(id).orElseThrow(() -> new NoSuchElementException("Alert not found: " + id));
    }

    @Transactional
    public Alert create(AlertDtos.AlertRequest req) {
        validate(req);
        if (alerts.findByStatus(Alert.Status.ACTIVE).size() >= MAX_ACTIVE) {
            throw new IllegalArgumentException("Too many active alerts (max " + MAX_ACTIVE + ")");
        }
        String symbol = req.symbol().trim().toUpperCase();
        String name = req.name() == null || req.name().isBlank() ? symbol + ": " + req.expression().trim() : req.name().trim();
        Alert a = new Alert(truncate(name, 128), symbol, Timeframe.fromCode(req.timeframe()).getCode(),
                req.expression().trim(),
                req.triggerMode() == null ? Alert.TriggerMode.ONCE : req.triggerMode());
        return alerts.save(a);
    }

    @Transactional
    public Alert update(long id, AlertDtos.AlertRequest req) {
        validate(req);
        Alert a = get(id);
        a.setName(req.name() == null || req.name().isBlank() ? a.getName() : truncate(req.name().trim(), 128));
        a.setSymbol(req.symbol().trim().toUpperCase());
        a.setTimeframe(Timeframe.fromCode(req.timeframe()).getCode());
        a.setExpression(req.expression().trim());
        if (req.triggerMode() != null) {
            a.setTriggerMode(req.triggerMode());
        }
        a.rearm();
        return alerts.save(a);
    }

    @Transactional
    public Alert setStatus(long id, Alert.Status status) {
        Alert a = get(id);
        if (status == Alert.Status.ACTIVE) {
            a.rearm();
        } else {
            a.setStatus(status);
        }
        return alerts.save(a);
    }

    @Transactional
    public void delete(long id) {
        if (!alerts.existsById(id)) {
            throw new NoSuchElementException("Alert not found: " + id);
        }
        alerts.deleteById(id);
    }

    public List<AlertEvent> recentEvents(int limit) {
        return events.findAllByOrderByTriggeredAtDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 500))));
    }

    private void validate(AlertDtos.AlertRequest req) {
        if (req.symbol() == null || req.symbol().isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (!instruments.exists(req.symbol().trim())) {
            throw new NoSuchElementException("Unknown symbol: " + req.symbol());
        }
        if (req.expression() == null || req.expression().isBlank()) {
            throw new IllegalArgumentException("expression is required");
        }
        Timeframe.fromCode(req.timeframe() == null ? "" : req.timeframe());
        evaluator.compileValidated(req.expression());
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ---- evaluation ----

    @Scheduled(fixedDelayString = "${stockcharts.alerts.interval:PT15S}",
            initialDelayString = "${stockcharts.alerts.initial-delay:PT20S}")
    @SchedulerLock(name = "alertEvaluation", lockAtMostFor = "PT5M")
    public void scheduledEvaluation() {
        try {
            evaluateAll();
        } catch (Exception e) {
            log.warn("Alert evaluation cycle failed: {}", e.getMessage());
        }
    }

    /** Evaluate every active alert once; returns the events fired in this cycle. */
    public List<AlertEvent> evaluateAll() {
        List<Alert> active = alerts.findByStatus(Alert.Status.ACTIVE);
        if (active.isEmpty()) {
            return List.of();
        }
        Map<String, List<Alert>> groups = new LinkedHashMap<>();
        for (Alert a : active) {
            groups.computeIfAbsent(a.getSymbol() + "|" + a.getTimeframe(), k -> new ArrayList<>()).add(a);
        }
        List<AlertEvent> fired = new ArrayList<>();
        for (List<Alert> group : groups.values()) {
            Alert first = group.get(0);
            List<Candle> series;
            try {
                series = candles.getCandles(first.getSymbol(), Timeframe.fromCode(first.getTimeframe()), null, null);
            } catch (Exception e) {
                log.debug("Alert data load failed for {}: {}", first.getSymbol(), e.getMessage());
                continue;
            }
            if (series.isEmpty()) {
                continue;
            }
            PriceSeries s = PriceSeries.of(series);
            int last = s.size() - 1;
            Instant barTime = series.get(last).getOpenTime();
            for (Alert a : group) {
                try {
                    CompiledExpr compiled = evaluator.compileValidated(a.getExpression());
                    double[] result = evaluator.evaluate(compiled, s);
                    if (SeriesEvaluator.truthy(result, last) && a.shouldFire(barTime)) {
                        fired.add(fire(a, s.close()[last], barTime));
                    }
                } catch (Exception e) {
                    // e.g. a referenced custom indicator was deleted — pause rather than spam errors.
                    log.warn("Pausing alert {} ({}): {}", a.getId(), a.getName(), e.getMessage());
                    a.setStatus(Alert.Status.PAUSED);
                    alerts.save(a);
                }
            }
        }
        return fired;
    }

    private AlertEvent fire(Alert a, double price, Instant barTime) {
        Instant now = Instant.now();
        a.markTriggered(now, barTime);
        alerts.save(a);
        AlertEvent event = events.save(new AlertEvent(a, price, barTime, now));
        triggered.increment();
        log.info("Alert fired: {} {} @ {}", a.getName(), a.getSymbol(), price);
        try {
            notifier.notify(AlertDtos.AlertEventDto.from(event));
        } catch (Exception e) {
            log.warn("Alert notification failed: {}", e.getMessage());
        }
        return event;
    }
}
