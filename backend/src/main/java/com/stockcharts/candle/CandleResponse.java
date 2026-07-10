package com.stockcharts.candle;

/**
 * Candle shape returned to clients. {@code time} is epoch SECONDS (UTC) — the format
 * TradingView lightweight-charts consumes directly.
 */
public record CandleResponse(
        long time,
        double open,
        double high,
        double low,
        double close,
        double volume
) {
    public static CandleResponse from(Candle c) {
        return new CandleResponse(
                c.getOpenTime().getEpochSecond(),
                c.getOpen(), c.getHigh(), c.getLow(), c.getClose(), c.getVolume());
    }
}
