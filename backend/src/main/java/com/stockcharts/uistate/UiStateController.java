package com.stockcharts.uistate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

/**
 * Key/value store for UI documents (chart layout, per-symbol drawings) so they survive reloads
 * and follow the user across devices. Values are arbitrary JSON, capped at 64 KiB.
 */
@RestController
@RequestMapping("/api/v1/ui-state")
@Tag(name = "UI state", description = "Saved chart layout and drawings")
public class UiStateController {

    static final int MAX_VALUE_CHARS = 65_536;
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9:._&-]{1,128}");

    private final UiStateRepository repository;
    private final ObjectMapper mapper;

    public UiStateController(UiStateRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    private static String checkKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid key (1-128 chars of A-Z a-z 0-9 : . _ & -)");
        }
        return key;
    }

    @GetMapping("/{key}")
    @Operation(summary = "Read a saved UI document (404 when absent)")
    public ResponseEntity<JsonNode> get(@PathVariable String key) {
        return repository.findById(checkKey(key))
                .map(s -> {
                    try {
                        return ResponseEntity.ok(mapper.readTree(s.getValue()));
                    } catch (Exception e) {
                        throw new IllegalStateException("Stored value is not valid JSON", e);
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{key}")
    @Operation(summary = "Create or replace a UI document")
    public JsonNode put(@PathVariable String key, @RequestBody JsonNode value) throws Exception {
        String json = mapper.writeValueAsString(value);
        if (json.length() > MAX_VALUE_CHARS) {
            throw new IllegalArgumentException("Value too large (max " + MAX_VALUE_CHARS + " characters)");
        }
        UiState state = repository.findById(checkKey(key)).orElse(null);
        if (state == null) {
            state = new UiState(key, json);
        } else {
            state.setValue(json);
        }
        repository.save(state);
        return value;
    }

    @DeleteMapping("/{key}")
    @Operation(summary = "Delete a UI document")
    public ResponseEntity<Void> delete(@PathVariable String key) {
        if (repository.existsById(checkKey(key))) {
            repository.deleteById(key);
        }
        return ResponseEntity.noContent().build();
    }
}
