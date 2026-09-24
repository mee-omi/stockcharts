package com.stockcharts.uistate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A small JSON document saved for the UI, e.g. the chart layout or one symbol's drawings. */
@Entity
@Table(name = "ui_state")
public class UiState {

    @Id
    @Column(name = "state_key", length = 128)
    private String key;

    @Column(name = "state_value", nullable = false, length = UiStateController.MAX_VALUE_CHARS)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UiState() {
    }

    public UiState(String key, String value) {
        this.key = key;
        this.value = value;
        this.updatedAt = Instant.now();
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setValue(String value) {
        this.value = value;
        this.updatedAt = Instant.now();
    }
}
