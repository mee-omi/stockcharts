package com.stockcharts.candle;

import com.stockcharts.instrument.InstrumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/candles")
@Tag(name = "Candles", description = "Historical/recent OHLCV candles by symbol and timeframe")
public class CandleController {

    private final CandleService candleService;
    private final InstrumentService instrumentService;

    public CandleController(CandleService candleService, InstrumentService instrumentService) {
        this.candleService = candleService;
        this.instrumentService = instrumentService;
    }

    @GetMapping("/{symbol}")
    @Operation(summary = "Candles for a symbol at a timeframe; from/to are epoch millis (UTC)")
    public ResponseEntity<List<CandleResponse>> candles(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1m") String timeframe,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to) {

        Timeframe tf;
        try {
            tf = Timeframe.fromCode(timeframe);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (!instrumentService.exists(symbol)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown symbol: " + symbol);
        }
        Instant fromI = from != null ? Instant.ofEpochMilli(from) : null;
        Instant toI = to != null ? Instant.ofEpochMilli(to) : null;
        return ResponseEntity.ok(candleService.getCandleResponses(symbol, tf, fromI, toI));
    }
}
