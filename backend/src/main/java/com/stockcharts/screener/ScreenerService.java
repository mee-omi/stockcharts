package com.stockcharts.screener;

import com.stockcharts.candle.Candle;
import com.stockcharts.candle.CandleService;
import com.stockcharts.candle.Timeframe;
import com.stockcharts.config.AppProperties;
import com.stockcharts.expr.CompiledExpr;
import com.stockcharts.expr.ExpressionException;
import com.stockcharts.expr.ExpressionService;
import com.stockcharts.indicator.CustomIndicatorService;
import com.stockcharts.instrument.Instrument;
import com.stockcharts.instrument.InstrumentRepository;
import com.stockcharts.screener.dto.ScreenerRequest;
import com.stockcharts.screener.dto.ScreenerResult;
import com.stockcharts.ta.PriceSeries;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

/**
 * Runs a screener: resolves the symbol universe, then evaluates the boolean expression at the
 * latest bar of each symbol's candles in parallel, collecting matches. Heavy ("all NSE") scans
 * are bounded by {@code stockcharts.screener.max-universe} and a result limit.
 */
@Service
public class ScreenerService {

    private static final Logger log = LoggerFactory.getLogger(ScreenerService.class);

    private final InstrumentRepository instruments;
    private final CandleService candleService;
    private final ExpressionService expr;
    private final CustomIndicatorService customService;
    private final WatchlistRepository watchlists;
    private final AppProperties props;
    private final Timer timer;

    public ScreenerService(InstrumentRepository instruments, CandleService candleService,
                           ExpressionService expr, CustomIndicatorService customService,
                           WatchlistRepository watchlists, AppProperties props, MeterRegistry meters) {
        this.instruments = instruments;
        this.candleService = candleService;
        this.expr = expr;
        this.customService = customService;
        this.watchlists = watchlists;
        this.props = props;
        this.timer = Timer.builder("stockcharts.screener.duration")
                .description("Screener run time").register(meters);
    }

    private record Column(String label, CompiledExpr compiled) {
    }

    public ScreenerResult run(ScreenerRequest req) {
        return timer.record(() -> doRun(req));
    }

    private ScreenerResult doRun(ScreenerRequest req) {
        if (req.expression() == null || req.expression().isBlank()) {
            throw new IllegalArgumentException("Screener expression is required");
        }
        Timeframe tf = Timeframe.fromCode(req.timeframe() == null ? "1d" : req.timeframe());
        int limit = req.limit() != null ? req.limit() : props.getScreener().getResultLimit();

        // Validate the expression once, up front.
        ExpressionService.ValidationResult v = expr.validate(req.expression(), customService.existingNames());
        if (!v.valid()) {
            throw new ExpressionException(v.error());
        }
        CompiledExpr compiled = expr.compile(req.expression());
        Set<String> customRefs = compiled.customReferences();

        List<Column> columns = new ArrayList<>();
        if (req.columns() != null) {
            for (ScreenerRequest.Column c : req.columns()) {
                columns.add(new Column(c.label(), expr.compile(c.expression())));
            }
        }

        List<Instrument> all = instruments.findAll();
        Map<String, String> nameBySymbol = all.stream()
                .collect(Collectors.toMap(Instrument::getSymbol, Instrument::getName, (a, b) -> a));
        Set<String> equities = all.stream().filter(i -> !i.isIndex())
                .map(Instrument::getSymbol).collect(Collectors.toSet());

        boolean[] truncatedUniverse = {false};
        List<String> symbols = resolveUniverse(req.universe(), nameBySymbol.keySet(), equities, truncatedUniverse);
        if (symbols.isEmpty()) {
            return new ScreenerResult(0, 0, truncatedUniverse[0], List.of());
        }

        List<ScreenerResult.Match> matches = evaluateParallel(symbols, tf, compiled, customRefs, columns, nameBySymbol);
        matches.sort(Comparator.comparing(ScreenerResult.Match::symbol));

        int matchedTotal = matches.size();
        boolean truncated = truncatedUniverse[0] || matchedTotal > limit;
        if (matchedTotal > limit) {
            matches = matches.subList(0, limit);
        }
        log.info("Screener scanned {} symbols, matched {}", symbols.size(), matchedTotal);
        return new ScreenerResult(symbols.size(), matchedTotal, truncated, matches);
    }

