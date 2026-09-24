package com.stockcharts.marketdata.dto;

import java.util.List;
import java.util.Map;

/**
 * Active data-source provider, the available ones, and the provider's live-feed / session status
 * (connected, market open, last tick, last error…) as reported by the data service.
 */
public record ProviderInfo(String active, List<String> available, Map<String, Object> status) {
}
