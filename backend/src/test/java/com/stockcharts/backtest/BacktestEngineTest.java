package com.stockcharts.backtest;

import com.stockcharts.backtest.BacktestDtos.BacktestResult;
import com.stockcharts.backtest.BacktestDtos.Direction;
import com.stockcharts.backtest.BacktestDtos.ExitReason;
import com.stockcharts.backtest.BacktestDtos.Trade;
import com.stockcharts.ta.PriceSeries;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktestEngineTest {

    private static final double CAPITAL = 1_000;

    /** Build a series from open prices; close = open + 0.5, high/low hug the body. */
    private static PriceSeries series(double[] open, double[] high, double[] low, double[] close) {
        int n = open.length;
        long[] t = new long[n];
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = 1_000L + i * 60L;
            v[i] = 1000;
        }
        return new PriceSeries(t, open, high, low, close, v);
    }

    private static PriceSeries ramp(double... open) {
        int n = open.length;
        double[] h = new double[n], l = new double[n], c = new double[n];
        for (int i = 0; i < n; i++) {
            c[i] = open[i] + 0.5;
            h[i] = c[i] + 0.2;
            l[i] = open[i] - 0.2;
        }
        return series(open, h, l, c);
    }

    private static double[] signalAt(int n, int... idx) {
        double[] s = new double[n];
        for (int i : idx) {
            s[i] = 1;
        }
        return s;
    }

    private static BacktestEngine.Config cfg(Direction d, Double sl, Double tp, double commission) {
        return new BacktestEngine.Config(d, sl, tp, CAPITAL, 100, commission, 0, 252);
    }

    @Test
    void signalsFillAtNextBarOpen() {
        PriceSeries s = ramp(10, 11, 12, 13, 14, 15);
        BacktestResult r = BacktestEngine.run("X", "1d", s, signalAt(6, 1), signalAt(6, 3),
                cfg(Direction.LONG, null, null, 0));

        assertEquals(1, r.trades().size());
        Trade t = r.trades().get(0);
        assertEquals(s.time()[2], t.entryTime());
        assertEquals(12, t.entryPrice());
        assertEquals(s.time()[4], t.exitTime());
        assertEquals(14, t.exitPrice());
        assertEquals(83, t.quantity()); // floor(1000 / 12)
        assertEquals(166, t.pnl(), 1e-9);
        assertEquals(2, t.barsHeld());
        assertEquals(ExitReason.SIGNAL, t.exitReason());
        assertEquals(CAPITAL + 166, r.metrics().finalEquity(), 1e-9);
    }

    @Test
    void signalOnLastBarNeverTrades() {
        PriceSeries s = ramp(10, 11, 12);
        BacktestResult r = BacktestEngine.run("X", "1d", s, signalAt(3, 2), null, cfg(Direction.LONG, null, null, 0));
        assertEquals(0, r.trades().size());
        assertEquals(CAPITAL, r.metrics().finalEquity(), 1e-9);
    }

    @Test
    void openPositionIsClosedAtEndOfData() {
        PriceSeries s = ramp(10, 10, 10, 20);
        BacktestResult r = BacktestEngine.run("X", "1d", s, signalAt(4, 0), null, cfg(Direction.LONG, null, null, 0));
        Trade t = r.trades().get(0);
        assertEquals(ExitReason.END_OF_DATA, t.exitReason());
        assertEquals(20.5, t.exitPrice());
    }

    @Test
    void stopLossExitsAtStopOrGapOpen() {
        // Enter at 100 (bar 1). Bar 2 trades down through the 5% stop (95) from an open of 98.
        double[] open = {100, 100, 98, 90};
        double[] high = {101, 101, 99, 91};
        double[] low = {99, 99, 90, 89};
        double[] close = {100, 100, 92, 90};
        BacktestResult r = BacktestEngine.run("X", "1d", series(open, high, low, close), signalAt(4, 0), null,
                cfg(Direction.LONG, 5.0, null, 0));
        Trade t = r.trades().get(0);
        assertEquals(ExitReason.STOP_LOSS, t.exitReason());
        assertEquals(95, t.exitPrice(), 1e-9);

        // Same, but bar 2 gaps below the stop: filled at the open, not the stop.
        double[] gapOpen = {100, 100, 90, 90};
        r = BacktestEngine.run("X", "1d", series(gapOpen, high, low, close), signalAt(4, 0), null,
                cfg(Direction.LONG, 5.0, null, 0));
        assertEquals(90, r.trades().get(0).exitPrice(), 1e-9);
    }

    @Test
    void shortTakeProfitIsProfitable() {
        double[] open = {100, 100, 97, 88};
        double[] high = {101, 101, 98, 89};
        double[] low = {99, 99, 85, 87};
        double[] close = {100, 100, 88, 88};
        BacktestResult r = BacktestEngine.run("X", "1d", series(open, high, low, close), signalAt(4, 0), null,
                cfg(Direction.SHORT, null, 10.0, 0));
        Trade t = r.trades().get(0);
        assertEquals(Direction.SHORT, t.side());
        assertEquals(ExitReason.TAKE_PROFIT, t.exitReason());
        assertEquals(90, t.exitPrice(), 1e-9);
        assertEquals(10 * 10, t.pnl(), 1e-9); // qty floor(1000/100)=10, 10 points each
    }

    @Test
    void commissionIsChargedOnBothLegs() {
        PriceSeries s = ramp(100, 100, 100, 100);
        BacktestResult r = BacktestEngine.run("X", "1d", s, signalAt(4, 0), signalAt(4, 1),
                cfg(Direction.LONG, null, null, 0.1));
        Trade t = r.trades().get(0);
        // 10 shares at 100 in and out: 0.1% of 1000 on each leg.
        assertEquals(10, t.quantity());
        assertEquals(-2.0, t.pnl(), 1e-9);
        assertEquals(2.0, r.metrics().commissionPaid(), 1e-9);
    }

    @Test
    void metricsDescribeWinsLossesAndDrawdown() {
        // Win then loss.
        PriceSeries s = ramp(10, 10, 20, 20, 20, 10, 10);
        BacktestResult r = BacktestEngine.run("X", "1d", s, signalAt(7, 0, 3), signalAt(7, 1, 4),
                cfg(Direction.LONG, null, null, 0));
        assertEquals(2, r.metrics().trades());
        assertEquals(1, r.metrics().wins());
        assertEquals(1, r.metrics().losses());
        assertEquals(50, r.metrics().winRatePct());
        assertTrue(r.metrics().maxDrawdownPct() > 0);
        assertTrue(r.metrics().profitFactor() > 0);
        assertEquals(7, r.equity().size());
    }

    @Test
    void noLossesMeansNoProfitFactor() {
        PriceSeries s = ramp(10, 10, 20, 20);
        BacktestResult r = BacktestEngine.run("X", "1d", s, signalAt(4, 0), signalAt(4, 1),
                cfg(Direction.LONG, null, null, 0));
        assertNull(r.metrics().profitFactor());
    }
}
