package com.stockcharts.indicator.dto;

import java.util.List;

/**
 * Computed output for one indicator instance.
 *
 * @param key     unique label, e.g. "EMA(20)" or "MACD(12,26,9)"
 * @param type    indicator code, e.g. "EMA"
 * @param overlay true if it should be drawn over the price chart
 * @param lines   one entry per output line (e.g. MACD → macd/signal/histogram)
 */
public record IndicatorResult(
        String key,
        String type,
        boolean overlay,
        List<Line> lines
) {
    public record Line(String name, List<Point> points) {
    }

    /** A single value at a point in time. {@code value} is null where undefined (warm-up). */
    public record Point(long time, Double value) {
    }
}
