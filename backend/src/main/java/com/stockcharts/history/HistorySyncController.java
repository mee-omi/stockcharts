package com.stockcharts.history;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/history-sync")
@Tag(name = "History sync", description = "Scheduled download of broker history into the candle store")
public class HistorySyncController {

    private final HistorySyncService service;

    public HistorySyncController(HistorySyncService service) {
        this.service = service;
    }

    public record SyncInfo(HistorySyncService.Status status, int trackedSymbols, Map<String, Long> lookbackDays) {
    }

    @GetMapping
    @Operation(summary = "Status of the current or last sync, and what a sync covers")
    public SyncInfo info() {
        HistorySyncService.Plan plan = service.plan();
        Map<String, Long> lookback = plan.timeframes().entrySet().stream().collect(Collectors.toMap(
                e -> e.getKey().getCode(), e -> e.getValue().toDays(), (a, b) -> a, java.util.LinkedHashMap::new));
        return new SyncInfo(service.status(), plan.symbols().size(), lookback);
    }

    @PostMapping("/run")
    @Operation(summary = "Start a sync now (runs in the background; 409 if one is running)")
    public ResponseEntity<HistorySyncService.Status> run() {
        boolean started = service.trigger("manual");
        return ResponseEntity.status(started ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT).body(service.status());
    }
}
