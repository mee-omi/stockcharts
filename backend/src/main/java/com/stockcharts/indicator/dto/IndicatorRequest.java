package com.stockcharts.indicator.dto;

import java.util.List;

/** Apply a set of indicators to a symbol's candles over an optional time range. */
public record IndicatorRequest(
        String symbol,
        String timeframe,
        Long from,           // epoch millis (UTC), optional
        Long to,             // epoch millis (UTC), optional
        List<IndicatorSpec> indicators
) {
}
