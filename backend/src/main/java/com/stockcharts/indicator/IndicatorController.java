package com.stockcharts.indicator;

import com.stockcharts.candle.Candle;
import com.stockcharts.indicator.dto.IndicatorSpec;
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

    /**
     * Live refresh: the chart's latest bars (including the forming one, which storage may not have
     * yet at higher timeframes) merged over stored history.
     *
     * @param from  the start of the chart's range (as sent to /apply), or null for the default
     * @param bars  up to 10 of the chart's newest bars; they replace stored bars with the same time
     * @param tail  how many trailing points per line to return (default 3, at most 50)
     */
    public record LiveRequest(String symbol, String timeframe, Long from, List<IndicatorSpec> indicators,
                              List<CandleResponse> bars, Integer tail) {
    }

    @PostMapping("/live")
    @Operation(summary = "Recompute indicators with the chart's live bars; returns only the newest points")
    public List<IndicatorResult> live(@RequestBody LiveRequest request) {
        if (request.symbol() == null || !instrumentService.exists(request.symbol())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown symbol: " + request.symbol());
        }
        if (request.indicators() == null || request.indicators().isEmpty()) {
            return List.of();
        }
        List<CandleResponse> bars = request.bars() == null ? List.of() : request.bars();
        if (bars.size() > 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most 10 live bars");
        }
        int tail = request.tail() == null ? 3 : Math.max(1, Math.min(50, request.tail()));
        Timeframe tf;
        try {
            tf = Timeframe.fromCode(request.timeframe() == null ? "1m" : request.timeframe());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        String symbol = request.symbol().toUpperCase();
        Instant from = request.from() != null ? Instant.ofEpochMilli(request.from()) : null;
        Instant to = from != null ? Instant.now() : null;

        java.util.TreeMap<Long, Candle> merged = new java.util.TreeMap<>();
        for (Candle c : candleService.getCandles(symbol, tf, from, to)) {
            merged.put(c.getOpenTime().getEpochSecond(), c);
        }
        for (CandleResponse b : bars) {
            merged.put(b.time(), new Candle(symbol, tf, Instant.ofEpochSecond(b.time()),
                    b.open(), b.high(), b.low(), b.close(), b.volume()));
        }
        List<IndicatorResult> results;
        try {
            results = engine.apply(tf, new java.util.ArrayList<>(merged.values()), request.indicators());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return results.stream().map(r -> new IndicatorResult(r.key(), r.type(), r.overlay(), r.lines().stream()
                .map(l -> new IndicatorResult.Line(l.name(),
                        l.points().subList(Math.max(0, l.points().size() - tail), l.points().size())))
                .toList())).toList();
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
