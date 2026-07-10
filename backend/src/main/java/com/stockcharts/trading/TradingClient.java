package com.stockcharts.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockcharts.trading.dto.FundsDto;
import com.stockcharts.trading.dto.HoldingDto;
import com.stockcharts.trading.dto.ModifyOrderRequest;
import com.stockcharts.trading.dto.OrderDto;
import com.stockcharts.trading.dto.PlaceOrderRequest;
import com.stockcharts.trading.dto.PositionsDto;
import com.stockcharts.trading.dto.TradingBrokerInfo;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin reactive client over the Python data-service trading and broker-management endpoints.
 * All broker-specific logic lives behind this boundary.
 */
@Component
public class TradingClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final WebClient client;

    public TradingClient(WebClient marketDataWebClient) {
        this.client = marketDataWebClient;
    }

    // ---- broker management ----

    public TradingBrokerInfo getBroker() {
        return client.get()
                .uri("/api/broker")
                .retrieve()
                .bodyToMono(TradingBrokerInfo.class)
                .block();
    }

    public TradingBrokerInfo setBroker(String name, Map<String, String> credentials) {
        Map<String, Object> body = new HashMap<>(credentials);
        body.put("broker", name);
        try {
            return client.post()
                    .uri("/api/broker")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(TradingBrokerInfo.class)
                    .block();
        } catch (WebClientResponseException e) {
            throw new IllegalArgumentException(extractDetail(e));
        }
    }

    // ---- orders ----

    public OrderDto placeOrder(PlaceOrderRequest req) {
        try {
            return client.post()
                    .uri("/api/trading/orders")
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(OrderDto.class)
                    .block();
        } catch (WebClientResponseException e) {
            throw new IllegalArgumentException(extractDetail(e));
        }
    }

    public List<OrderDto> getOrders() {
        return client.get()
                .uri("/api/trading/orders")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<OrderDto>>() {})
                .block();
    }

    public Map<String, Object> cancelOrder(String orderId) {
        try {
            return client.delete()
                    .uri("/api/trading/orders/{id}", orderId)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();
        } catch (WebClientResponseException e) {
            throw new IllegalArgumentException(extractDetail(e));
        }
    }

    public OrderDto modifyOrder(String orderId, ModifyOrderRequest req) {
        try {
            return client.put()
                    .uri("/api/trading/orders/{id}", orderId)
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(OrderDto.class)
                    .block();
        } catch (WebClientResponseException e) {
            throw new IllegalArgumentException(extractDetail(e));
        }
    }

    // ---- positions & holdings ----

    public PositionsDto getPositions() {
        return client.get()
                .uri("/api/trading/positions")
                .retrieve()
                .bodyToMono(PositionsDto.class)
                .block();
    }

    public List<HoldingDto> getHoldings() {
        return client.get()
                .uri("/api/trading/holdings")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<HoldingDto>>() {})
                .block();
    }

    // ---- funds ----

    public FundsDto getFunds() {
        return client.get()
                .uri("/api/trading/funds")
                .retrieve()
                .bodyToMono(FundsDto.class)
                .block();
    }

    // ---- helpers ----

    private static String extractDetail(WebClientResponseException e) {
        try {
            JsonNode node = MAPPER.readTree(e.getResponseBodyAsString());
            if (node.has("detail")) return node.get("detail").asText();
        } catch (Exception ignored) { // fall through
        }
        return "Broker error: " + e.getStatusText();
    }
}
