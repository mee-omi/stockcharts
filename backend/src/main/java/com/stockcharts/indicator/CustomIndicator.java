package com.stockcharts.indicator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A user-defined indicator: a {@code name} (usable as an expression identifier) plus an
 * {@code expression} evaluated by the expression engine. One output line ("value").
 */
@Entity
@Table(name = "custom_indicator")
public class CustomIndicator {

    @Id
    @Column(length = 64)
    private String name;

    @Column(nullable = false, length = 2000)
    private String expression;

    @Column(nullable = false)
    private boolean overlay;

    @Column(length = 512)
    private String description;

    protected CustomIndicator() {
    }

    public CustomIndicator(String name, String expression, boolean overlay, String description) {
        this.name = name;
        this.expression = expression;
        this.overlay = overlay;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getExpression() {
        return expression;
    }

    public boolean isOverlay() {
        return overlay;
    }

    public String getDescription() {
        return description;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public void setOverlay(boolean overlay) {
        this.overlay = overlay;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
