package com.stockcharts.marketdata.dto;

/** OHLCV candle as returned by the Python data service (timestamp = epoch millis UTC). */
public record CandleDto(
        long timestamp,
        double open,
        double high,
        double low,
        double close,
        double volume
) {
}
