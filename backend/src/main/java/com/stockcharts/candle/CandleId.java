package com.stockcharts.candle;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** Composite primary key for {@link Candle}. */
public class CandleId implements Serializable {

    private String symbol;
    private Timeframe timeframe;
    private Instant openTime;

    public CandleId() {
    }

    public CandleId(String symbol, Timeframe timeframe, Instant openTime) {
        this.symbol = symbol;
        this.timeframe = timeframe;
        this.openTime = openTime;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CandleId that)) return false;
        return Objects.equals(symbol, that.symbol)
                && timeframe == that.timeframe
                && Objects.equals(openTime, that.openTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(symbol, timeframe, openTime);
    }
}
