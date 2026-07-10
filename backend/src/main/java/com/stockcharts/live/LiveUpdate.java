package com.stockcharts.live;

/**
 * Pushed to /topic/ticks/{symbol}. {@code time} is the live 1m candle's open time
 * (epoch seconds), so the frontend can call chart.update() directly.
 */
public record LiveUpdate(
        String symbol,
        long time,
        double open,
        double high,
        double low,
        double close,
        double volume,
        double ltp
) {
}
