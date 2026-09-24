package com.stockcharts.backtest;

import com.stockcharts.backtest.BacktestDtos.Direction;
import com.stockcharts.backtest.BacktestDtos.EquityPoint;
import com.stockcharts.backtest.BacktestDtos.ExitReason;
import com.stockcharts.backtest.BacktestDtos.Metrics;
import com.stockcharts.backtest.BacktestDtos.Trade;
import com.stockcharts.indicator.SeriesEvaluator;
import com.stockcharts.ta.PriceSeries;

import java.util.ArrayList;
import java.util.List;

/**
 * Bar-by-bar simulation of a single-position strategy. Deliberately free of look-ahead:
 * signals are read at a bar's close and filled at the next bar's open; stop-loss and
 * take-profit are checked against each bar's high/low (stop first when both are touched in the
 * same bar, the conservative assumption), with gaps filled at the open.
 */
public final class BacktestEngine {

    public record Config(Direction direction, Double stopLossPct, Double takeProfitPct, double initialCapital,
                         double positionSizePct, double commissionPct, double slippagePct, double barsPerYear) {
    }

    /** Equity curves longer than this are thinned for transport (metrics use the full curve). */
    static final int MAX_EQUITY_POINTS = 2_000;

    private BacktestEngine() {
    }

    private static final class Position {
        int entryIdx;
        double entryPrice;
        long qty;
        double entryCommission;
    }

    public static BacktestDtos.BacktestResult run(String symbol, String timeframe, PriceSeries s,
                                                   double[] entry, double[] exit, Config cfg) {
        int n = s.size();
        double[] open = s.open(), high = s.high(), low = s.low(), close = s.close();
        long[] time = s.time();
        boolean isLong = cfg.direction() != Direction.SHORT;
        double comm = cfg.commissionPct() / 100.0;
        double slip = cfg.slippagePct() / 100.0;

        List<Trade> trades = new ArrayList<>();
        double[] equity = new double[n];
        double realized = 0;
        double commissionPaid = 0;
        int barsInMarket = 0;
        Position pos = null;
        boolean pendingEntry = false;
        boolean pendingExit = false;

        for (int i = 0; i < n; i++) {
            // 1) Orders queued at the previous close fill at this bar's open.
            if (pendingExit && pos != null) {
                double px = fillExit(open[i], isLong, slip);
                Trade t = close(pos, i, px, ExitReason.SIGNAL, time, isLong, comm);
                realized += t.pnl();
                commissionPaid += pos.entryCommission + px * pos.qty * comm;
                trades.add(t);
                pos = null;
            }
            pendingExit = false;
            if (pendingEntry && pos == null) {
                double px = fillEntry(open[i], isLong, slip);
                double capital = cfg.initialCapital() + realized;
                long qty = (long) Math.floor(capital * cfg.positionSizePct() / 100.0 / px);
                if (qty >= 1) {
                    pos = new Position();
                    pos.entryIdx = i;
                    pos.entryPrice = px;
                    pos.qty = qty;
                    pos.entryCommission = px * qty * comm;
                }
            }
            pendingEntry = false;

            // 2) Protective exits inside this bar.
            if (pos != null) {
                Double stop = level(pos.entryPrice, cfg.stopLossPct(), isLong, true);
                Double target = level(pos.entryPrice, cfg.takeProfitPct(), isLong, false);
                ExitReason reason = null;
                double px = 0;
                if (stop != null && (isLong ? low[i] <= stop : high[i] >= stop)) {
                    reason = ExitReason.STOP_LOSS;
                    px = isLong ? Math.min(open[i], stop) : Math.max(open[i], stop);
                    // A position opened at this bar's open cannot gap through its own stop.
                    if (pos.entryIdx == i) {
                        px = stop;
                    }
                } else if (target != null && (isLong ? high[i] >= target : low[i] <= target)) {
                    reason = ExitReason.TAKE_PROFIT;
                    px = isLong ? Math.max(open[i], target) : Math.min(open[i], target);
                    if (pos.entryIdx == i) {
                        px = target;
                    }
                }
                if (reason != null) {
                    px = fillExit(px, isLong, slip);
                    Trade t = close(pos, i, px, reason, time, isLong, comm);
                    realized += t.pnl();
                    commissionPaid += pos.entryCommission + px * pos.qty * comm;
                    trades.add(t);
                    pos = null;
                }
            }

            // 3) Mark to market at the close.
            double unrealized = 0;
            if (pos != null) {
                barsInMarket++;
                unrealized = (isLong ? close[i] - pos.entryPrice : pos.entryPrice - close[i]) * pos.qty
                        - pos.entryCommission;
            }
            equity[i] = cfg.initialCapital() + realized + unrealized;

            // 4) Signals at this close queue orders for the next bar.
            if (i < n - 1) {
                if (pos == null && SeriesEvaluator.truthy(entry, i)) {
                    pendingEntry = true;
                } else if (pos != null && exit != null && SeriesEvaluator.truthy(exit, i)) {
                    pendingExit = true;
                }
            }
        }

        if (pos != null) {
            double px = close[n - 1];
            Trade t = close(pos, n - 1, px, ExitReason.END_OF_DATA, time, isLong, comm);
            realized += t.pnl();
            commissionPaid += pos.entryCommission + px * pos.qty * comm;
            trades.add(t);
            equity[n - 1] = cfg.initialCapital() + realized;
        }

        Metrics metrics = metrics(cfg, s, equity, trades, barsInMarket, commissionPaid);
        return new BacktestDtos.BacktestResult(symbol, timeframe, metrics, trades, curve(time, equity));
    }

