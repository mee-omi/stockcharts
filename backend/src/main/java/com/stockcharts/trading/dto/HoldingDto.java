package com.stockcharts.trading.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A long-term CNC holding. */
public record HoldingDto(
        String symbol,
        String exchange,
        int quantity,
        @JsonProperty("average_price") double averagePrice,
        @JsonProperty("last_price")    double lastPrice,
        double pnl,
        @JsonProperty("pnl_percent")   double pnlPercent
) {}
