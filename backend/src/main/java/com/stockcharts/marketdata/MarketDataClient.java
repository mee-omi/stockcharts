package com.stockcharts.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockcharts.marketdata.dto.CandleDto;
import com.stockcharts.marketdata.dto.InstrumentDto;
import com.stockcharts.marketdata.dto.ProviderInfo;
import com.stockcharts.marketdata.dto.TickDto;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin reactive client over the Python market-data service. All Zerodha/PyZData
 * specifics live behind this boundary.
 */
@Component
public class MarketDataClient {

    private final WebClient client;

    public MarketDataClient(WebClient marketDataWebClient) {
        this.client = marketDataWebClient;
    }

    public List<InstrumentDto> listInstruments() {
        return client.get()
                .uri("/api/instruments")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<InstrumentDto>>() {})
                .block();
    }

    public List<CandleDto> getHistorical(String symbol, String interval, long fromMs, long toMs) {
        return client.get()
                .uri(uri -> uri.path("/api/historical/{symbol}")
                        .queryParam("interval", interval)
                        .queryParam("from", fromMs)
                        .queryParam("to", toMs)
                        .build(symbol))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<CandleDto>>() {})
                .block();
    }

    /** Infinite SSE stream of live ticks for the given symbols. */
    public Flux<TickDto> streamTicks(List<String> symbols) {
        String joined = String.join(",", symbols);
        return client.get()
                .uri(uri -> uri.path("/api/stream/ticks")
                        .queryParam("symbols", joined)
                        .build())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(TickDto.class);
    }

    public Mono<String> health() {
        return client.get().uri("/health").retrieve().bodyToMono(String.class);
    }

    // ---- data-source provider switching ----

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ProviderInfo getProvider() {
        return client.get().uri("/api/provider").retrieve().bodyToMono(ProviderInfo.class).block();
    }

    public ProviderInfo setProvider(String name, String enctoken) {
        Map<String, Object> body = new HashMap<>();
        body.put("provider", name);
        if (enctoken != null && !enctoken.isBlank()) {
            body.put("enctoken", enctoken);
        }
        try {
            return client.post().uri("/api/provider")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(ProviderInfo.class)
                    .block();
        } catch (WebClientResponseException e) {
            // FastAPI returns {"detail": "..."} on error — surface it as a 400.
            throw new IllegalArgumentException(extractDetail(e));
        }
    }

    private static String extractDetail(WebClientResponseException e) {
        try {
            JsonNode node = MAPPER.readTree(e.getResponseBodyAsString());
            if (node.has("detail")) {
                return node.get("detail").asText();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "Failed to switch data source: " + e.getStatusText();
    }
}
