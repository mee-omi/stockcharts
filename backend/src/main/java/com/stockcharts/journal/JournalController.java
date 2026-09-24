package com.stockcharts.journal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "Portfolio", description = "Trade journal, realised P&L, charges and tax report")
public class JournalController {

    private final JournalService service;
    private final JournalRepository repository;

    public JournalController(JournalService service, JournalRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    public record EntryDto(long id, String orderId, String broker, String symbol, String exchange, String side,
                           String product, int quantity, double price, long executedAt, String tag,
                           String strategy, String notes, String source) {
        static EntryDto from(JournalEntry e) {
            return new EntryDto(e.getId(), e.getOrderId(), e.getBroker(), e.getSymbol(), e.getExchange(),
                    e.getSide().name(), e.getProduct(), e.getQuantity(), e.getPrice(),
                    e.getExecutedAt().toEpochMilli(), e.getTag(), e.getStrategy(), e.getNotes(),
                    e.getSource().name());
        }
    }

    @GetMapping("/api/v1/journal")
    @Operation(summary = "All journalled fills, newest first")
    public List<EntryDto> list() {
        return service.list().stream().map(EntryDto::from).toList();
    }

    @PostMapping("/api/v1/journal")
    @Operation(summary = "Add a trade manually (e.g. one executed outside StockCharts)")
    public EntryDto add(@RequestBody JournalService.ManualEntry entry) {
        return EntryDto.from(service.addManual(entry));
    }

    @PutMapping("/api/v1/journal/{id}")
    @Operation(summary = "Set the strategy label and notes on a journal entry")
    public EntryDto annotate(@PathVariable long id, @RequestBody JournalService.Annotation a) {
        return EntryDto.from(service.annotate(id, a));
    }

    @DeleteMapping("/api/v1/journal/{id}")
    @Operation(summary = "Delete a journal entry")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/journal/sync")
    @Operation(summary = "Import completed orders from the active broker's order book")
    public Map<String, Object> sync() {
        return Map.of("added", service.sync());
    }

    private PortfolioReport.Report report(Long from, Long to, String broker) {
        List<JournalEntry> entries = repository.findAllByOrderByExecutedAtAscIdAsc().stream()
                .filter(e -> broker == null || broker.isBlank() || broker.equalsIgnoreCase(e.getBroker()))
                .toList();
        return PortfolioReport.build(entries, from == null ? null : Instant.ofEpochMilli(from),
                to == null ? null : Instant.ofEpochMilli(to));
    }

    @GetMapping("/api/v1/portfolio/report")
    @Operation(summary = "Realised P&L (FIFO), charges, tax classification; from/to = close time, epoch ms")
    public PortfolioReport.Report getReport(@RequestParam(required = false) Long from,
                                            @RequestParam(required = false) Long to,
                                            @RequestParam(required = false) String broker) {
        return report(from, to, broker);
    }

    @GetMapping(value = "/api/v1/portfolio/report.csv", produces = "text/csv")
    @Operation(summary = "Realised trades as CSV (for tax filing / spreadsheets)")
    public ResponseEntity<String> csv(@RequestParam(required = false) Long from,
                                      @RequestParam(required = false) Long to,
                                      @RequestParam(required = false) String broker) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"stockcharts-pnl.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(PortfolioReport.toCsv(report(from, to, broker)));
    }
}
