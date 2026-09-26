package com.stockcharts.scanschedule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

interface ScanScheduleRepository extends JpaRepository<ScanSchedule, Long> {
    List<ScanSchedule> findAllByOrderByNameAsc();

    List<ScanSchedule> findByEnabledTrueAndNextRunAtLessThanEqual(Instant now);

    boolean existsByNameIgnoreCase(String name);
}

interface ScanRunRepository extends JpaRepository<ScanRun, Long> {
    List<ScanRun> findTop30ByScheduleIdOrderByStartedAtDesc(Long scheduleId);

    Optional<ScanRun> findFirstByScheduleIdAndStatusOrderByStartedAtDesc(Long scheduleId, String status);

    List<ScanRun> findByScheduleIdOrderByStartedAtDesc(Long scheduleId);
}
