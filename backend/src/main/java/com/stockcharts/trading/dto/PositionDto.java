package com.stockcharts.trading.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A single open/day position. */
public record PositionDto(
        String symbol,
        String exchange,
        String product,
        int quantity,
        @JsonProperty("buy_quantity")  int buyQuantity,
        @JsonProperty("sell_quantity") int sellQuantity,
        @JsonProperty("average_price") double averagePrice,
        @JsonProperty("last_price")    double lastPrice,
        double pnl,
        @JsonProperty("day_change")    double dayChange
) {}
