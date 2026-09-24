package com.stockcharts.instrument;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An instrument in the universe: a tradable equity ({@code EQ}) or an index ({@code INDEX}).
 * {@code symbol} is the natural key.
 */
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

    @Column(name = "instrument_type", nullable = false, length = 16)
    private String instrumentType = TYPE_EQUITY;

    public static final String TYPE_EQUITY = "EQ";
    public static final String TYPE_INDEX = "INDEX";

    protected Instrument() {
    }

    public Instrument(String symbol, String name, long instrumentToken, String exchange) {
        this(symbol, name, instrumentToken, exchange, TYPE_EQUITY);
    }

    public Instrument(String symbol, String name, long instrumentToken, String exchange, String instrumentType) {
        this.symbol = symbol;
        this.name = name;
        this.instrumentToken = instrumentToken;
        this.exchange = exchange;
        this.instrumentType = instrumentType == null || instrumentType.isBlank() ? TYPE_EQUITY : instrumentType;
    }

    public String getInstrumentType() {
        return instrumentType;
    }

    @JsonIgnore
    public boolean isIndex() {
        return TYPE_INDEX.equals(instrumentType);
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
