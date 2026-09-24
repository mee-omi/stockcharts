package com.stockcharts.alert;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A condition watched on one symbol/timeframe. The condition is a boolean expression in the same
 * language as screeners (price templates such as "crosses above 3000" compile to
 * {@code crossover(close, 3000)}), evaluated at the latest bar.
 */
@Entity
@Table(name = "alert")
public class Alert {

    public enum TriggerMode {
        /** Fire once, then move to TRIGGERED. */
        ONCE,
        /** Fire at most once per bar while the condition holds. */
        EVERY_BAR
    }

    public enum Status {
        ACTIVE, TRIGGERED, PAUSED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 64)
    private String symbol;

    @Column(nullable = false, length = 8)
    private String timeframe;

    @Column(nullable = false, length = 2000)
    private String expression;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_mode", nullable = false, length = 16)
    private TriggerMode triggerMode = TriggerMode.ONCE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_triggered_at")
    private Instant lastTriggeredAt;

    @Column(name = "last_bar_time")
    private Instant lastBarTime;

    @Column(name = "trigger_count", nullable = false)
    private int triggerCount;

    protected Alert() {
    }

    public Alert(String name, String symbol, String timeframe, String expression, TriggerMode triggerMode) {
        this.name = name;
        this.symbol = symbol;
        this.timeframe = timeframe;
        this.expression = expression;
        this.triggerMode = triggerMode;
        this.createdAt = Instant.now();
    }

    /** Record a trigger on the bar opening at {@code barTime}. */
    public void markTriggered(Instant at, Instant barTime) {
        this.lastTriggeredAt = at;
        this.lastBarTime = barTime;
        this.triggerCount++;
        if (triggerMode == TriggerMode.ONCE) {
            this.status = Status.TRIGGERED;
        }
    }

    /** Whether a passing evaluation on {@code barTime} should fire. */
    public boolean shouldFire(Instant barTime) {
        if (status != Status.ACTIVE) {
            return false;
        }
        return triggerMode == TriggerMode.ONCE || lastBarTime == null || barTime.isAfter(lastBarTime);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public void setTimeframe(String timeframe) {
        this.timeframe = timeframe;
    }

    public String getExpression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public TriggerMode getTriggerMode() {
        return triggerMode;
    }

    public void setTriggerMode(TriggerMode triggerMode) {
        this.triggerMode = triggerMode;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastTriggeredAt() {
        return lastTriggeredAt;
    }

    public Instant getLastBarTime() {
        return lastBarTime;
    }

    public int getTriggerCount() {
        return triggerCount;
    }

    /** Re-arming clears the bar memory so the alert can fire again immediately. */
    public void rearm() {
        this.status = Status.ACTIVE;
        this.lastBarTime = null;
    }
}
