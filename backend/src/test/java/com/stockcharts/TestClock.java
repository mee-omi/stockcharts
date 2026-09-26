package com.stockcharts;

import com.stockcharts.market.NseCalendar;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** A clock tests move by hand; register it with {@code @Import(TestClock.Config.class)}. */
public class TestClock extends Clock {

    private volatile Instant now = Instant.now();

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }

    public void setIst(String dateTime) {
        now = LocalDateTime.parse(dateTime).atZone(NseCalendar.IST).toInstant();
    }

    @org.springframework.boot.test.context.TestConfiguration
    public static class Config {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        public TestClock testClock() {
            return new TestClock();
        }
    }
}
