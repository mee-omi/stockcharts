package com.stockcharts.trading.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A single broker order as returned by the data service. */
public record OrderDto(
        @JsonProperty("order_id")    String orderId,
        String symbol,
        String exchange,
        @JsonProperty("transaction_type") String transactionType,
        @JsonProperty("order_type")        String orderType,
        String product,
        int quantity,
        double price,
        @JsonProperty("trigger_price")  Double triggerPrice,
        String status,
        @JsonProperty("filled_quantity") int filledQuantity,
        @JsonProperty("average_price")   double averagePrice,
        long timestamp,
        String message
) {}
