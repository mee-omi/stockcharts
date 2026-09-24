package com.stockcharts.backtest;

import com.stockcharts.backtest.BacktestDtos.BacktestRequest;
import com.stockcharts.backtest.BacktestDtos.BacktestResult;
import com.stockcharts.backtest.BacktestDtos.Direction;
import com.stockcharts.candle.Candle;
import com.stockcharts.candle.CandleService;
import com.stockcharts.candle.Timeframe;
import com.stockcharts.indicator.SeriesEvaluator;
import com.stockcharts.instrument.InstrumentService;
import com.stockcharts.ta.PriceSeries;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

/** Loads candles, evaluates the entry/exit rules and runs {@link BacktestEngine}. */
@Service
public class BacktestService {

    static final int MIN_BARS = 30;
    static final int MAX_BARS = 100_000;
    /** NSE cash session: 09:15–15:30 = 375 minutes, ~252 sessions a year. */
    private static final double SESSION_MINUTES = 375;
    private static final double SESSIONS_PER_YEAR = 252;

    private final CandleService candles;
    private final InstrumentService instruments;
    private final SeriesEvaluator evaluator;
    private final Timer timer;

    public BacktestService(CandleService candles, InstrumentService instruments, SeriesEvaluator evaluator,
                           MeterRegistry meters) {
        this.candles = candles;
        this.instruments = instruments;
        this.evaluator = evaluator;
        this.timer = Timer.builder("stockcharts.backtest.duration")
                .description("Backtest run time").register(meters);
    }

    public BacktestResult run(BacktestRequest req) {
        return timer.record(() -> doRun(req));
    }

    private BacktestResult doRun(BacktestRequest req) {
        if (req.symbol() == null || req.symbol().isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (!instruments.exists(req.symbol())) {
            throw new NoSuchElementException("Unknown symbol: " + req.symbol());
        }
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

        var entryExpr = evaluator.compileValidated(req.entry());
        var exitExpr = req.exit() == null || req.exit().isBlank() ? null : evaluator.compileValidated(req.exit());

        Instant to = req.to() != null ? Instant.ofEpochMilli(req.to()) : Instant.now();
        Instant from = req.from() != null ? Instant.ofEpochMilli(req.from()) : to.minus(defaultSpan(tf));
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (Duration.between(from, to).toMillis() / tf.getMillis() > MAX_BARS) {
            throw new IllegalArgumentException("Range too large: at most " + MAX_BARS + " bars per backtest");
        }
        List<Candle> data = candles.getCandles(req.symbol(), tf, from, to);
        if (data.size() < MIN_BARS) {
            throw new IllegalArgumentException("Not enough data: " + data.size() + " bars (need " + MIN_BARS + ")");
        }
        PriceSeries s = PriceSeries.of(data);
        double[] entry = evaluator.evaluate(entryExpr, s);
        double[] exit = exitExpr == null ? null : evaluator.evaluate(exitExpr, s);

        var cfg = new BacktestEngine.Config(req.direction() == null ? Direction.LONG : req.direction(),
                stop, target, capital, sizePct, commission, slippage, barsPerYear(tf));
        return BacktestEngine.run(req.symbol().toUpperCase(), tf.getCode(), s, entry, exit, cfg);
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
