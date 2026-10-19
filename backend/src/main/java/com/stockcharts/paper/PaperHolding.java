package com.stockcharts.paper;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "paper_holding")
class PaperHolding {

    @Id
    @Column(length = 64)
    String symbol;

    @Column(nullable = false, length = 16)
    String exchange;

    @Column(nullable = false)
    int quantity;

    @Column(name = "average_price", nullable = false)
    double averagePrice;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    protected PaperHolding() {
    }

    PaperHolding(String symbol, String exchange) {
        this.symbol = symbol;
        this.exchange = exchange;
        this.updatedAt = Instant.now();
    }
}
