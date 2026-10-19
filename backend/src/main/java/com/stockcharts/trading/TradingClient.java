package com.stockcharts.trading;

import com.stockcharts.paper.PaperBroker;
import com.stockcharts.trading.dto.FundsDto;
import com.stockcharts.trading.dto.HoldingDto;
import com.stockcharts.trading.dto.ModifyOrderRequest;
import com.stockcharts.trading.dto.OrderDto;
import com.stockcharts.trading.dto.PlaceOrderRequest;
import com.stockcharts.trading.dto.PositionsDto;
import com.stockcharts.trading.dto.TradingBrokerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The trading API the rest of the backend uses. Broker selection lives in the data service; when
 * the active broker is {@code mock}, orders go to the backend's own {@link PaperBroker} (live
 * prices, charges, margin, persistence), otherwise to the real broker through the data service.
 */
@Component
public class TradingClient {

    private static final Logger log = LoggerFactory.getLogger(TradingClient.class);
    private static final long BROKER_TTL_MS = 3_000;

    private final RemoteTradingClient remote;
    private final PaperBroker paper;
    private volatile String activeBroker;
    private volatile long activeCheckedAt;

    public TradingClient(RemoteTradingClient remote, PaperBroker paper) {
        this.remote = remote;
        this.paper = paper;
    }

    public TradingBrokerInfo getBroker() {
        TradingBrokerInfo info = remote.getBroker();
        remember(info);
        return info;
    }

    public TradingBrokerInfo setBroker(String name, Map<String, String> credentials) {
        TradingBrokerInfo info = remote.setBroker(name, credentials);
        remember(info);
        return info;
    }

    /** True when orders go to the paper broker. */
    public boolean isPaper() {
        long now = System.currentTimeMillis();
        if (activeBroker == null || now - activeCheckedAt > BROKER_TTL_MS) {
            try {
                remember(remote.getBroker());
            } catch (Exception e) {
                // Data service unreachable: keep the last known broker (paper by default).
                log.debug("Broker lookup failed: {}", e.getMessage());
                activeCheckedAt = now;
            }
        }
        return activeBroker == null || PaperBroker.BROKER.equalsIgnoreCase(activeBroker);
    }

    private void remember(TradingBrokerInfo info) {
        if (info != null && info.active() != null) {
            activeBroker = info.active();
        }
        activeCheckedAt = System.currentTimeMillis();
    }

    public OrderDto placeOrder(PlaceOrderRequest req) {
        return isPaper() ? paper.placeOrder(req) : remote.placeOrder(req);
    }

    public List<OrderDto> getOrders() {
        return isPaper() ? paper.getOrders() : remote.getOrders();
    }

    public Map<String, Object> cancelOrder(String orderId) {
        return isPaper() ? paper.cancelOrder(orderId) : remote.cancelOrder(orderId);
    }

    public OrderDto modifyOrder(String orderId, ModifyOrderRequest req) {
        return isPaper() ? paper.modifyOrder(orderId, req) : remote.modifyOrder(orderId, req);
    }

    public PositionsDto getPositions() {
        return isPaper() ? paper.getPositions() : remote.getPositions();
    }

    public List<HoldingDto> getHoldings() {
        return isPaper() ? paper.getHoldings() : remote.getHoldings();
    }

    public FundsDto getFunds() {
        return isPaper() ? paper.getFunds() : remote.getFunds();
    }
}
