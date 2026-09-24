package com.stockcharts.market;

import com.stockcharts.candle.Timeframe;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NseCalendarTest {

    // 2026-10-02 (Fri) is Gandhi Jayanti; 2026-09-24 is a Thursday.
    private final NseCalendar cal = new NseCalendar("2026-10-02, ");

    private static Instant ist(String dateTime) {
        return LocalDateTime.parse(dateTime).atZone(NseCalendar.IST).toInstant();
    }

    @Test
    void sessionHoursWeekendsAndHolidays() {
        assertTrue(cal.isOpen(ist("2026-09-24T09:15")));
        assertTrue(cal.isOpen(ist("2026-09-24T15:29")));
        assertFalse(cal.isOpen(ist("2026-09-24T15:30")));
        assertFalse(cal.isOpen(ist("2026-09-24T09:14")));
        assertFalse(cal.isOpen(ist("2026-09-26T11:00"))); // Saturday
        assertFalse(cal.isOpen(ist("2026-10-02T11:00"))); // holiday
        assertFalse(cal.isTradingDay(LocalDate.parse("2026-10-02")));
    }

    @Test
    void barsAreAnchoredToTheSessionOpenAndIstMidnight() {
        assertEquals(ist("2026-09-24T10:15"), NseCalendar.barStart(Timeframe.ONE_HOUR, ist("2026-09-24T11:14")));
        assertEquals(ist("2026-09-24T15:15"), NseCalendar.barStart(Timeframe.ONE_HOUR, ist("2026-09-24T15:29")));
        assertEquals(ist("2026-09-24T09:45"), NseCalendar.barStart(Timeframe.THIRTY_MINUTE, ist("2026-09-24T10:00")));
        assertEquals(ist("2026-09-24T10:00"), NseCalendar.barStart(Timeframe.FIVE_MINUTE, ist("2026-09-24T10:04")));
        // Daily bars start at IST midnight, i.e. 18:30 UTC the day before.
        assertEquals(Instant.parse("2026-09-23T18:30:00Z"), NseCalendar.barStart(Timeframe.ONE_DAY, ist("2026-09-24T14:00")));
    }

    @Test
    void latestExpectedBarSkipsNightsWeekendsAndHolidays() {
        // During the session: the forming bar.
        assertEquals(ist("2026-09-24T11:03"), cal.latestExpectedBarStart(Timeframe.ONE_MINUTE, ist("2026-09-24T11:03")));
        // Evening: the session's last bar.
        assertEquals(ist("2026-09-24T15:29"), cal.latestExpectedBarStart(Timeframe.ONE_MINUTE, ist("2026-09-24T21:00")));
        assertEquals(ist("2026-09-24T15:15"), cal.latestExpectedBarStart(Timeframe.ONE_HOUR, ist("2026-09-24T21:00")));
        // Before the open on Monday: Friday's close.
        assertEquals(ist("2026-09-25T15:29"), cal.latestExpectedBarStart(Timeframe.ONE_MINUTE, ist("2026-09-28T08:00")));
        // Saturday after a holiday Friday: Thursday's close; daily bar of Thursday.
        assertEquals(ist("2026-10-01T15:25"), cal.latestExpectedBarStart(Timeframe.FIVE_MINUTE, ist("2026-10-03T12:00")));
        assertEquals(ist("2026-10-01T00:00"), cal.latestExpectedBarStart(Timeframe.ONE_DAY, ist("2026-10-03T12:00")));
    }

    @Test
    void firstExpectedBarSkipsToTheNextSession() {
        assertEquals(ist("2026-09-28T09:15"), cal.firstExpectedBarStart(Timeframe.ONE_MINUTE, ist("2026-09-26T10:00")));
        assertEquals(ist("2026-09-25T09:15"), cal.firstExpectedBarStart(Timeframe.ONE_HOUR, ist("2026-09-24T16:00")));
        assertEquals(ist("2026-09-24T11:15"), cal.firstExpectedBarStart(Timeframe.ONE_HOUR, ist("2026-09-24T10:20")));
        assertEquals(ist("2026-09-24T10:15"), cal.firstExpectedBarStart(Timeframe.ONE_HOUR, ist("2026-09-24T10:15")));
        assertEquals(ist("2026-10-05T00:00"), cal.firstExpectedBarStart(Timeframe.ONE_DAY, ist("2026-10-01T12:00")));
    }

    @Test
    void nextOpenSkipsClosedDays() {
        assertEquals(ist("2026-09-25T09:15"), cal.nextOpen(ist("2026-09-24T15:30")));
        assertEquals(ist("2026-09-24T09:15"), cal.nextOpen(ist("2026-09-24T07:00")));
        assertEquals(ist("2026-10-05T09:15"), cal.nextOpen(ist("2026-10-01T16:00")));
    }
}
