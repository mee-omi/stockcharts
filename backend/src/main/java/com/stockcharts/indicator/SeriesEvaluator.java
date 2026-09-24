package com.stockcharts.indicator;

import com.stockcharts.expr.CompiledExpr;
import com.stockcharts.expr.ExpressionException;
import com.stockcharts.expr.ExpressionService;
import com.stockcharts.ta.PriceSeries;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Validates, compiles and evaluates a user expression against a price series, resolving any
 * custom-indicator references. Shared by the screener, alerts and the backtester.
 */
@Component
public class SeriesEvaluator {

    private final ExpressionService expr;
    private final CustomIndicatorService customs;

    public SeriesEvaluator(ExpressionService expr, CustomIndicatorService customs) {
        this.expr = expr;
        this.customs = customs;
    }

    /** Compile after checking syntax, functions and that referenced custom indicators exist. */
    public CompiledExpr compileValidated(String expression) {
        ExpressionService.ValidationResult v = customs.validate(expression);
        if (!v.valid()) {
            throw new ExpressionException(v.error());
        }
        return expr.compile(expression);
    }

    public double[] evaluate(CompiledExpr compiled, PriceSeries series) {
        Map<String, double[]> values = new HashMap<>();
        for (String ref : compiled.customReferences()) {
            values.put(ref, customs.compute(ref, series));
        }
        return expr.evaluate(compiled, series, values);
    }

    /** True if a boolean-valued series holds (finite and > 0.5) at {@code index}. */
    public static boolean truthy(double[] series, int index) {
        if (index < 0 || index >= series.length) {
            return false;
        }
        double v = series[index];
        return Double.isFinite(v) && v > 0.5;
    }
}
