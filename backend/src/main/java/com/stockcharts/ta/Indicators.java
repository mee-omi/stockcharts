package com.stockcharts.ta;

/**
 * Primitive {@code double[]} technical indicators. Every function returns an array aligned
 * to the input length, with {@link Double#NaN} for warm-up (undefined) positions. Wilder's
 * smoothing (RMA) is used for RSI / ATR / ADX to match conventional definitions.
 *
 * Stateless and allocation-light — one output array per call, no BigDecimal.
 */
public final class Indicators {

    private Indicators() {
    }

    public record Macd(double[] macd, double[] signal, double[] histogram) {
    }

    public record Bands(double[] upper, double[] middle, double[] lower) {
    }

    public record Stoch(double[] k, double[] d) {
    }

    // ---- helpers -----------------------------------------------------------

    private static double[] nan(int n) {
        double[] a = new double[n];
        java.util.Arrays.fill(a, Double.NaN);
        return a;
    }

    private static int firstFinite(double[] src) {
        for (int i = 0; i < src.length; i++) {
            if (Double.isFinite(src[i])) {
                return i;
            }
        }
        return -1;
    }

    private static double max3(double a, double b, double c) {
        return Math.max(a, Math.max(b, c));
    }

    public static double[] highest(double[] src, int p) {
        int n = src.length;
        double[] out = nan(n);
        for (int i = p - 1; i < n; i++) {
            double m = Double.NEGATIVE_INFINITY;
            boolean ok = true;
            for (int j = i - p + 1; j <= i; j++) {
                if (!Double.isFinite(src[j])) { ok = false; break; }
                m = Math.max(m, src[j]);
            }
            if (ok) out[i] = m;
        }
        return out;
    }

    public static double[] lowest(double[] src, int p) {
        int n = src.length;
        double[] out = nan(n);
        for (int i = p - 1; i < n; i++) {
            double m = Double.POSITIVE_INFINITY;
            boolean ok = true;
            for (int j = i - p + 1; j <= i; j++) {
                if (!Double.isFinite(src[j])) { ok = false; break; }
                m = Math.min(m, src[j]);
            }
            if (ok) out[i] = m;
        }
        return out;
    }

    // ---- moving averages ---------------------------------------------------

    public static double[] sma(double[] src, int p) {
        int n = src.length;
        double[] out = nan(n);
        if (p <= 0) return out;
        for (int i = p - 1; i < n; i++) {
            double s = 0;
            boolean ok = true;
            for (int j = i - p + 1; j <= i; j++) {
                if (!Double.isFinite(src[j])) { ok = false; break; }
                s += src[j];
            }
            if (ok) out[i] = s / p;
        }
        return out;
    }

    /** Exponential MA; tolerant of leading NaN so it composes (e.g. MACD signal line). */
    public static double[] ema(double[] src, int p) {
        int n = src.length;
        double[] out = nan(n);
        if (p <= 0) return out;
        int start = firstFinite(src);
        if (start < 0 || start + p > n) return out;
        double k = 2.0 / (p + 1);
        double sum = 0;
        for (int i = start; i < start + p; i++) sum += src[i];
        double prev = sum / p;
        out[start + p - 1] = prev;
        for (int i = start + p; i < n; i++) {
            double v = Double.isFinite(src[i]) ? src[i] : prev;
            prev = v * k + prev * (1 - k);
            out[i] = prev;
        }
        return out;
    }

    public static double[] wma(double[] src, int p) {
        int n = src.length;
        double[] out = nan(n);
        if (p <= 0) return out;
        double denom = p * (p + 1) / 2.0;
        for (int i = p - 1; i < n; i++) {
            double s = 0;
            boolean ok = true;
            for (int w = 0; w < p; w++) {
                double v = src[i - p + 1 + w];
                if (!Double.isFinite(v)) { ok = false; break; }
                s += v * (w + 1);
            }
            if (ok) out[i] = s / denom;
        }
        return out;
    }

    /** Wilder's smoothing (a.k.a. RMA / SMMA). */
    public static double[] rma(double[] src, int p) {
        int n = src.length;
        double[] out = nan(n);
        if (p <= 0) return out;
        int start = firstFinite(src);
        if (start < 0 || start + p > n) return out;
        double sum = 0;
        for (int i = start; i < start + p; i++) sum += src[i];
        double prev = sum / p;
        out[start + p - 1] = prev;
        for (int i = start + p; i < n; i++) {
            double v = Double.isFinite(src[i]) ? src[i] : 0.0;
            prev = (prev * (p - 1) + v) / p;
            out[i] = prev;
        }
        return out;
    }

