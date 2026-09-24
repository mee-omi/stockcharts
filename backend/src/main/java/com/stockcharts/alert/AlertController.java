package com.stockcharts.alert;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/alerts")
@Tag(name = "Alerts", description = "Price / expression alerts with browser, Telegram and webhook delivery")
public class AlertController {

    private final AlertService service;

    public AlertController(AlertService service) {
        this.service = service;
    }

    public record StatusRequest(Alert.Status status) {
    }

    @GetMapping
    @Operation(summary = "List alerts (newest first)")
    public List<AlertDtos.AlertDto> list() {
        return service.list().stream().map(AlertDtos.AlertDto::from).toList();
    }

    @PostMapping
    @Operation(summary = "Create an alert; the expression is validated like a screener condition")
    public AlertDtos.AlertDto create(@RequestBody AlertDtos.AlertRequest req) {
        return AlertDtos.AlertDto.from(service.create(req));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an alert (re-arms it)")
    public AlertDtos.AlertDto update(@PathVariable long id, @RequestBody AlertDtos.AlertRequest req) {
        return AlertDtos.AlertDto.from(service.update(id, req));
    }

    @PostMapping("/{id}/status")
    @Operation(summary = "Pause, or re-arm (ACTIVE) an alert")
    public AlertDtos.AlertDto setStatus(@PathVariable long id, @RequestBody StatusRequest req) {
        if (req.status() == null) {
            throw new IllegalArgumentException("status is required");
        }
        return AlertDtos.AlertDto.from(service.setStatus(id, req.status()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an alert (its history is kept)")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/events")
    @Operation(summary = "Recently fired alerts (newest first)")
    public List<AlertDtos.AlertEventDto> events(@RequestParam(defaultValue = "50") int limit) {
        return service.recentEvents(limit).stream().map(AlertDtos.AlertEventDto::from).toList();
    }

    @PostMapping("/evaluate")
    @Operation(summary = "Evaluate all active alerts now (normally runs every 15s)")
    public List<AlertDtos.AlertEventDto> evaluateNow() {
        return service.evaluateAll().stream().map(AlertDtos.AlertEventDto::from).toList();
    }
}
