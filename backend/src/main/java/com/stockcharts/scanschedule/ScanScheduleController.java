package com.stockcharts.scanschedule;

import com.stockcharts.scanschedule.ScanScheduleDtos.RunDto;
import com.stockcharts.scanschedule.ScanScheduleDtos.ScheduleDto;
import com.stockcharts.scanschedule.ScanScheduleDtos.ScheduleRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/scan-schedules")
@Tag(name = "Scheduled scans", description = "Backtest scans that run at IST times and notify their matches")
public class ScanScheduleController {

    private final ScanScheduleService service;

    public ScanScheduleController(ScanScheduleService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "All schedules with their next run and last result")
    public List<ScheduleDto> list() {
        return service.list();
    }

    @PostMapping
    @Operation(summary = "Create a schedule (times in IST like \"15:35\", or intervalMinutes during market hours)")
    public ScheduleDto create(@RequestBody ScheduleRequest req) {
        return service.create(req);
    }

    @GetMapping("/{id}")
    public ScheduleDto get(@PathVariable long id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a schedule; omitted fields keep their value")
    public ScheduleDto update(@PathVariable long id, @RequestBody ScheduleRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/run")
    @Operation(summary = "Run now (does not move the regular schedule)")
    public RunDto run(@PathVariable long id) {
        return service.runNow(id);
    }

    @GetMapping("/{id}/runs")
    @Operation(summary = "The last 30 runs, newest first")
    public List<RunDto> runs(@PathVariable long id) {
        return service.history(id);
    }
}
