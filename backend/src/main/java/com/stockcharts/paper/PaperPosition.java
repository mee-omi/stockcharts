package com.stockcharts.paper;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/** Today's position in one symbol and product; average price uses the net-position method. */
@Entity
@Table(name = "paper_position")
@IdClass(PaperPosition.Key.class)
class PaperPosition {

    static class Key implements Serializable {
        String symbol;
        String product;

        Key() {
        }

        Key(String symbol, String product) {
            this.symbol = symbol;
            this.product = product;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.symbol.equals(symbol) && k.product.equals(product);
        }

        @Override
        public int hashCode() {
            return Objects.hash(symbol, product);
        }
    }

    @Id
    @Column(length = 64)
    String symbol;

    @Id
    @Column(length = 8)
    String product;

    @Column(nullable = false, length = 16)
    String exchange;

    /** Net quantity: positive long, negative short. */
    @Column(nullable = false)
    int quantity;

    @Column(name = "average_price", nullable = false)
    double averagePrice;

    @Column(name = "buy_quantity", nullable = false)
    int buyQuantity;

    @Column(name = "buy_value", nullable = false)
    double buyValue;

    @Column(name = "sell_quantity", nullable = false)
    int sellQuantity;

    @Column(name = "sell_value", nullable = false)
    double sellValue;

    @Column(name = "realized_pnl", nullable = false)
    double realizedPnl;

    @Column(nullable = false)
    double charges;

    protected PaperPosition() {
    }

    PaperPosition(String symbol, String product, String exchange) {
        this.symbol = symbol;
        this.product = product;
        this.exchange = exchange;
    }

    /**
     * Apply a fill; returns the P&L realised by the part that reduced the position.
     */
    double apply(boolean buy, int qty, double price) {
        if (buy) {
            buyQuantity += qty;
            buyValue += qty * price;
        } else {
            sellQuantity += qty;
            sellValue += qty * price;
        }
        int signed = buy ? qty : -qty;
        double realized = 0;
        if (quantity == 0 || Integer.signum(quantity) == Integer.signum(signed)) {
            // Opening or adding: blend the average.
            averagePrice = (averagePrice * Math.abs(quantity) + price * qty) / (Math.abs(quantity) + qty);
            quantity += signed;
        } else {
            int closing = Math.min(Math.abs(quantity), qty);
            realized = (quantity > 0 ? price - averagePrice : averagePrice - price) * closing;
            int remainder = qty - closing;
            quantity += signed;
            if (quantity == 0) {
                averagePrice = 0;
            } else if (remainder > 0) {
                averagePrice = price; // flipped sides: the rest opened a new position
            }
        }
        realizedPnl += realized;
        return realized;
    }
}
