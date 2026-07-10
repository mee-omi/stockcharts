package com.stockcharts.screener.dto;

import java.util.List;
import java.util.Map;

/** Result of a screener run. */
public record ScreenerResult(
        int scanned,
        int matched,
        boolean truncated,
        List<Match> matches
) {
    public record Match(String symbol, String name, double close, Map<String, Double> columns) {
    }
}
