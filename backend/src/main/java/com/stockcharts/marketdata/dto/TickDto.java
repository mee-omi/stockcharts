package com.stockcharts.marketdata.dto;

/** A live tick from the Python SSE stream. */
public record TickDto(
        String symbol,
        double ltp,
        long timestamp,
        double volume
) {
}