    /** Population standard deviation over a rolling window (Bollinger convention). */
    public static double[] stddev(double[] src, int p) {
        int n = src.length;
        double[] out = nan(n);
        if (p <= 0) return out;
        for (int i = p - 1; i < n; i++) {
            double s = 0;
            boolean ok = true;
            for (int j = i - p + 1; j <= i; j++) {
                if (!Double.isFinite(src[j])) { ok = false; break; }
                s += src[j];
            }
            if (!ok) continue;
            double mean = s / p;
            double var = 0;
            for (int j = i - p + 1; j <= i; j++) {
                double d = src[j] - mean;
                var += d * d;
            }
            out[i] = Math.sqrt(var / p);
        }
        return out;
    }

    // ---- oscillators / volatility -----------------------------------------

    public static double[] rsi(double[] close, int p) {
        int n = close.length;
        double[] out = nan(n);
        if (n < p + 1 || p <= 0) return out;
        double[] gain = new double[n];
        double[] loss = new double[n];
        for (int i = 1; i < n; i++) {
            double ch = close[i] - close[i - 1];
            gain[i] = ch > 0 ? ch : 0;
            loss[i] = ch < 0 ? -ch : 0;
        }
        double ag = 0, al = 0;
        for (int i = 1; i <= p; i++) { ag += gain[i]; al += loss[i]; }
        ag /= p; al /= p;
        out[p] = al == 0 ? 100 : 100 - 100 / (1 + ag / al);
        for (int i = p + 1; i < n; i++) {
            ag = (ag * (p - 1) + gain[i]) / p;
            al = (al * (p - 1) + loss[i]) / p;
            out[i] = al == 0 ? 100 : 100 - 100 / (1 + ag / al);
        }
        return out;
    }

    public static Macd macd(double[] close, int fast, int slow, int signal) {
        double[] ef = ema(close, fast);
        double[] es = ema(close, slow);
        int n = close.length;
        double[] line = nan(n);
        for (int i = 0; i < n; i++) {
            if (Double.isFinite(ef[i]) && Double.isFinite(es[i])) line[i] = ef[i] - es[i];
        }
        double[] sig = ema(line, signal);
        double[] hist = nan(n);
        for (int i = 0; i < n; i++) {
            if (Double.isFinite(line[i]) && Double.isFinite(sig[i])) hist[i] = line[i] - sig[i];
        }
        return new Macd(line, sig, hist);
    }

    public static Bands bollinger(double[] close, int p, double k) {
        double[] mid = sma(close, p);
        double[] sd = stddev(close, p);
        int n = close.length;
        double[] up = nan(n);
        double[] lo = nan(n);
        for (int i = 0; i < n; i++) {
            if (Double.isFinite(mid[i]) && Double.isFinite(sd[i])) {
                up[i] = mid[i] + k * sd[i];
                lo[i] = mid[i] - k * sd[i];
            }
        }
        return new Bands(up, mid, lo);
    }

    public static double[] trueRange(double[] high, double[] low, double[] close) {
        int n = close.length;
        double[] tr = new double[n];
        if (n == 0) return tr;
        tr[0] = high[0] - low[0];
        for (int i = 1; i < n; i++) {
            tr[i] = max3(high[i] - low[i],
                    Math.abs(high[i] - close[i - 1]),
                    Math.abs(low[i] - close[i - 1]));
        }
        return tr;
    }

    public static double[] atr(double[] high, double[] low, double[] close, int p) {
        return rma(trueRange(high, low, close), p);
    }

    public static Stoch stochastic(double[] high, double[] low, double[] close,
                                   int kPeriod, int dPeriod) {
        int n = close.length;
        double[] hh = highest(high, kPeriod);
        double[] ll = lowest(low, kPeriod);
        double[] k = nan(n);
        for (int i = 0; i < n; i++) {
            if (Double.isFinite(hh[i]) && Double.isFinite(ll[i])) {
                double denom = hh[i] - ll[i];
                k[i] = denom == 0 ? 0 : 100 * (close[i] - ll[i]) / denom;
            }
        }
        double[] d = sma(k, dPeriod);
        return new Stoch(k, d);
    }

