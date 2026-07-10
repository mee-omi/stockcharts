package com.stockcharts.expr;

import com.stockcharts.ta.Indicators;
import com.stockcharts.ta.PriceSeries;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionServiceTest {

    private final ExpressionService svc = new ExpressionService();

    private PriceSeries series(double... close) {
        int n = close.length;
        long[] t = new long[n];
        double[] o = new double[n], h = new double[n], l = new double[n], v = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i * 60L;
            o[i] = close[i];
            h[i] = close[i] + 1;
            l[i] = close[i] - 1;
            v[i] = 1000;
        }
        return new PriceSeries(t, o, h, l, close, v);
    }

    @Test
    void smaFunctionMatchesLibrary() {
        PriceSeries s = series(10, 11, 12, 13, 14, 15);
        double[] viaExpr = svc.evaluate("sma(close, 3)", s, Map.of());
        double[] viaLib = Indicators.sma(s.close(), 3);
        assertEquals(viaLib.length, viaExpr.length);
        for (int i = 0; i < viaLib.length; i++) {
            if (Double.isNaN(viaLib[i])) {
                assertTrue(Double.isNaN(viaExpr[i]));
            } else {
                assertEquals(viaLib[i], viaExpr[i], 1e-9);
            }
        }
    }

    @Test
    void arithmeticPrecedence() {
        double[] r = svc.evaluate("2 + 3 * 4", series(1, 2, 3), Map.of());
        assertEquals(14.0, r[0], 1e-9);
    }

    @Test
    void booleanComparisonIsZeroOneOrNaN() {
        PriceSeries s = series(10, 20, 30, 40, 50);
        double[] r = svc.evaluate("close > sma(close, 3)", s, Map.of());
        // warm-up (first two) are NaN; later bars rising → close above its SMA → 1.0
        assertTrue(Double.isNaN(r[0]));
        assertEquals(1.0, r[4], 1e-9);
    }

    @Test
    void logicalAndCombinesConditions() {
        PriceSeries s = series(1, 2, 3, 4, 5, 6, 7, 8);
        double[] r = svc.evaluate("close > 3 and close < 7", s, Map.of());
        assertEquals(0.0, r[1], 1e-9); // close=2
        assertEquals(1.0, r[3], 1e-9); // close=4
        assertEquals(0.0, r[6], 1e-9); // close=7 (not < 7)
    }

    @Test
    void customIndicatorReferenceResolves() {
        PriceSeries s = series(1, 2, 3);
        double[] my = {10, 20, 30};
        double[] r = svc.evaluate("myind + 1", s, Map.of("myind", my));
        assertEquals(11.0, r[0], 1e-9);
        assertEquals(31.0, r[2], 1e-9);
    }

    @Test
    void unknownIdentifierIsInvalid() {
        ExpressionService.ValidationResult v = svc.validate("foo > 10");
        assertFalse(v.valid());
    }

    @Test
    void unknownFunctionIsInvalid() {
        ExpressionService.ValidationResult v = svc.validate("frobnicate(close, 5) > 1");
        assertFalse(v.valid());
    }

    @Test
    void validExpressionReportsReferences() {
        ExpressionService.ValidationResult v = svc.validate("rsi(close, 14) < 30 and close > sma(close, 50)");
        assertTrue(v.valid(), v.error());
        assertTrue(v.variables().contains("close"));
    }
}
