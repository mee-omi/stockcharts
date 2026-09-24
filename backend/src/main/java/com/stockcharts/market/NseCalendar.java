package com.stockcharts.market;

import com.stockcharts.candle.Timeframe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * NSE equity session calendar (IST): 09:15–15:30 on weekdays that are not exchange holidays.
 * Bars follow the broker convention: intraday bars are anchored at the 09:15 open (so 1h bars
 * start 09:15, 10:15 … 15:15, the last one cut short at 15:30) and daily bars at IST midnight.
 * Holidays come from {@code NSE_HOLIDAYS} (YYYY-MM-DD, comma-separated), the same setting the data
 * service uses.
 */
@Component
public class NseCalendar {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    public static final LocalTime OPEN = LocalTime.of(9, 15);
    public static final LocalTime CLOSE = LocalTime.of(15, 30);
    /** Search bound when walking to the next/previous trading day (covers any holiday cluster). */
    private static final int MAX_DAYS = 30;

    private final Set<LocalDate> holidays;

    public NseCalendar(@Value("${stockcharts.nse-holidays:}") String holidays) {
        this.holidays = Arrays.stream(holidays == null ? new String[0] : holidays.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(LocalDate::parse)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isTradingDay(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY
                && !holidays.contains(d);
    }

    public boolean isOpen(Instant t) {
        ZonedDateTime z = t.atZone(IST);
        LocalTime time = z.toLocalTime();
        return isTradingDay(z.toLocalDate()) && !time.isBefore(OPEN) && time.isBefore(CLOSE);
    }

    public static Instant sessionOpen(LocalDate d) {
        return d.atTime(OPEN).atZone(IST).toInstant();
    }

    public static Instant sessionClose(LocalDate d) {
        return d.atTime(CLOSE).atZone(IST).toInstant();
    }

    /**
     * Start of the bar containing {@code t}: IST midnight for daily bars; for intraday bars the
     * session-anchored slot (times before the open fall back to the plain epoch grid).
     */
    public static Instant barStart(Timeframe tf, Instant t) {
        LocalDate d = t.atZone(IST).toLocalDate();
        if (tf == Timeframe.ONE_DAY) {
            return d.atStartOfDay(IST).toInstant();
        }
        long step = tf.getMillis();
        long open = sessionOpen(d).toEpochMilli();
        long ms = t.toEpochMilli();
        if (ms < open) {
            return Instant.ofEpochMilli(ms - Math.floorMod(ms, step));
        }
        return Instant.ofEpochMilli(open + (ms - open) / step * step);
    }

    /** The newest bar that should exist at {@code now}: the forming bar, or the last bar of the last session. */
    public Instant latestExpectedBarStart(Timeframe tf, Instant now) {
        LocalDate d = now.atZone(IST).toLocalDate();
        for (int i = 0; i < MAX_DAYS; i++, d = d.minusDays(1)) {
            if (!isTradingDay(d) || now.isBefore(sessionOpen(d))) {
                continue;
            }
            Instant close = sessionClose(d);
            return barStart(tf, now.isBefore(close) ? now : close.minusMillis(1));
        }
        return barStart(tf, now);
    }

    /** The next session open at or after {@code t} (t itself is not checked for being in session). */
    public Instant nextOpen(Instant t) {
        LocalDate d = t.atZone(IST).toLocalDate();
        for (int i = 0; i < MAX_DAYS; i++, d = d.plusDays(1)) {
            if (isTradingDay(d) && !sessionOpen(d).isBefore(t)) {
                return sessionOpen(d);
            }
        }
        return t;
    }

    /** The oldest bar that should exist at or after {@code from}. */
    public Instant firstExpectedBarStart(Timeframe tf, Instant from) {
        LocalDate d = from.atZone(IST).toLocalDate();
        for (int i = 0; i < MAX_DAYS; i++, d = d.plusDays(1)) {
            if (!isTradingDay(d)) {
                continue;
            }
            if (tf == Timeframe.ONE_DAY) {
                Instant midnight = d.atStartOfDay(IST).toInstant();
                if (!midnight.isBefore(from)) {
                    return midnight;
                }
                continue;
            }
            Instant open = sessionOpen(d);
            Instant close = sessionClose(d);
            if (!from.isAfter(open)) {
                return open;
            }
            if (from.isBefore(close)) {
                Instant start = barStart(tf, from);
                return start.equals(from) ? start : start.plusMillis(tf.getMillis());
            }
        }
        return from;
    }
}
