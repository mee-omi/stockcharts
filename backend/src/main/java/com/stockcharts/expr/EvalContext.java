package com.stockcharts.expr;

import com.stockcharts.ta.PriceSeries;

import java.util.Map;

/** Inputs available while evaluating an expression: price data + referenced custom series. */
public final class EvalContext {

    private final PriceSeries series;
    private final Map<String, double[]> customSeries;

    public EvalContext(PriceSeries series, Map<String, double[]> customSeries) {
        this.series = series;
        this.customSeries = customSeries == null ? Map.of() : customSeries;
    }

    public PriceSeries series() {
        return series;
    }

    public int size() {
        return series.size();
    }

    public double[] custom(String name) {
        return customSeries.get(name);
    }
}
