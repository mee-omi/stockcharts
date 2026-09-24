package com.stockcharts.indicator;

import com.stockcharts.candle.Candle;
import com.stockcharts.candle.CandleResponse;
import com.stockcharts.candle.CandleService;
import com.stockcharts.candle.Timeframe;
import com.stockcharts.indicator.dto.ApplyResponse;
import com.stockcharts.indicator.dto.IndicatorRequest;
import com.stockcharts.indicator.dto.IndicatorResult;
import com.stockcharts.instrument.InstrumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/v1/indicators")
@Tag(name = "Indicators", description = "Technical indicator catalog and computation")
public class IndicatorController {

    private final IndicatorEngine engine;
    private final CandleService candleService;
    private final InstrumentService instrumentService;
    private final CustomIndicatorService customIndicatorService;

    public IndicatorController(IndicatorEngine engine, CandleService candleService,
                               InstrumentService instrumentService,
                               CustomIndicatorService customIndicatorService) {
        this.engine = engine;
        this.candleService = candleService;
        this.instrumentService = instrumentService;
        this.customIndicatorService = customIndicatorService;
    }

    /** Catalog entry describing one indicator and its tunable parameters. */
    public record CatalogEntry(String code, String label, boolean overlay, boolean custom,
                               List<IndicatorType.ParamDef> params, List<String> outputs) {
    }

    @GetMapping
    @Operation(summary = "List available indicators (built-in + custom) with params and output lines")
    public List<CatalogEntry> catalog() {
        List<CatalogEntry> entries = new java.util.ArrayList<>(Arrays.stream(IndicatorType.values())
                .map(t -> new CatalogEntry(t.getCode(), t.getLabel(), t.isOverlay(), false,
                        t.getParams(), t.getOutputs()))
                .toList());
        customIndicatorService.list().forEach(c -> entries.add(new CatalogEntry(
                c.getName(),
                c.getDescription() != null && !c.getDescription().isBlank() ? c.getDescription() : c.getName(),
                c.isOverlay(), true, List.of(), List.of("value"))));
        return entries;
    }

    @PostMapping("/apply")
    @Operation(summary = "Apply indicators to a symbol's candles; returns candles + aligned series")
    public ApplyResponse apply(@RequestBody IndicatorRequest request) {
        if (request.symbol() == null || request.symbol().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "symbol is required");
        }
        if (!instrumentService.exists(request.symbol())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown symbol: " + request.symbol());
        }
        Timeframe tf;
        try {
            tf = Timeframe.fromCode(request.timeframe() == null ? "1m" : request.timeframe());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        Instant from = request.from() != null ? Instant.ofEpochMilli(request.from()) : null;
        Instant to = request.to() != null ? Instant.ofEpochMilli(request.to()) : null;
        List<Candle> candles = candleService.getCandles(request.symbol(), tf, from, to);

        List<IndicatorResult> results;
        try {
            results = engine.apply(tf, candles, request.indicators());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        List<CandleResponse> candleResponses = candles.stream().map(CandleResponse::from).toList();
        return new ApplyResponse(request.symbol().toUpperCase(), tf.getCode(), candleResponses, results);
    }
}
