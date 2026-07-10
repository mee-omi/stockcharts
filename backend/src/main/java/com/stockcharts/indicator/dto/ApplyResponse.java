package com.stockcharts.indicator.dto;

import com.stockcharts.candle.CandleResponse;

import java.util.List;

/** Candles plus the computed indicator series, time-aligned for charting. */
public record ApplyResponse(
        String symbol,
        String timeframe,
        List<CandleResponse> candles,
        List<IndicatorResult> indicators
) {
}
