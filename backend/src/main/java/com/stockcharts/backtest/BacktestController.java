package com.stockcharts.backtest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/backtest")
@Tag(name = "Backtest", description = "Rule-based strategy backtests on historical candles")
public class BacktestController {

    private final BacktestService service;

    public BacktestController(BacktestService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Run a backtest: entry/exit expressions, optional stop-loss/target, costs")
    public BacktestDtos.BacktestResult run(@RequestBody BacktestDtos.BacktestRequest request) {
        return service.run(request);
    }
}
