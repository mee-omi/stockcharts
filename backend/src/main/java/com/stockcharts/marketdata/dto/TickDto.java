package com.stockcharts.marketdata.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A live tick from the Python SSE stream.
 *
 * @param volume    volume since the previous tick (mock feed); 0 from exchange feeds
 * @param dayVolume cumulative volume for the day from exchange feeds (Kite, Angel One), or null
 * @param bar       the whole current 1-minute bar from polling providers, or null
 */
public record TickDto(
        String symbol,
        double ltp,
        long timestamp,
        double volume,
        @JsonProperty("day_volume") Double dayVolume,
        CandleDto bar
) {
    public TickDto(String symbol, double ltp, long timestamp, double volume) {
        this(symbol, ltp, timestamp, volume, null, null);
    }
}
