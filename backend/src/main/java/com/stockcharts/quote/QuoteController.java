package com.stockcharts.quote;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/v1/quotes")
@Tag(name = "Quotes", description = "Last price and day change for a list of symbols")
public class QuoteController {

    private final QuoteService service;

    public QuoteController(QuoteService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Quotes for comma-separated symbols (max 100); unknown symbols are skipped")
    public List<QuoteService.Quote> quotes(@RequestParam String symbols) {
        return service.quotes(Arrays.asList(symbols.split(",")));
    }
}
