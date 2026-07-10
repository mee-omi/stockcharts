package com.stockcharts.screener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A saved screener: a boolean expression evaluated over a universe at a timeframe. */
@Entity
@Table(name = "screener")
public class Screener {

    @Id
    @Column(length = 64)
    private String name;

    @Column(nullable = false, length = 2000)
    private String expression;

    @Column(name = "universe_type", nullable = false, length = 16)
    private String universeType; // index | list | watchlist | all

    @Column(name = "universe_value", length = 4000)
    private String universeValue;

    @Column(nullable = false, length = 8)
    private String timeframe = "1d";

    protected Screener() {
    }

    public Screener(String name, String expression, String universeType, String universeValue, String timeframe) {
        this.name = name;
        this.expression = expression;
        this.universeType = universeType;
        this.universeValue = universeValue;
        this.timeframe = timeframe;
    }

    public String getName() {
        return name;
    }

    public String getExpression() {
        return expression;
    }

    public String getUniverseType() {
        return universeType;
    }

    public String getUniverseValue() {
        return universeValue;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public void setUniverseType(String universeType) {
        this.universeType = universeType;
    }

    public void setUniverseValue(String universeValue) {
        this.universeValue = universeValue;
    }

    public void setTimeframe(String timeframe) {
        this.timeframe = timeframe;
    }
}
