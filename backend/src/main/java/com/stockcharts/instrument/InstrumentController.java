package com.stockcharts.instrument;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/instruments")
@Tag(name = "Instruments", description = "Searchable NSE instrument universe")
public class InstrumentController {

    private final InstrumentService service;

    public InstrumentController(InstrumentService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Search instruments by symbol or name (powers the dropdown)")
    public List<Instrument> search(@RequestParam(name = "query", required = false) String query) {
        return service.search(query);
    }
}
