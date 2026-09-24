package com.stockcharts.backtest;

import com.stockcharts.backtest.BacktestDtos.Metrics;
import com.stockcharts.backtest.BacktestDtos.ScanRequest;
import com.stockcharts.backtest.BacktestDtos.ScanRow;
import com.stockcharts.backtest.BacktestDtos.ScanSort;
import com.stockcharts.backtest.BacktestDtos.SignalState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktestScanOrderingTest {

    private static ScanRow row(String sym, double ret, int trades, int wins, Double pf, double dd, SignalState sig) {
        Metrics m = new Metrics(100_000, 100_000 * (1 + ret / 100), ret * 1000, ret, 0, trades, wins, trades - wins,
                trades == 0 ? 0 : wins * 100.0 / trades, 0, 0, 0, pf, dd, 0, 0, 0, 0, 100);
        return new ScanRow(sym, sym, new BacktestDtos.Latest(0, 1, sig, null, null, null), m);
    }

    private static ScanRequest req(ScanSort sort, Integer minTrades, Double minPf, Boolean onlySignals) {
        return new ScanRequest(null, null, sort, minTrades, null, minPf, onlySignals, null);
    }

    @Test
    void sortsByChosenMetricWithDrawdownAscending() {
        ScanRow a = row("A", 10, 5, 3, 1.5, 20, SignalState.NONE);
        ScanRow b = row("B", 30, 2, 2, null, 5, SignalState.ENTRY); // no losses → infinite PF
        ScanRow c = row("C", -5, 8, 2, 0.5, 40, SignalState.EXIT);

        assertEquals(List.of("B", "A", "C"), sorted(ScanSort.TOTAL_RETURN, a, b, c));
        assertEquals(List.of("B", "A", "C"), sorted(ScanSort.PROFIT_FACTOR, a, b, c));
        assertEquals(List.of("B", "A", "C"), sorted(ScanSort.MAX_DRAWDOWN, a, b, c));
        assertEquals(List.of("C", "A", "B"), sorted(ScanSort.TRADES, a, b, c));
    }

    @Test
    void filtersOnTradesProfitFactorAndSignals() {
        ScanRow few = row("A", 10, 1, 1, null, 5, SignalState.ENTRY);
        ScanRow weak = row("B", 1, 10, 4, 0.8, 5, SignalState.EXIT);
        ScanRow idle = row("C", 5, 10, 6, 1.4, 5, SignalState.IN_POSITION);

        assertFalse(BacktestService.passes(few, req(null, 3, null, null)));
        assertFalse(BacktestService.passes(weak, req(null, null, 1.0, null)));
        assertTrue(BacktestService.passes(few, req(null, null, 1.0, null))); // infinite PF passes
        assertTrue(BacktestService.passes(weak, req(null, null, null, true)));
        assertFalse(BacktestService.passes(idle, req(null, null, null, true)));
    }

    private static List<String> sorted(ScanSort sort, ScanRow... rows) {
        return Stream.of(rows).sorted(BacktestService.order(sort)).map(ScanRow::symbol).toList();
    }
}
