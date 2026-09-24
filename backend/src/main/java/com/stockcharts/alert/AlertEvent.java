package com.stockcharts.alert;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One firing of an alert (kept as history; denormalised so it survives alert deletion). */
@Entity
@Table(name = "alert_event")
public class AlertEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_id", nullable = false)
    private long alertId;

    @Column(name = "alert_name", nullable = false, length = 128)
    private String alertName;

    @Column(nullable = false, length = 64)
    private String symbol;

    @Column(nullable = false, length = 2000)
    private String expression;

    @Column(nullable = false)
    private double price;

    @Column(name = "bar_time", nullable = false)
    private Instant barTime;

    @Column(name = "triggered_at", nullable = false)
    private Instant triggeredAt;

    protected AlertEvent() {
    }

    public AlertEvent(Alert alert, double price, Instant barTime, Instant triggeredAt) {
        this.alertId = alert.getId();
        this.alertName = alert.getName();
        this.symbol = alert.getSymbol();
        this.expression = alert.getExpression();
        this.price = price;
        this.barTime = barTime;
        this.triggeredAt = triggeredAt;
    }

    public Long getId() {
        return id;
    }

    public long getAlertId() {
        return alertId;
    }

    public String getAlertName() {
        return alertName;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getExpression() {
        return expression;
    }

    public double getPrice() {
        return price;
    }

    public Instant getBarTime() {
        return barTime;
    }

    public Instant getTriggeredAt() {
        return triggeredAt;
    }
}
