package com.stockcharts.candle;

import java.time.Duration;
import java.util.Arrays;

/**
 * Chart timeframes. {@code code} is the public API value; {@code interval} is the
 * name understood by the Python data service.
 */
public enum Timeframe {
    ONE_MINUTE("1m", "minute", Duration.ofMinutes(1)),
    FIVE_MINUTE("5m", "5minute", Duration.ofMinutes(5)),
    FIFTEEN_MINUTE("15m", "15minute", Duration.ofMinutes(15)),
    THIRTY_MINUTE("30m", "30minute", Duration.ofMinutes(30)),
    ONE_HOUR("1h", "60minute", Duration.ofHours(1)),
    ONE_DAY("1d", "day", Duration.ofDays(1));

    private final String code;
    private final String interval;
    private final Duration duration;

    Timeframe(String code, String interval, Duration duration) {
        this.code = code;
        this.interval = interval;
        this.duration = duration;
    }

    public String getCode() {
        return code;
    }

    public String getInterval() {
        return interval;
    }

    public Duration getDuration() {
        return duration;
    }

    public long getMillis() {
        return duration.toMillis();
    }

    public static Timeframe fromCode(String code) {
        return Arrays.stream(values())
                .filter(t -> t.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown timeframe '" + code + "'. Valid: 1m,5m,15m,30m,1h,1d"));
    }
}
