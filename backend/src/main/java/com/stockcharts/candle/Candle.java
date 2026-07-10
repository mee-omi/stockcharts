package com.stockcharts.candle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;

/** One OHLCV bar for a (symbol, timeframe). Persisted as a TimescaleDB hypertable. */
@Entity
@Table(name = "candle")
@IdClass(CandleId.class)
public class Candle {

    @Id
    @Column(nullable = false)
    private String symbol;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Timeframe timeframe;

    @Id
    @Column(name = "open_time", nullable = false)
    private Instant openTime;

    @Column(nullable = false)
    private double open;
    @Column(nullable = false)
    private double high;
    @Column(nullable = false)
    private double low;
    @Column(nullable = false)
    private double close;
    @Column(nullable = false)
    private double volume;

    protected Candle() {
    }

    public Candle(String symbol, Timeframe timeframe, Instant openTime,
                  double open, double high, double low, double close, double volume) {
        this.symbol = symbol;
        this.timeframe = timeframe;
        this.openTime = openTime;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    public String getSymbol() {
        return symbol;
    }

    public Timeframe getTimeframe() {
        return timeframe;
    }

    public Instant getOpenTime() {
        return openTime;
    }

    public double getOpen() {
        return open;
    }

    public double getHigh() {
        return high;
    }

    public double getLow() {
        return low;
    }

    public double getClose() {
        return close;
    }

    public double getVolume() {
        return volume;
    }

    public void setOpen(double open) {
        this.open = open;
    }

    public void setHigh(double high) {
        this.high = high;
    }

    public void setLow(double low) {
        this.low = low;
    }

    public void setClose(double close) {
        this.close = close;
    }

    public void setVolume(double volume) {
        this.volume = volume;
    }
}