    private List<ScreenerResult.Match> evaluateParallel(
            List<String> symbols, Timeframe tf, CompiledExpr compiled,
            Set<String> customRefs, List<Column> columns, Map<String, String> names) {
        int parallelism = Math.max(1, Math.min(props.getScreener().getParallelism(),
                Runtime.getRuntime().availableProcessors()));
        ForkJoinPool pool = new ForkJoinPool(parallelism);
        try {
            return pool.submit(() -> symbols.parallelStream()
                    .map(sym -> evaluateSymbol(sym, tf, compiled, customRefs, columns, names))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList())).get();
        } catch (Exception e) {
            throw new IllegalStateException("Screener evaluation failed: " + e.getMessage(), e);
        } finally {
            pool.shutdown();
        }
    }

    private ScreenerResult.Match evaluateSymbol(
            String symbol, Timeframe tf, CompiledExpr compiled, Set<String> customRefs,
            List<Column> columns, Map<String, String> names) {
        try {
            List<Candle> candles = candleService.getCandles(symbol, tf, null, null);
            if (candles.isEmpty()) {
                return null;
            }
            PriceSeries s = PriceSeries.of(candles);
            Map<String, double[]> customs = new HashMap<>();
            for (String ref : customRefs) {
                customs.put(ref, customService.compute(ref, s));
            }
            double[] result = expr.evaluate(compiled, s, customs);
            int last = s.size() - 1;
            double pass = result[last];
            if (!Double.isFinite(pass) || pass <= 0.5) {
                return null;
            }
            Map<String, Double> cols = new LinkedHashMap<>();
            for (Column col : columns) {
                double[] cv = expr.evaluate(col.compiled(), s, customs);
                double x = cv[last];
                cols.put(col.label(), Double.isFinite(x) ? round(x) : null);
            }
            return new ScreenerResult.Match(symbol, names.getOrDefault(symbol, symbol),
                    round(s.close()[last]), cols);
        } catch (Exception e) {
            log.debug("Screener skip {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    private List<String> resolveUniverse(ScreenerRequest.Universe u, Set<String> known, Set<String> equities,
                                         boolean[] truncated) {
        String type = u == null || u.type() == null ? "index" : u.type().toLowerCase();
        String value = u == null ? null : u.value();
        List<String> symbols = switch (type) {
            case "all" -> equities.stream().sorted().collect(Collectors.toList());
            case "index" -> IndexUniverses.find(value)
                    .map(d -> d.symbols().stream().sorted().collect(Collectors.toList()))
                    .orElseThrow(() -> new IllegalArgumentException("Unknown index universe: " + value));
            case "list" -> value == null ? List.of()
                    : java.util.Arrays.stream(value.split(","))
                    .map(x -> x.trim().toUpperCase()).filter(x -> !x.isEmpty()).toList();
            case "watchlist" -> watchlists.findById(value == null ? "" : value)
                    .map(Watchlist::getSymbols)
                    .orElseThrow(() -> new java.util.NoSuchElementException("Watchlist not found: " + value));
            default -> throw new IllegalArgumentException("Unknown universe type: " + type);
        };
        // Keep only symbols we actually have instruments for.
        List<String> filtered = symbols.stream().filter(known::contains).distinct().collect(Collectors.toList());
        int max = props.getScreener().getMaxUniverse();
        if (filtered.size() > max) {
            truncated[0] = true;
            filtered = filtered.subList(0, max);
        }
        return filtered;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
