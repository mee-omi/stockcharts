package com.stockcharts.screener;

import com.stockcharts.screener.dto.ScreenerDef;
import com.stockcharts.screener.dto.ScreenerRequest;
import com.stockcharts.screener.dto.ScreenerResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/v1/screener")
@Tag(name = "Screener", description = "Run and save multi-indicator stock screeners")
public class ScreenerController {

    private final ScreenerService service;
    private final ScreenerRepository repository;

    public ScreenerController(ScreenerService service, ScreenerRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    private static ScreenerDef toDef(Screener s) {
        return new ScreenerDef(s.getName(), s.getExpression(),
                new ScreenerRequest.Universe(s.getUniverseType(), s.getUniverseValue()), s.getTimeframe());
    }

    public record UniverseInfo(String code, String label, int size) {
    }

    @GetMapping("/universes")
    @Operation(summary = "List the named index universes available to the screener")
    public List<UniverseInfo> universes() {
        return IndexUniverses.all().stream()
                .map(d -> new UniverseInfo(d.code(), d.label(), d.symbols().size())).toList();
    }

    @PostMapping("/run")
    @Operation(summary = "Run an ad-hoc screener over a universe")
    public ScreenerResult run(@RequestBody ScreenerRequest request) {
        return service.run(request);
    }

    @GetMapping
    @Operation(summary = "List saved screeners")
    public List<ScreenerDef> list() {
        return repository.findAll().stream().map(ScreenerController::toDef).toList();
    }

    @PostMapping
    @Operation(summary = "Create or update a saved screener")
    public ScreenerDef save(@RequestBody ScreenerDef def) {
        if (def.name() == null || def.name().isBlank()) {
            throw new IllegalArgumentException("Screener name is required");
        }
        String uType = def.universe() != null ? def.universe().type() : "index";
        String uValue = def.universe() != null ? def.universe().value() : null;
        Screener saved = repository.save(new Screener(def.name(), def.expression(), uType, uValue,
                def.timeframe() == null ? "1d" : def.timeframe()));
        return toDef(saved);
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "Delete a saved screener")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        if (!repository.existsById(name)) {
            throw new NoSuchElementException("Screener not found: " + name);
        }
        repository.deleteById(name);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{name}/run")
    @Operation(summary = "Run a saved screener")
    public ScreenerResult runSaved(@PathVariable String name) {
        Screener s = repository.findById(name)
                .orElseThrow(() -> new NoSuchElementException("Screener not found: " + name));
        ScreenerRequest req = new ScreenerRequest(
                s.getExpression(), s.getTimeframe(),
                new ScreenerRequest.Universe(s.getUniverseType(), s.getUniverseValue()),
                null, null);
        return service.run(req);
    }
}
