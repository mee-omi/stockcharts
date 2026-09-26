package com.stockcharts.scanschedule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "scan_run")
class ScanRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "schedule_id", nullable = false)
    Long scheduleId;

    @Column(name = "started_at", nullable = false)
    Instant startedAt;

    @Column(name = "duration_ms", nullable = false)
    long durationMs;

    /** OK or FAILED. */
    @Column(nullable = false, length = 16)
    String status;

    @Column(nullable = false)
    int matched;

    @Column(name = "new_matches", nullable = false)
    int newMatches;

    /** The notified rows ({@link ScanScheduleDtos.RunRow}) as JSON. */
    @Column(name = "rows_json", length = 20000)
    String rowsJson;

    @Column(length = 1000)
    String message;
}
