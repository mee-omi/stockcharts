package com.stockcharts.scanschedule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "scan_schedule")
class ScanSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(nullable = false, unique = true, length = 100)
    String name;

    /** The {@code ScanRequest} to run, as JSON. */
    @Column(name = "request_json", nullable = false, length = 20000)
    String requestJson;

    /** IST times of day, "HH:mm" comma-separated (e.g. "09:30,15:35"); null in interval mode. */
    @Column(name = "run_times", length = 200)
    String runTimes;

    /** Every N minutes during the NSE session; null in times mode. */
    @Column(name = "interval_minutes")
    Integer intervalMinutes;

    @Column(name = "trading_days_only", nullable = false)
    boolean tradingDaysOnly = true;

    @Column(name = "only_new", nullable = false)
    boolean onlyNew;

    @Column(name = "top_n", nullable = false)
    int topN = 10;

    @Column(nullable = false)
    boolean notify = true;

    @Column(nullable = false)
    boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    Instant createdAt = Instant.now();

    @Column(name = "next_run_at")
    Instant nextRunAt;

    @Column(name = "last_run_at")
    Instant lastRunAt;
}
