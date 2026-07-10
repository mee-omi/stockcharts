package com.stockcharts.trading.dto;

import java.util.List;

/** Active trading broker and the list of available brokers. */
public record TradingBrokerInfo(String active, List<String> available) {}
