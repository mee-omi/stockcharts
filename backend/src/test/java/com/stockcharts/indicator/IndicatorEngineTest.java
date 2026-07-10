package com.stockcharts.indicator;

import com.stockcharts.candle.Candle;
import com.stockcharts.candle.Timeframe;
import com.stockcharts.indicator.dto.IndicatorResult;
import com.stockcharts.indicator.dto.IndicatorSpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndicatorEngineTest {

    private final IndicatorEngine engine = new IndicatorEngine(java.util.Optional.empty());

    private List<Candle> candles(double... closes) {
        List<Candle> list = new ArrayList<>();
        Instant t0 = Instant.parse("2024-01-01T00:00:00Z");
        for (int i = 0; i < closes.length; i++) {
            double c = closes[i];
            // simple bars: o=h=l=c, constant volume
            list.add(new Candle("TEST", Timeframe.ONE_MINUTE,
                    t0.plusSeconds(i * 60L), c, c, c, c, 1000));
        }
        return list;
    }

    @Test
    void smaMatchesArithmeticMean() {
        List<Candle> data = candles(10, 11, 12, 13, 14);
        List<IndicatorResult> out = engine.apply(Timeframe.ONE_MINUTE, data,
                List.of(new IndicatorSpec("SMA", Map.of("period", 3.0))));

        assertEquals(1, out.size());
        IndicatorResult sma = out.get(0);
        assertEquals("SMA(3)", sma.key());
        assertTrue(sma.overlay());
        List<IndicatorResult.Point> pts = sma.lines().get(0).points();
        assertEquals(5, pts.size());
        // last SMA(3) = mean(12,13,14) = 13
        assertEquals(13.0, pts.get(4).value(), 1e-9);
    }

    @Test
    void macdProducesThreeLines() {
        double[] closes = new double[60];
        for (int i = 0; i < closes.length; i++) {
            closes[i] = 100 + Math.sin(i / 3.0) * 5 + i * 0.1;
        }
        List<IndicatorResult> out = engine.apply(Timeframe.ONE_MINUTE, candles(closes),
                List.of(new IndicatorSpec("MACD", Map.of())));

        IndicatorResult macd = out.get(0);
        assertEquals(3, macd.lines().size());
        assertFalse(macd.overlay());
        Double last = macd.lines().get(0).points().get(closes.length - 1).value();
        assertNotNull(last, "MACD should be defined at the end of a 60-bar series");
    }

    @Test
    void bollingerHasUpperMiddleLowerOrdered() {
        double[] closes = new double[40];
        for (int i = 0; i < closes.length; i++) {
            closes[i] = 50 + Math.sin(i / 2.0) * 3;
        }
        List<IndicatorResult> out = engine.apply(Timeframe.ONE_MINUTE, candles(closes),
                List.of(new IndicatorSpec("BBANDS", Map.of("period", 20.0, "k", 2.0))));

        IndicatorResult bb = out.get(0);
        assertEquals(3, bb.lines().size());
        int last = closes.length - 1;
        double upper = bb.lines().get(0).points().get(last).value();
        double middle = bb.lines().get(1).points().get(last).value();
        double lower = bb.lines().get(2).points().get(last).value();
        assertTrue(upper >= middle && middle >= lower,
                "Bollinger bands must satisfy upper >= middle >= lower");
    }

    @Test
    void rsiStaysWithinZeroToHundred() {
        double[] closes = new double[50];
        for (int i = 0; i < closes.length; i++) {
            closes[i] = 100 + (i % 5 == 0 ? -2 : 1) * i * 0.3;
        }
        List<IndicatorResult> out = engine.apply(Timeframe.ONE_MINUTE, candles(closes),
                List.of(new IndicatorSpec("RSI", Map.of("period", 14.0))));

        for (IndicatorResult.Point p : out.get(0).lines().get(0).points()) {
            if (p.value() != null) {
                assertTrue(p.value() >= 0 && p.value() <= 100, "RSI in [0,100]");
            }
        }
    }
}
