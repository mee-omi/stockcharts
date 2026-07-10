package com.stockcharts.indicator;

import java.util.Arrays;
import java.util.List;

/**
 * Catalog of supported indicators with their parameters and output lines.
 * {@code overlay} = drawn on the price chart; otherwise rendered in a sub-pane.
 */
public enum IndicatorType {

    SMA("Simple Moving Average", true,
            List.of(new ParamDef("period", 20, 1, 500)), List.of("value")),
    EMA("Exponential Moving Average", true,
            List.of(new ParamDef("period", 20, 1, 500)), List.of("value")),
    WMA("Weighted Moving Average", true,
            List.of(new ParamDef("period", 20, 1, 500)), List.of("value")),
    RSI("Relative Strength Index", false,
            List.of(new ParamDef("period", 14, 2, 200)), List.of("value")),
    MACD("MACD", false,
            List.of(new ParamDef("fast", 12, 1, 200),
                    new ParamDef("slow", 26, 1, 400),
                    new ParamDef("signal", 9, 1, 200)),
            List.of("macd", "signal", "histogram")),
    BBANDS("Bollinger Bands", true,
            List.of(new ParamDef("period", 20, 1, 500),
                    new ParamDef("k", 2, 1, 5)),
            List.of("upper", "middle", "lower")),
    STOCH("Stochastic Oscillator", false,
            List.of(new ParamDef("period", 14, 1, 200),
                    new ParamDef("dPeriod", 3, 1, 50)),
            List.of("k", "d")),
    ATR("Average True Range", false,
            List.of(new ParamDef("period", 14, 1, 200)), List.of("value")),
    ADX("Average Directional Index", false,
            List.of(new ParamDef("period", 14, 1, 200)), List.of("value")),
    OBV("On Balance Volume", false,
            List.of(), List.of("value")),
    VWAP("Volume Weighted Average Price", true,
            List.of(new ParamDef("period", 14, 1, 500)), List.of("value")),
    CCI("Commodity Channel Index", false,
            List.of(new ParamDef("period", 20, 1, 200)), List.of("value"));

    /** A tunable parameter with a default and inclusive bounds. */
    public record ParamDef(String name, double defaultValue, double min, double max) {
    }

    private final String label;
    private final boolean overlay;
    private final List<ParamDef> params;
    private final List<String> outputs;

    IndicatorType(String label, boolean overlay, List<ParamDef> params, List<String> outputs) {
        this.label = label;
        this.overlay = overlay;
        this.params = params;
        this.outputs = outputs;
    }

    public String getCode() {
        return name();
    }

    public String getLabel() {
        return label;
    }

    public boolean isOverlay() {
        return overlay;
    }

    public List<ParamDef> getParams() {
        return params;
    }

    public List<String> getOutputs() {
        return outputs;
    }

    public static IndicatorType fromCode(String code) {
        IndicatorType t = fromCodeOrNull(code);
        if (t == null) {
            throw new IllegalArgumentException("Unknown indicator: " + code);
        }
        return t;
    }

    /** Returns the matching built-in type, or {@code null} if the code is not a built-in. */
    public static IndicatorType fromCodeOrNull(String code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(t -> t.name().equalsIgnoreCase(code))
                .findFirst()
                .orElse(null);
    }
}
