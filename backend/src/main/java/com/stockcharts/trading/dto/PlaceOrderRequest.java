package com.stockcharts.trading.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Request body for placing a new order. */
public record PlaceOrderRequest(
        String symbol,
        String exchange,
        @JsonProperty("transaction_type") String transactionType,
        @JsonProperty("order_type")        String orderType,
        String product,
        int quantity,
        Double price,
        @JsonProperty("trigger_price") Double triggerPrice,
        String tag
) {}
