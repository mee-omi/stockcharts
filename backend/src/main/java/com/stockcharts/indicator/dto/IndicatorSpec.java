package com.stockcharts.indicator.dto;

import java.util.Map;

/** One requested indicator, e.g. {"type":"EMA","params":{"period":50}}. */
public record IndicatorSpec(String type, Map<String, Double> params) {

    public double param(String name, double fallback) {
        if (params == null) {
            return fallback;
        }
        Double v = params.get(name);
        return v != null ? v : fallback;
    }

    public int intParam(String name, double fallback) {
        return (int) Math.round(param(name, fallback));
    }
}
