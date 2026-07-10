package com.stockcharts.screener.dto;

/** A saved screener definition. */
public record ScreenerDef(
        String name,
        String expression,
        ScreenerRequest.Universe universe,
        String timeframe
) {
}
