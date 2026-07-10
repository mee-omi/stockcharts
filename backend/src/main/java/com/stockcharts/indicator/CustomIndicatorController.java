package com.stockcharts.indicator;

import com.stockcharts.expr.ExpressionService;
import com.stockcharts.indicator.dto.CustomIndicatorDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/v1/custom-indicators")
@Tag(name = "Custom Indicators", description = "User-defined expression indicators")
public class CustomIndicatorController {

    private final CustomIndicatorService service;

    public CustomIndicatorController(CustomIndicatorService service) {
        this.service = service;
    }

    public record ValidateRequest(String expression) {
    }

    private static CustomIndicatorDto toDto(CustomIndicator c) {
        return new CustomIndicatorDto(c.getName(), c.getExpression(), c.isOverlay(), c.getDescription());
    }

    @GetMapping
    @Operation(summary = "List custom indicators")
    public List<CustomIndicatorDto> list() {
        return service.list().stream().map(CustomIndicatorController::toDto).toList();
    }

    @PostMapping
    @Operation(summary = "Create a custom indicator (validates the expression)")
    public ResponseEntity<CustomIndicatorDto> create(@RequestBody CustomIndicatorDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(service.create(dto)));
    }

    @PutMapping("/{name}")
    @Operation(summary = "Update a custom indicator")
    public CustomIndicatorDto update(@PathVariable String name, @RequestBody CustomIndicatorDto dto) {
        return toDto(service.update(name, dto));
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "Delete a custom indicator")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        service.delete(name);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/validate")
    @Operation(summary = "Validate an expression without saving")
    public ExpressionService.ValidationResult validate(@RequestBody ValidateRequest req) {
        return service.validate(req.expression());
    }
}
