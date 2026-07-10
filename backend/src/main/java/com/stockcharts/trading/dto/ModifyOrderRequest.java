package com.stockcharts.trading.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Request body for modifying an existing open order. */
public record ModifyOrderRequest(
        Integer quantity,
        Double price,
        @JsonProperty("trigger_price") Double triggerPrice,
        @JsonProperty("order_type")    String orderType
) {}
