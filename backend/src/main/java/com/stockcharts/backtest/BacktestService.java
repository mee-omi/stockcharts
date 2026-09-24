package com.stockcharts.backtest;

import com.stockcharts.backtest.BacktestDtos.BacktestRequest;
import com.stockcharts.backtest.BacktestDtos.BacktestResult;
import com.stockcharts.backtest.BacktestDtos.Direction;
import com.stockcharts.backtest.BacktestDtos.ScanRequest;
import com.stockcharts.backtest.BacktestDtos.ScanResult;
import com.stockcharts.backtest.BacktestDtos.ScanRow;
import com.stockcharts.backtest.BacktestDtos.ScanSort;
import com.stockcharts.backtest.BacktestDtos.SignalState;
import com.stockcharts.candle.Candle;
import com.stockcharts.candle.CandleService;
import com.stockcharts.candle.Timeframe;
import com.stockcharts.config.AppProperties;
import com.stockcharts.expr.CompiledExpr;
import com.stockcharts.indicator.SeriesEvaluator;
import com.stockcharts.instrument.InstrumentService;
import com.stockcharts.screener.UniverseService;
import com.stockcharts.ta.PriceSeries;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

/**
 * Loads candles, evaluates the entry/exit rules and runs {@link BacktestEngine} — for one symbol,
 * or ({@link #scan}) for every symbol of a universe in parallel. Candles go through the
 * read-through cache, which re-fetches stale ranges, so each run uses the latest available bars.
 */
