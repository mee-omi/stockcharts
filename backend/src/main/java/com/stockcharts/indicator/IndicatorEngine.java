package com.stockcharts.indicator;

import com.stockcharts.candle.Candle;
import com.stockcharts.candle.Timeframe;
import com.stockcharts.indicator.dto.IndicatorResult;
import com.stockcharts.indicator.dto.IndicatorSpec;
import com.stockcharts.ta.Indicators;
import com.stockcharts.ta.PriceSeries;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes indicator series from candles using our own {@link Indicators} library
 * (primitive double[], no BigDecimal). Output is the time-aligned {@link IndicatorResult}
 * shape consumed by the frontend.
 *
 * Custom (expression-defined) indicators are resolved by an optional
 * {@link CustomIndicatorResolver} when a spec's type is not a built-in.
 */
@Component
public class IndicatorEngine {

    /** Resolves a non-built-in indicator name to a result (custom/expression indicators). */
    public interface CustomIndicatorResolver {
        IndicatorResult resolve(String name, PriceSeries series, long[] times);
    }

    private final java.util.Optional<CustomIndicatorResolver> customResolver;

    public IndicatorEngine(java.util.Optional<CustomIndicatorResolver> customResolver) {
        this.customResolver = customResolver;
    }

    public List<IndicatorResult> apply(Timeframe tf, List<Candle> candles, List<IndicatorSpec> specs) {
        List<IndicatorResult> out = new ArrayList<>();
        if (candles.isEmpty() || specs == null || specs.isEmpty()) {
            return out;
        }
        PriceSeries s = PriceSeries.of(candles);
        long[] times = s.time();
        for (IndicatorSpec spec : specs) {
            IndicatorType type = IndicatorType.fromCodeOrNull(spec.type());
            if (type != null) {
                out.add(computeBuiltin(type, spec, s, times));
            } else if (customResolver.isPresent()) {
                out.add(customResolver.get().resolve(spec.type(), s, times));
            } else {
                throw new IllegalArgumentException("Unknown indicator: " + spec.type());
            }
        }
        return out;
    }

    private IndicatorResult computeBuiltin(IndicatorType type, IndicatorSpec spec,
                                           PriceSeries s, long[] times) {
        double[] close = s.close();
        return switch (type) {
            case SMA -> {
                int p = spec.intParam("period", 20);
                yield single(type, "SMA(" + p + ")", Indicators.sma(close, p), times);
            }
            case EMA -> {
                int p = spec.intParam("period", 20);
                yield single(type, "EMA(" + p + ")", Indicators.ema(close, p), times);
            }
            case WMA -> {
                int p = spec.intParam("period", 20);
                yield single(type, "WMA(" + p + ")", Indicators.wma(close, p), times);
            }
            case RSI -> {
                int p = spec.intParam("period", 14);
                yield single(type, "RSI(" + p + ")", Indicators.rsi(close, p), times);
            }
            case CCI -> {
                int p = spec.intParam("period", 20);
                yield single(type, "CCI(" + p + ")",
                        Indicators.cci(s.high(), s.low(), close, p), times);
            }
            case ATR -> {
                int p = spec.intParam("period", 14);
                yield single(type, "ATR(" + p + ")",
                        Indicators.atr(s.high(), s.low(), close, p), times);
            }
            case ADX -> {
                int p = spec.intParam("period", 14);
                yield single(type, "ADX(" + p + ")",
                        Indicators.adx(s.high(), s.low(), close, p), times);
            }
            case OBV -> single(type, "OBV", Indicators.obv(close, s.volume()), times);
            case VWAP -> {
                int p = spec.intParam("period", 14);
                yield single(type, "VWAP(" + p + ")",
                        Indicators.vwap(s.high(), s.low(), close, s.volume(), p), times);
            }
            case MACD -> {
                int fast = spec.intParam("fast", 12);
                int slow = spec.intParam("slow", 26);
                int sig = spec.intParam("signal", 9);
                Indicators.Macd m = Indicators.macd(close, fast, slow, sig);
                List<IndicatorResult.Line> lines = List.of(
                        line("macd", m.macd(), times),
                        line("signal", m.signal(), times),
                        line("histogram", m.histogram(), times));
                yield new IndicatorResult(
                        "MACD(" + fast + "," + slow + "," + sig + ")",
                        type.name(), type.isOverlay(), lines);
            }
            case BBANDS -> {
                int p = spec.intParam("period", 20);
                double k = spec.param("k", 2);
                Indicators.Bands b = Indicators.bollinger(close, p, k);
                List<IndicatorResult.Line> lines = List.of(
                        line("upper", b.upper(), times),
                        line("middle", b.middle(), times),
                        line("lower", b.lower(), times));
                yield new IndicatorResult(
                        "BB(" + p + "," + (int) k + ")", type.name(), type.isOverlay(), lines);
            }
            case STOCH -> {
                int p = spec.intParam("period", 14);
                int dPeriod = spec.intParam("dPeriod", 3);
                Indicators.Stoch st = Indicators.stochastic(s.high(), s.low(), close, p, dPeriod);
                List<IndicatorResult.Line> lines = List.of(
                        line("k", st.k(), times),
                        line("d", st.d(), times));
                yield new IndicatorResult(
                        "STOCH(" + p + "," + dPeriod + ")", type.name(), type.isOverlay(), lines);
            }
        };
    }

    private IndicatorResult single(IndicatorType type, String key, double[] values, long[] times) {
        return new IndicatorResult(key, type.name(), type.isOverlay(),
                List.of(line("value", values, times)));
    }

    static IndicatorResult.Line line(String name, double[] values, long[] times) {
        List<IndicatorResult.Point> points = new ArrayList<>(times.length);
        for (int i = 0; i < times.length; i++) {
            double v = i < values.length ? values[i] : Double.NaN;
            points.add(new IndicatorResult.Point(times[i], Double.isFinite(v) ? v : null));
        }
        return new IndicatorResult.Line(name, points);
    }
}