    public static double[] adx(double[] high, double[] low, double[] close, int p) {
        int n = close.length;
        double[] plusDM = new double[n];
        double[] minusDM = new double[n];
        double[] tr = trueRange(high, low, close);
        for (int i = 1; i < n; i++) {
            double up = high[i] - high[i - 1];
            double down = low[i - 1] - low[i];
            plusDM[i] = (up > down && up > 0) ? up : 0;
            minusDM[i] = (down > up && down > 0) ? down : 0;
        }
        double[] trN = rma(tr, p);
        double[] plusN = rma(plusDM, p);
        double[] minusN = rma(minusDM, p);
        double[] dx = nan(n);
        for (int i = 0; i < n; i++) {
            if (Double.isFinite(trN[i]) && trN[i] != 0
                    && Double.isFinite(plusN[i]) && Double.isFinite(minusN[i])) {
                double plusDI = 100 * plusN[i] / trN[i];
                double minusDI = 100 * minusN[i] / trN[i];
                double sum = plusDI + minusDI;
                dx[i] = sum == 0 ? 0 : 100 * Math.abs(plusDI - minusDI) / sum;
            }
        }
        return rma(dx, p);
    }

    public static double[] obv(double[] close, double[] volume) {
        int n = close.length;
        double[] out = new double[n];
        if (n == 0) return out;
        out[0] = 0;
        for (int i = 1; i < n; i++) {
            if (close[i] > close[i - 1]) out[i] = out[i - 1] + volume[i];
            else if (close[i] < close[i - 1]) out[i] = out[i - 1] - volume[i];
            else out[i] = out[i - 1];
        }
        return out;
    }

    /** Rolling VWAP over a window (typical price weighted by volume). */
    public static double[] vwap(double[] high, double[] low, double[] close, double[] volume, int p) {
        int n = close.length;
        double[] out = nan(n);
        for (int i = p - 1; i < n; i++) {
            double pv = 0, v = 0;
            for (int j = i - p + 1; j <= i; j++) {
                double tp = (high[j] + low[j] + close[j]) / 3.0;
                pv += tp * volume[j];
                v += volume[j];
            }
            if (v != 0) out[i] = pv / v;
        }
        return out;
    }

    public static double[] cci(double[] high, double[] low, double[] close, int p) {
        int n = close.length;
        double[] tp = new double[n];
        for (int i = 0; i < n; i++) tp[i] = (high[i] + low[i] + close[i]) / 3.0;
        double[] smatp = sma(tp, p);
        double[] out = nan(n);
        for (int i = p - 1; i < n; i++) {
            if (!Double.isFinite(smatp[i])) continue;
            double md = 0;
            for (int j = i - p + 1; j <= i; j++) md += Math.abs(tp[j] - smatp[i]);
            md /= p;
            out[i] = md == 0 ? 0 : (tp[i] - smatp[i]) / (0.015 * md);
        }
        return out;
    }

    // ---- helpers for the expression engine --------------------------------

    public static double[] change(double[] src) {
        int n = src.length;
        double[] out = nan(n);
        for (int i = 1; i < n; i++) {
            if (Double.isFinite(src[i]) && Double.isFinite(src[i - 1])) out[i] = src[i] - src[i - 1];
        }
        return out;
    }

    /** 1.0 where a crosses above b, else 0.0. */
    public static double[] crossOver(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        double[] out = new double[n];
        for (int i = 1; i < n; i++) {
            if (Double.isFinite(a[i]) && Double.isFinite(b[i])
                    && Double.isFinite(a[i - 1]) && Double.isFinite(b[i - 1])
                    && a[i] > b[i] && a[i - 1] <= b[i - 1]) {
                out[i] = 1.0;
            }
        }
        return out;
    }

    /** 1.0 where a crosses below b, else 0.0. */
    public static double[] crossUnder(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        double[] out = new double[n];
        for (int i = 1; i < n; i++) {
            if (Double.isFinite(a[i]) && Double.isFinite(b[i])
                    && Double.isFinite(a[i - 1]) && Double.isFinite(b[i - 1])
                    && a[i] < b[i] && a[i - 1] >= b[i - 1]) {
                out[i] = 1.0;
            }
        }
        return out;
    }
}
