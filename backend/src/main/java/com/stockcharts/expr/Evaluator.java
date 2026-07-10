package com.stockcharts.expr;

import com.stockcharts.ta.Indicators;
import com.stockcharts.ta.PriceSeries;

import java.util.List;

/** Evaluates an {@link Expr} tree to a {@code double[]} series, element-wise. */
final class Evaluator {

    private Evaluator() {
    }

    static double[] eval(Expr e, EvalContext ctx) {
        return switch (e) {
            case Expr.Num n -> fill(n.value(), ctx.size());
            case Expr.Var v -> resolveVar(v.name(), ctx);
            case Expr.Unary u -> unary(u, ctx);
            case Expr.Binary b -> binary(b, ctx);
            case Expr.Call c -> call(c, ctx);
        };
    }

    private static double[] resolveVar(String name, EvalContext ctx) {
        PriceSeries s = ctx.series();
        return switch (name.toLowerCase()) {
            case "open" -> s.open();
            case "high" -> s.high();
            case "low" -> s.low();
            case "close" -> s.close();
            case "volume" -> s.volume();
            case "hl2" -> s.hl2();
            case "hlc3", "typical" -> s.hlc3();
            case "ohlc4" -> s.ohlc4();
            default -> {
                double[] custom = ctx.custom(name);
                if (custom == null) {
                    throw new ExpressionException("Unknown identifier '" + name
                            + "'. Use a price field (open/high/low/close/volume/hl2/hlc3/ohlc4) "
                            + "or a defined custom indicator.");
                }
                yield custom;
            }
        };
    }