@Service
public class BacktestService {

    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);

    static final int MIN_BARS = 30;
    static final int MAX_BARS = 100_000;
    static final int MAX_SCAN_ERRORS = 10;
    /** NSE cash session: 09:15–15:30 = 375 minutes, ~252 sessions a year. */
    private static final double SESSION_MINUTES = 375;
    private static final double SESSIONS_PER_YEAR = 252;

    private final CandleService candles;
    private final InstrumentService instruments;
    private final SeriesEvaluator evaluator;
    private final UniverseService universes;
    private final AppProperties props;
    private final Timer timer;
    private final Timer scanTimer;

    public BacktestService(CandleService candles, InstrumentService instruments, SeriesEvaluator evaluator,
                           UniverseService universes, AppProperties props, MeterRegistry meters) {
        this.candles = candles;
        this.instruments = instruments;
        this.evaluator = evaluator;
        this.universes = universes;
        this.props = props;
        this.timer = Timer.builder("stockcharts.backtest.duration")
                .description("Backtest run time").register(meters);
        this.scanTimer = Timer.builder("stockcharts.backtest.scan.duration")
                .description("Backtest scan run time").register(meters);
    }

    /** A validated, compiled strategy, reusable across symbols. */
    record Prepared(Timeframe tf, Instant from, Instant to, CompiledExpr entry, CompiledExpr exit,
                    BacktestEngine.Config cfg) {
    }

    public BacktestResult run(BacktestRequest req) {
        return timer.record(() -> {
            if (req.symbol() == null || req.symbol().isBlank()) {
                throw new IllegalArgumentException("symbol is required");
            }
            if (!instruments.exists(req.symbol())) {
                throw new NoSuchElementException("Unknown symbol: " + req.symbol());
            }
            Prepared p = prepare(req);
            List<Candle> data = candles.getCandles(req.symbol(), p.tf(), p.from(), p.to());
            if (data.size() < MIN_BARS) {
                throw new IllegalArgumentException("Not enough data: " + data.size() + " bars (need " + MIN_BARS + ")");
            }
            return execute(req.symbol().toUpperCase(), p, data);
        });
    }

    /** Backtest one strategy across a universe; rank, filter and report each symbol's latest signal. */
    public ScanResult scan(ScanRequest req) {
        return scanTimer.record(() -> doScan(req));
    }

    private ScanResult doScan(ScanRequest req) {
        if (req.strategy() == null) {
            throw new IllegalArgumentException("strategy is required");
        }
        long started = System.currentTimeMillis();
        Prepared p = prepare(req.strategy());
        UniverseService.Resolved universe = universes.resolve(req.universe());
        List<String> symbols = universe.symbols();

        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        List<ScanRow> rows = parallel(symbols, sym -> {
            try {
                List<Candle> data = candles.getCandles(sym, p.tf(), p.from(), p.to());
                if (data.size() < MIN_BARS) {
                    note(errors, sym + ": only " + data.size() + " bars");
                    return null;
                }
                BacktestResult r = execute(sym, p, data);
                return new ScanRow(sym, universe.names().getOrDefault(sym, sym), r.latest(), r.metrics());
            } catch (Exception e) {
                note(errors, sym + ": " + e.getMessage());
                return null;
            }
        });

        int evaluated = rows.size();
        Long dataAsOf = rows.stream().map(r -> r.latest().time()).max(Long::compare).orElse(null);
        List<ScanRow> matched = rows.stream().filter(r -> passes(r, req)).sorted(order(req.sortBy()))
                .collect(Collectors.toList());
        int limit = req.limit() != null && req.limit() > 0 ? req.limit() : props.getScreener().getResultLimit();
        List<ScanRow> page = matched.size() > limit ? matched.subList(0, limit) : matched;

        log.info("Backtest scan: {} symbols, {} evaluated, {} matched", symbols.size(), evaluated, matched.size());
        return new ScanResult(p.tf().getCode(), symbols.size(), evaluated, matched.size(),
                symbols.size() - evaluated, universe.truncated() || matched.size() > limit, dataAsOf,
                System.currentTimeMillis() - started, page, List.copyOf(errors));
    }

    private static void note(List<String> errors, String msg) {
        if (errors.size() < MAX_SCAN_ERRORS) {
            errors.add(msg);
        }
    }

    private <T> List<T> parallel(List<String> symbols, java.util.function.Function<String, T> task) {
        int parallelism = Math.max(1, Math.min(props.getScreener().getParallelism(),
                Runtime.getRuntime().availableProcessors()));
        ForkJoinPool pool = new ForkJoinPool(parallelism);
        try {
            return pool.submit(() -> symbols.parallelStream().map(task).filter(Objects::nonNull)
                    .collect(Collectors.toList())).get();
        } catch (Exception e) {
            throw new IllegalStateException("Scan failed: " + e.getMessage(), e);
        } finally {
            pool.shutdown();
        }
    }

    static boolean passes(ScanRow r, ScanRequest req) {
        BacktestDtos.Metrics m = r.metrics();
        if (req.minTrades() != null && m.trades() < req.minTrades()) {
            return false;
        }
        if (req.minWinRatePct() != null && m.winRatePct() < req.minWinRatePct()) {
            return false;
        }
        if (req.minProfitFactor() != null && profitFactor(m) < req.minProfitFactor()) {
            return false;
        }
        if (Boolean.TRUE.equals(req.onlySignals())) {
            SignalState s = r.latest().signal();
            return s == SignalState.ENTRY || s == SignalState.EXIT;
        }
        return true;
    }

    /** No losing trades means an unbounded profit factor (if there were wins at all). */
    private static double profitFactor(BacktestDtos.Metrics m) {
        if (m.profitFactor() != null) {
            return m.profitFactor();
        }
        return m.wins() > 0 ? Double.POSITIVE_INFINITY : 0;
    }

    static Comparator<ScanRow> order(ScanSort sort) {
        Comparator<ScanRow> c = switch (sort == null ? ScanSort.TOTAL_RETURN : sort) {
            case NET_PROFIT -> Comparator.comparingDouble(r -> -r.metrics().netProfit());
            case WIN_RATE -> Comparator.comparingDouble(r -> -r.metrics().winRatePct());
            case PROFIT_FACTOR -> Comparator.comparingDouble(r -> -profitFactor(r.metrics()));
            case SHARPE -> Comparator.comparingDouble(r -> -r.metrics().sharpe());
            case MAX_DRAWDOWN -> Comparator.comparingDouble(r -> r.metrics().maxDrawdownPct());
            case TRADES -> Comparator.comparingDouble(r -> -r.metrics().trades());
            case TOTAL_RETURN -> Comparator.comparingDouble(r -> -r.metrics().totalReturnPct());
        };
        return c.thenComparing(ScanRow::symbol);
    }

    private BacktestResult execute(String symbol, Prepared p, List<Candle> data) {
        PriceSeries s = PriceSeries.of(data);
        double[] entry = evaluator.evaluate(p.entry(), s);
        double[] exit = p.exit() == null ? null : evaluator.evaluate(p.exit(), s);
        return BacktestEngine.run(symbol, p.tf().getCode(), s, entry, exit, p.cfg());
    }

    Prepared prepare(BacktestRequest req) {
        if (req.entry() == null || req.entry().isBlank()) {
            throw new IllegalArgumentException("entry condition is required");
        }
        Timeframe tf = Timeframe.fromCode(req.timeframe() == null ? "1d" : req.timeframe());
        double capital = positive(req.initialCapital(), 100_000, "initialCapital");
        double sizePct = positive(req.positionSizePct(), 100, "positionSizePct");
        if (sizePct > 100) {
            throw new IllegalArgumentException("positionSizePct must be at most 100 (no leverage)");
        }
        double commission = nonNegative(req.commissionPct(), 0.03, "commissionPct");
        double slippage = nonNegative(req.slippagePct(), 0, "slippagePct");
        Double stop = optionalPct(req.stopLossPct(), "stopLossPct");
        Double target = optionalPct(req.takeProfitPct(), "takeProfitPct");

        CompiledExpr entryExpr = evaluator.compileValidated(req.entry());
        CompiledExpr exitExpr = req.exit() == null || req.exit().isBlank() ? null : evaluator.compileValidated(req.exit());

        Instant to = req.to() != null ? Instant.ofEpochMilli(req.to()) : Instant.now();
        Instant from = req.from() != null ? Instant.ofEpochMilli(req.from()) : to.minus(defaultSpan(tf));
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (Duration.between(from, to).toMillis() / tf.getMillis() > MAX_BARS) {
            throw new IllegalArgumentException("Range too large: at most " + MAX_BARS + " bars per backtest");
        }
        var cfg = new BacktestEngine.Config(req.direction() == null ? Direction.LONG : req.direction(),
                stop, target, capital, sizePct, commission, slippage, barsPerYear(tf));
        return new Prepared(tf, from, to, entryExpr, exitExpr, cfg);
    }

    static Duration defaultSpan(Timeframe tf) {
        return switch (tf) {
            case ONE_DAY -> Duration.ofDays(3 * 365);
            case ONE_HOUR -> Duration.ofDays(180);
            case ONE_MINUTE -> Duration.ofDays(10);
            default -> Duration.ofDays(30);
        };
    }

    static double barsPerYear(Timeframe tf) {
        if (tf == Timeframe.ONE_DAY) {
            return SESSIONS_PER_YEAR;
        }
        return SESSIONS_PER_YEAR * SESSION_MINUTES / tf.getDuration().toMinutes();
    }

    private static double positive(Double v, double dflt, String name) {
        if (v == null) {
            return dflt;
        }
        if (!(v > 0)) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return v;
    }

    private static double nonNegative(Double v, double dflt, String name) {
        if (v == null) {
            return dflt;
        }
        if (!(v >= 0) || v > 10) {
            throw new IllegalArgumentException(name + " must be between 0 and 10 (percent)");
        }
        return v;
    }

    private static Double optionalPct(Double v, String name) {
        if (v == null || v == 0) {
            return null;
        }
        if (!(v > 0) || v >= 100) {
            throw new IllegalArgumentException(name + " must be between 0 and 100 (percent)");
        }
        return v;
    }
}
