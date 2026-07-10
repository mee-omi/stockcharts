package com.stockcharts.instrument;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A tradable NSE instrument. {@code symbol} is the natural key. */
@Entity
@Table(name = "instrument")
public class Instrument {

    @Id
    @Column(nullable = false, updatable = false)
    private String symbol;

    @Column(nullable = false)
    private String name;

    @Column(name = "instrument_token", nullable = false)
    private long instrumentToken;

    @Column(nullable = false)
    private String exchange;

    protected Instrument() {
    }

    public Instrument(String symbol, String name, long instrumentToken, String exchange) {
        this.symbol = symbol;
        this.name = name;
        this.instrumentToken = instrumentToken;
        this.exchange = exchange;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getName() {
        return name;
    }

    public long getInstrumentToken() {
        return instrumentToken;
    }

    public String getExchange() {
        return exchange;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setInstrumentToken(long instrumentToken) {
        this.instrumentToken = instrumentToken;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }
}
