package com.stockcharts.backtest;

import java.util.List;

/** Request/response shapes for the backtester. Times are epoch seconds (UTC), like candles. */
public final class BacktestDtos {

    private BacktestDtos() {
    }

    public enum Direction { LONG, SHORT }

    /**
     * @param entry          boolean expression; when true at a bar's close, enter at the next bar's open
     * @param exit           optional boolean expression; when true, exit at the next bar's open
     * @param stopLossPct    optional stop distance from entry, in percent (checked intrabar)
     * @param takeProfitPct  optional target distance from entry, in percent (checked intrabar)
     * @param positionSizePct share of current equity committed per trade (default 100)
     * @param commissionPct  per-leg cost as percent of traded value (default 0.03)
     * @param slippagePct    adverse fill adjustment per leg, percent (default 0)
     */
    public record BacktestRequest(String symbol, String timeframe, Long from, Long to,
                                  String entry, String exit, Direction direction,
                                  Double stopLossPct, Double takeProfitPct, Double initialCapital,
                                  Double positionSizePct, Double commissionPct, Double slippagePct) {
    }

    public enum ExitReason { SIGNAL, STOP_LOSS, TAKE_PROFIT, END_OF_DATA }

    public record Trade(Direction side, long entryTime, double entryPrice, long exitTime, double exitPrice,
                        long quantity, double pnl, double pnlPct, int barsHeld, ExitReason exitReason) {
    }

    public record EquityPoint(long time, double equity, double drawdownPct) {
    }

    public record Metrics(double initialCapital, double finalEquity, double netProfit, double totalReturnPct,
                          double buyHoldReturnPct, int trades, int wins, int losses, double winRatePct,
                          double avgTradePct, double avgWinPct, double avgLossPct, Double profitFactor,
                          double maxDrawdownPct, double sharpe, double exposurePct, double avgBarsHeld,
                          double commissionPaid, int bars) {
    }

    public record BacktestResult(String symbol, String timeframe, Metrics metrics, List<Trade> trades,
                                 List<EquityPoint> equity) {
    }
}
