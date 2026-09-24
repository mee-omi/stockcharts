package com.stockcharts.screener;

import com.stockcharts.candle.Candle;
import com.stockcharts.candle.CandleService;
import com.stockcharts.candle.Timeframe;
import com.stockcharts.config.AppProperties;
import com.stockcharts.expr.CompiledExpr;
import com.stockcharts.expr.ExpressionException;
import com.stockcharts.expr.ExpressionService;
import com.stockcharts.indicator.CustomIndicatorService;
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

    private final UniverseService universes;
    private final CandleService candleService;
    private final ExpressionService expr;
    private final CustomIndicatorService customService;
    private final AppProperties props;
    private final Timer timer;

    public ScreenerService(UniverseService universes, CandleService candleService,
                           ExpressionService expr, CustomIndicatorService customService,
                           AppProperties props, MeterRegistry meters) {
        this.universes = universes;
        this.candleService = candleService;
        this.expr = expr;
        this.customService = customService;
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

        UniverseService.Resolved universe = universes.resolve(req.universe());
        Map<String, String> nameBySymbol = universe.names();
        boolean[] truncatedUniverse = {universe.truncated()};
        List<String> symbols = universe.symbols();
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

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
