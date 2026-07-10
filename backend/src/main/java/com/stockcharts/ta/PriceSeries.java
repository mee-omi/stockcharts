package com.stockcharts.ta;

import com.stockcharts.candle.Candle;

import java.util.List;

/**
 * Column-oriented OHLCV data backed by primitive arrays. This is the input to the
 * indicator library and the expression engine — using {@code double[]} (not BigDecimal)
 * keeps indicator math fast and allocation-light.
 *
 * Arrays are index-aligned; {@code time[i]} is epoch seconds (UTC) of bar {@code i}.
 */
public final class PriceSeries {

    private final long[] time;
    private final double[] open;
    private final double[] high;
    private final double[] low;
    private final double[] close;
    private final double[] volume;

    public PriceSeries(long[] time, double[] open, double[] high,
                       double[] low, double[] close, double[] volume) {
        this.time = time;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    public static PriceSeries of(List<Candle> candles) {
        int n = candles.size();
        long[] time = new long[n];
        double[] open = new double[n];
        double[] high = new double[n];
        double[] low = new double[n];
        double[] close = new double[n];
        double[] volume = new double[n];
        for (int i = 0; i < n; i++) {
            Candle c = candles.get(i);
            time[i] = c.getOpenTime().getEpochSecond();
            open[i] = c.getOpen();
            high[i] = c.getHigh();
            low[i] = c.getLow();
            close[i] = c.getClose();
            volume[i] = c.getVolume();
        }
        return new PriceSeries(time, open, high, low, close, volume);
    }

    public int size() {
        return close.length;
    }

    public long[] time() {
        return time;
    }

    public double[] open() {
        return open;
    }

    public double[] high() {
        return high;
    }

    public double[] low() {
        return low;
    }

    public double[] close() {
        return close;
    }

    public double[] volume() {
        return volume;
    }

    /** (high + low) / 2 */
    public double[] hl2() {
        double[] r = new double[size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = (high[i] + low[i]) / 2.0;
        }
        return r;
    }

    /** (high + low + close) / 3 — also the "typical price". */
    public double[] hlc3() {
        double[] r = new double[size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = (high[i] + low[i] + close[i]) / 3.0;
        }
        return r;
    }

    /** (open + high + low + close) / 4 */
    public double[] ohlc4() {
        double[] r = new double[size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = (open[i] + high[i] + low[i] + close[i]) / 4.0;
        }
        return r;
    }

    public double[] typical() {
        return hlc3();
    }
}
