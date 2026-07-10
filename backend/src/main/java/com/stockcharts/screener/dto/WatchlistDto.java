package com.stockcharts.screener.dto;

import java.util.List;

public record WatchlistDto(String name, List<String> symbols) {
}
