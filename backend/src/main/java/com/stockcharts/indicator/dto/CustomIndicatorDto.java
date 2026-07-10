package com.stockcharts.indicator.dto;

/** Create/update/read payload for a custom (expression) indicator. */
public record CustomIndicatorDto(
        String name,
        String expression,
        boolean overlay,
        String description
) {
}
