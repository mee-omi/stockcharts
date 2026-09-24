package com.stockcharts.journal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One executed trade (fill). The basis for the journal, realised P&L and tax reports. */
@Entity
@Table(name = "trade_journal")
public class JournalEntry {

    public enum Side { BUY, SELL }

    /** APP = placed through StockCharts, SYNC = pulled from the broker order book, MANUAL = typed in. */
    public enum Source { APP, SYNC, MANUAL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", length = 64)
    private String orderId;

    @Column(nullable = false, length = 32)
    private String broker;

    @Column(nullable = false, length = 64)
    private String symbol;

    @Column(nullable = false, length = 16)
    private String exchange;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Side side;

    @Column(nullable = false, length = 8)
    private String product;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private double price;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    @Column(length = 64)
    private String tag;

    @Column(length = 64)
    private String strategy;

    @Column(length = 2000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source;

    protected JournalEntry() {
    }

    public JournalEntry(String orderId, String broker, String symbol, String exchange, Side side, String product,
                        int quantity, double price, Instant executedAt, String tag, Source source) {
        this.orderId = orderId;
        this.broker = broker;
        this.symbol = symbol;
        this.exchange = exchange;
        this.side = side;
        this.product = product;
        this.quantity = quantity;
        this.price = price;
        this.executedAt = executedAt;
        this.tag = tag;
        this.source = source;
    }

    public Long getId() {
        return id;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getBroker() {
        return broker;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getExchange() {
        return exchange;
    }

    public Side getSide() {
        return side;
    }

    public String getProduct() {
        return product;
    }

    public int getQuantity() {
        return quantity;
    }

    public double getPrice() {
        return price;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public String getTag() {
        return tag;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Source getSource() {
        return source;
    }
}
