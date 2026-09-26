package com.stockcharts.live;

/** Published for every live tick (after aggregation), for in-process listeners such as the paper broker. */
public record PriceTick(String symbol, double ltp, long epochMs) {
}