    private static double[] unary(Expr.Unary u, EvalContext ctx) {
        double[] a = eval(u.operand(), ctx);
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            if (u.op().equals("-")) {
                out[i] = -a[i];
            } else { // "not"
                out[i] = Double.isNaN(a[i]) ? Double.NaN : (truthy(a[i]) ? 0.0 : 1.0);
            }
        }
        return out;
    }

    private static double[] binary(Expr.Binary b, EvalContext ctx) {
        double[] l = eval(b.left(), ctx);
        double[] r = eval(b.right(), ctx);
        int n = Math.min(l.length, r.length);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double a = l[i], c = r[i];
            out[i] = switch (b.op()) {
                case "+" -> a + c;
                case "-" -> a - c;
                case "*" -> a * c;
                case "/" -> a / c;
                case "%" -> a % c;
                case ">" -> cmp(a, c, a > c);
                case ">=" -> cmp(a, c, a >= c);
                case "<" -> cmp(a, c, a < c);
                case "<=" -> cmp(a, c, a <= c);
                case "==" -> cmp(a, c, a == c);
                case "!=" -> cmp(a, c, a != c);
                case "and" -> logic(a, c, true);
                case "or" -> logic(a, c, false);
                default -> throw new ExpressionException("Unknown operator '" + b.op() + "'");
            };
        }
        return out;
    }

    private static double cmp(double a, double c, boolean result) {
        // NaN operands → NaN (so warm-up bars never satisfy a screener condition).
        if (Double.isNaN(a) || Double.isNaN(c)) return Double.NaN;
        return result ? 1.0 : 0.0;
    }

    private static double logic(double a, double c, boolean and) {
        if (Double.isNaN(a) || Double.isNaN(c)) return Double.NaN;
        boolean la = truthy(a), lc = truthy(c);
        return (and ? (la && lc) : (la || lc)) ? 1.0 : 0.0;
    }

    private static boolean truthy(double v) {
        return v > 0.5;
    }

    // ---- function dispatch -------------------------------------------------

    private static double[] call(Expr.Call c, EvalContext ctx) {
        String fn = c.name().toLowerCase();
        List<Expr> a = c.args();
        PriceSeries s = ctx.series();
        return switch (fn) {
            case "sma" -> Indicators.sma(series(a, 0, ctx), intArg(a, 1, ctx));
            case "ema" -> Indicators.ema(series(a, 0, ctx), intArg(a, 1, ctx));
            case "wma" -> Indicators.wma(series(a, 0, ctx), intArg(a, 1, ctx));
            case "rma" -> Indicators.rma(series(a, 0, ctx), intArg(a, 1, ctx));
            case "rsi" -> Indicators.rsi(series(a, 0, ctx), intArg(a, 1, ctx));
            case "stdev", "stddev" -> Indicators.stddev(series(a, 0, ctx), intArg(a, 1, ctx));
            case "highest" -> Indicators.highest(series(a, 0, ctx), intArg(a, 1, ctx));
            case "lowest" -> Indicators.lowest(series(a, 0, ctx), intArg(a, 1, ctx));
            case "change" -> Indicators.change(series(a, 0, ctx));
            case "abs" -> map1(series(a, 0, ctx), Math::abs);
            case "macd" -> Indicators.macd(series(a, 0, ctx), intArg(a, 1, ctx), intArg(a, 2, ctx), 9).macd();
            case "bb_upper" -> Indicators.bollinger(series(a, 0, ctx), intArg(a, 1, ctx), doubleArg(a, 2, ctx)).upper();
            case "bb_lower" -> Indicators.bollinger(series(a, 0, ctx), intArg(a, 1, ctx), doubleArg(a, 2, ctx)).lower();
            case "bb_middle" -> Indicators.bollinger(series(a, 0, ctx), intArg(a, 1, ctx), 2).middle();
            case "atr" -> Indicators.atr(s.high(), s.low(), s.close(), intArg(a, 0, ctx));
            case "adx" -> Indicators.adx(s.high(), s.low(), s.close(), intArg(a, 0, ctx));
            case "cci" -> Indicators.cci(s.high(), s.low(), s.close(), intArg(a, 0, ctx));
            case "vwap" -> Indicators.vwap(s.high(), s.low(), s.close(), s.volume(), intArg(a, 0, ctx));
            case "stoch_k" -> Indicators.stochastic(s.high(), s.low(), s.close(), intArg(a, 0, ctx), 3).k();
            case "stoch_d" -> Indicators.stochastic(s.high(), s.low(), s.close(), intArg(a, 0, ctx), intArg(a, 1, ctx)).d();
            case "obv" -> Indicators.obv(s.close(), s.volume());
            case "crossover" -> Indicators.crossOver(series(a, 0, ctx), series(a, 1, ctx));
            case "crossunder" -> Indicators.crossUnder(series(a, 0, ctx), series(a, 1, ctx));
            case "max" -> map2(series(a, 0, ctx), series(a, 1, ctx), Math::max);
            case "min" -> map2(series(a, 0, ctx), series(a, 1, ctx), Math::min);
            default -> throw new ExpressionException("Unknown function '" + c.name() + "'");
        };
    }

    private static double[] series(List<Expr> args, int idx, EvalContext ctx) {
        if (idx >= args.size()) {
            throw new ExpressionException("Missing argument " + (idx + 1));
        }
        return eval(args.get(idx), ctx);
    }

    private static int intArg(List<Expr> args, int idx, EvalContext ctx) {
        return (int) Math.round(doubleArg(args, idx, ctx));
    }

    private static double doubleArg(List<Expr> args, int idx, EvalContext ctx) {
        if (idx >= args.size()) {
            throw new ExpressionException("Missing numeric argument " + (idx + 1));
        }
        Expr e = args.get(idx);
        if (e instanceof Expr.Num n) {
            return n.value();
        }
        // Allow an expression that evaluates to a constant; take its first finite value.
        double[] v = eval(e, ctx);
        for (double x : v) {
            if (Double.isFinite(x)) return x;
        }
        throw new ExpressionException("Expected a constant numeric argument " + (idx + 1));
    }

    private interface D1 {
        double apply(double a);
    }

    private interface D2 {
        double apply(double a, double b);
    }

    private static double[] map1(double[] a, D1 f) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) out[i] = f.apply(a[i]);
        return out;
    }

    private static double[] map2(double[] a, double[] b, D2 f) {
        int n = Math.min(a.length, b.length);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = f.apply(a[i], b[i]);
        return out;
    }

    private static double[] fill(double v, int n) {
        double[] a = new double[n];
        java.util.Arrays.fill(a, v);
        return a;
    }
}
