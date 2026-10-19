package com.stockcharts.paper;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** The single paper-trading account (id 1). */
@Entity
@Table(name = "paper_account")
class PaperAccount {

    static final short ID = 1;

    @Id
    short id = ID;

    @Column(name = "starting_cash", nullable = false)
    double startingCash;

    @Column(nullable = false)
    double cash;

    /** The IST trading day the day-state (positions, day orders) belongs to. */
    @Column(name = "trading_day", nullable = false)
    LocalDate tradingDay;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    protected PaperAccount() {
    }

    PaperAccount(double startingCash, LocalDate day) {
        this.startingCash = startingCash;
        this.cash = startingCash;
        this.tradingDay = day;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }
}
