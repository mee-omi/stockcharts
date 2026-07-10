package com.stockcharts.marketdata.dto;

import java.util.List;

/** Active data-source provider and the list of available ones. */
public record ProviderInfo(String active, List<String> available) {
}
