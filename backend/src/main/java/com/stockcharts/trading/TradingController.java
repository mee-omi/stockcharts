package com.stockcharts.trading;

import com.stockcharts.trading.dto.FundsDto;
import com.stockcharts.trading.dto.HoldingDto;
import com.stockcharts.trading.dto.ModifyOrderRequest;
import com.stockcharts.trading.dto.OrderDto;
import com.stockcharts.trading.dto.PlaceOrderRequest;
import com.stockcharts.trading.dto.PositionsDto;
import com.stockcharts.trading.dto.TradingBrokerInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trading REST API — exposes broker management and order/position/holdings/funds endpoints.
 * All heavy lifting is delegated to the Python data service via {@link TradingClient}.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Trading", description = "Broker management, order placement, positions, holdings, funds")
public class TradingController {

    private final TradingClient trading;

    public TradingController(TradingClient trading) {
        this.trading = trading;
    }

    // ---- broker management ----

    public record SetBrokerRequest(
            String broker,
            String apiKey,
            String accessToken,
            String clientId,
            String totpSecret
    ) {}

    @GetMapping("/broker")
    @Operation(summary = "Get the active trading broker and available options")
    public TradingBrokerInfo getBroker() {
        return trading.getBroker();
    }

    @PostMapping("/broker")
    @Operation(summary = "Switch the trading broker")
    public TradingBrokerInfo setBroker(@RequestBody SetBrokerRequest req) {
        Map<String, String> creds = new LinkedHashMap<>();
        if (req.apiKey() != null && !req.apiKey().isBlank())         creds.put("api_key",       req.apiKey());
        if (req.accessToken() != null && !req.accessToken().isBlank()) creds.put("access_token",  req.accessToken());
        if (req.clientId() != null && !req.clientId().isBlank())     creds.put("client_id",      req.clientId());
        if (req.totpSecret() != null && !req.totpSecret().isBlank()) creds.put("totp_secret",    req.totpSecret());
        return trading.setBroker(req.broker(), creds);
    }

    // ---- orders ----

    @PostMapping("/trading/orders")
    @Operation(summary = "Place a new order")
    public OrderDto placeOrder(@RequestBody PlaceOrderRequest req) {
        return trading.placeOrder(req);
    }

    @GetMapping("/trading/orders")
    @Operation(summary = "Get today's orders")
    public List<OrderDto> getOrders() {
        return trading.getOrders();
    }

    @DeleteMapping("/trading/orders/{orderId}")
    @Operation(summary = "Cancel an open order")
    public Map<String, Object> cancelOrder(@PathVariable String orderId) {
        return trading.cancelOrder(orderId);
    }

    @PutMapping("/trading/orders/{orderId}")
    @Operation(summary = "Modify an open order")
    public OrderDto modifyOrder(
            @PathVariable String orderId,
            @RequestBody ModifyOrderRequest req) {
        return trading.modifyOrder(orderId, req);
    }

    // ---- positions & holdings ----

    @GetMapping("/trading/positions")
    @Operation(summary = "Get open positions")
    public PositionsDto getPositions() {
        return trading.getPositions();
    }

    @GetMapping("/trading/holdings")
    @Operation(summary = "Get long-term holdings")
    public List<HoldingDto> getHoldings() {
        return trading.getHoldings();
    }

    // ---- funds ----

    @GetMapping("/trading/funds")
    @Operation(summary = "Get account funds and margin")
    public FundsDto getFunds() {
        return trading.getFunds();
    }
}
