package com.stockcharts.scanschedule;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.stockcharts.backtest.BacktestDtos.ScanRequest;

import java.util.List;

public final class ScanScheduleDtos {

    private ScanScheduleDtos() {
    }

    /**
     * Create or update a schedule. Exactly one of {@code times} ("HH:mm" IST, comma-separated) or
     * {@code intervalMinutes} (every N minutes during the NSE session) is required.
     */
    public record ScheduleRequest(String name, ScanRequest scan, String times, Integer intervalMinutes,
                                  Boolean tradingDaysOnly, Boolean onlyNew, Integer topN,
                                  @JsonProperty("notify") Boolean notifyMatches,
                                  Boolean enabled) {
    }

    public record RunRow(String symbol, String name, String signal, double close, double totalReturnPct,
                         double winRatePct, int trades, Double profitFactor, boolean isNew) {
    }

    public record RunDto(long id, long scheduleId, String scheduleName, long startedAt, long durationMs,
                         String status, int matched, int newMatches, List<RunRow> rows, String message) {
    }

    public record ScheduleDto(long id, String name, ScanRequest scan, String times, Integer intervalMinutes,
                              boolean tradingDaysOnly, boolean onlyNew, int topN,
                              @JsonProperty("notify") boolean notifyMatches, boolean enabled,
                              String description, Long nextRunAt, Long lastRunAt, RunDto lastRun) {
    }
}
