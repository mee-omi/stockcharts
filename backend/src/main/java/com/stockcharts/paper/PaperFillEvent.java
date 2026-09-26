package com.stockcharts.paper;

import com.stockcharts.trading.dto.OrderDto;

/** A paper order filled (possibly long after it was placed); the journal records it. */
public record PaperFillEvent(OrderDto order, String tag) {
}
