package com.stockcharts.screener;

import com.stockcharts.screener.dto.WatchlistDto;
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
@RequestMapping("/api/v1/watchlists")
@Tag(name = "Watchlists", description = "Named, reusable symbol lists for screeners")
public class WatchlistController {

    private final WatchlistRepository repository;

    public WatchlistController(WatchlistRepository repository) {
        this.repository = repository;
    }

    private static WatchlistDto toDto(Watchlist w) {
        return new WatchlistDto(w.getName(), w.getSymbols());
    }

    @GetMapping
    @Operation(summary = "List watchlists")
    public List<WatchlistDto> list() {
        return repository.findAll().stream().map(WatchlistController::toDto).toList();
    }

    @GetMapping("/{name}")
    @Operation(summary = "Get a watchlist")
    public WatchlistDto get(@PathVariable String name) {
        return repository.findById(name).map(WatchlistController::toDto)
                .orElseThrow(() -> new NoSuchElementException("Watchlist not found: " + name));
    }

    @PostMapping
    @Operation(summary = "Create or update a watchlist")
    public WatchlistDto save(@RequestBody WatchlistDto dto) {
        if (dto.name() == null || dto.name().isBlank()) {
            throw new IllegalArgumentException("Watchlist name is required");
        }
        return toDto(repository.save(new Watchlist(dto.name(), dto.symbols())));
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "Delete a watchlist")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        if (!repository.existsById(name)) {
            throw new NoSuchElementException("Watchlist not found: " + name);
        }
        repository.deleteById(name);
        return ResponseEntity.noContent().build();
    }
}
