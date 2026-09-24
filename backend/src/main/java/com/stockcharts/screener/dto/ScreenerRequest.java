package com.stockcharts.screener.dto;

import java.util.List;

/**
 * An ad-hoc screener run.
 *
 * @param expression boolean expression evaluated at the latest bar (e.g. "rsi(close,14) < 30")
 * @param timeframe  candle timeframe (1m,5m,15m,30m,1h,1d); defaults to 1d
 * @param universe   which symbols to scan
 * @param columns    optional extra expressions to compute per match (display columns)
 * @param limit      max matches to return
 */
public record ScreenerRequest(
        String expression,
        String timeframe,
        Universe universe,
        List<Column> columns,
        Integer limit
) {
    /**
     * type = index | list | watchlist | all. value = index code (e.g. NIFTY50, BANKNIFTY; default
     * NIFTY50), watchlist name, or CSV of symbols.
     */
    public record Universe(String type, String value) {
    }

    public record Column(String label, String expression) {
    }
}