    private static double fillEntry(double price, boolean isLong, double slip) {
        return isLong ? price * (1 + slip) : price * (1 - slip);
    }

    private static double fillExit(double price, boolean isLong, double slip) {
        return isLong ? price * (1 - slip) : price * (1 + slip);
    }

    /** Stop (adverse) or target (favourable) price for the position, or null when unset. */
    private static Double level(double entry, Double pct, boolean isLong, boolean adverse) {
        if (pct == null || pct <= 0) {
            return null;
        }
        double d = pct / 100.0;
        boolean below = isLong == adverse;
        return below ? entry * (1 - d) : entry * (1 + d);
    }

    private static Trade close(Position p, int i, double exitPx, ExitReason reason, long[] time,
                               boolean isLong, double comm) {
        double gross = (isLong ? exitPx - p.entryPrice : p.entryPrice - exitPx) * p.qty;
        double net = gross - p.entryCommission - exitPx * p.qty * comm;
        double pct = net / (p.entryPrice * p.qty) * 100.0;
        return new Trade(isLong ? Direction.LONG : Direction.SHORT, time[p.entryIdx], round(p.entryPrice),
                time[i], round(exitPx), p.qty, round(net), round(pct), i - p.entryIdx, reason);
    }

    private static Metrics metrics(Config cfg, PriceSeries s, double[] equity, List<Trade> trades,
                                   int barsInMarket, double commissionPaid) {
        int n = equity.length;
        double initial = cfg.initialCapital();
        double finalEq = n == 0 ? initial : equity[n - 1];

        int wins = 0, losses = 0;
        double grossWin = 0, grossLoss = 0, sumPct = 0, sumWinPct = 0, sumLossPct = 0, sumBars = 0;
        for (Trade t : trades) {
            sumPct += t.pnlPct();
            sumBars += t.barsHeld();
            if (t.pnl() > 0) {
                wins++;
                grossWin += t.pnl();
                sumWinPct += t.pnlPct();
            } else {
                losses++;
                grossLoss += -t.pnl();
                sumLossPct += t.pnlPct();
            }
        }
        int count = trades.size();

        double peak = initial, maxDd = 0;
        for (double e : equity) {
            peak = Math.max(peak, e);
            maxDd = Math.max(maxDd, peak > 0 ? (peak - e) / peak : 0);
        }

        // Sharpe on per-bar equity returns, annualised; zero when there is no variance.
        double mean = 0, var = 0;
        int rets = Math.max(0, n - 1);
        double[] r = new double[rets];
        for (int i = 1; i < n; i++) {
            double prev = equity[i - 1];
            r[i - 1] = prev != 0 ? equity[i] / prev - 1 : 0;
            mean += r[i - 1];
        }
        if (rets > 0) {
            mean /= rets;
            for (double x : r) {
                var += (x - mean) * (x - mean);
            }
            var = rets > 1 ? var / (rets - 1) : 0;
        }
        double sd = Math.sqrt(var);
        double sharpe = sd > 0 ? mean / sd * Math.sqrt(cfg.barsPerYear()) : 0;

        double buyHold = n > 0 && s.open()[0] > 0 ? (s.close()[n - 1] / s.open()[0] - 1) * 100 : 0;

        return new Metrics(round(initial), round(finalEq), round(finalEq - initial),
                round((finalEq / initial - 1) * 100), round(buyHold), count, wins, losses,
                count > 0 ? round(wins * 100.0 / count) : 0,
                count > 0 ? round(sumPct / count) : 0,
                wins > 0 ? round(sumWinPct / wins) : 0,
                losses > 0 ? round(sumLossPct / losses) : 0,
                grossLoss > 0 ? round(grossWin / grossLoss) : null,
                round(maxDd * 100), round(sharpe),
                n > 0 ? round(barsInMarket * 100.0 / n) : 0,
                count > 0 ? round(sumBars / count) : 0,
                round(commissionPaid), n);
    }

    private static List<EquityPoint> curve(long[] time, double[] equity) {
        int n = equity.length;
        int step = Math.max(1, (int) Math.ceil(n / (double) MAX_EQUITY_POINTS));
        List<EquityPoint> out = new ArrayList<>(Math.min(n, MAX_EQUITY_POINTS) + 1);
        double peak = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            peak = Math.max(peak, equity[i]);
            if (i % step == 0 || i == n - 1) {
                double dd = peak > 0 ? (equity[i] - peak) / peak * 100 : 0;
                out.add(new EquityPoint(time[i], round(equity[i]), round(dd)));
            }
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
