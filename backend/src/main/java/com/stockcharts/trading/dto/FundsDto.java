package com.stockcharts.trading.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Margin and cash summary for the trading account. */
public record FundsDto(
        @JsonProperty("available_cash")   double availableCash,
        @JsonProperty("used_margin")      double usedMargin,
        @JsonProperty("available_margin") double availableMargin,
        @JsonProperty("total_balance")    double totalBalance
) {}
