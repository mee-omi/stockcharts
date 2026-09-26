package com.stockcharts.paper;

import com.stockcharts.trading.dto.OrderDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "paper_order")
class PaperOrder {

    enum Status { OPEN, TRIGGER_PENDING, COMPLETE, CANCELLED, REJECTED }

    @Id
    @Column(name = "order_id", length = 40)
    String orderId;

    @Column(nullable = false, length = 64)
    String symbol;

    @Column(nullable = false, length = 16)
    String exchange;

    /** BUY or SELL. */
    @Column(nullable = false, length = 4)
    String side;

    /** MARKET, LIMIT, SL, SL-M. */
    @Column(name = "order_type", nullable = false, length = 8)
    String orderType;

    /** CNC or MIS. */
    @Column(nullable = false, length = 8)
    String product;

    @Column(nullable = false)
    int quantity;

    @Column
    Double price;

    @Column(name = "trigger_price")
    Double triggerPrice;

    @Column(nullable = false, length = 16)
    String status;

    @Column(name = "filled_quantity", nullable = false)
    int filledQuantity;

    @Column(name = "average_price", nullable = false)
    double averagePrice;

    @Column(nullable = false)
    double charges;

    /** Funds set aside while the order is open (released when it fills or is cancelled). */
    @Column(name = "blocked_margin", nullable = false)
    double blockedMargin;

    @Column(name = "trading_day", nullable = false)
    LocalDate tradingDay;

    @Column(name = "placed_at", nullable = false)
    Instant placedAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @Column(length = 255)
    String message;

    @Column(length = 64)
    String tag;

    protected PaperOrder() {
    }

    PaperOrder(String orderId, String symbol, String exchange, String side, String orderType, String product,
               int quantity, Double price, Double triggerPrice, LocalDate day, String tag) {
        this.orderId = orderId;
        this.symbol = symbol;
        this.exchange = exchange;
        this.side = side;
        this.orderType = orderType;
        this.product = product;
        this.quantity = quantity;
        this.price = price;
        this.triggerPrice = triggerPrice;
        this.status = Status.OPEN.name();
        this.tradingDay = day;
        this.placedAt = Instant.now();
        this.updatedAt = placedAt;
        this.tag = tag;
    }

    boolean buy() {
        return "BUY".equals(side);
    }

    boolean isOpen() {
        return Status.OPEN.name().equals(status) || Status.TRIGGER_PENDING.name().equals(status);
    }

    OrderDto toDto() {
        return new OrderDto(orderId, symbol, exchange, side, orderType, product, quantity,
                price == null ? 0 : price, triggerPrice, status, filledQuantity, averagePrice,
                (Status.COMPLETE.name().equals(status) ? updatedAt : placedAt).toEpochMilli(),
                message == null ? "" : message);
    }
}
