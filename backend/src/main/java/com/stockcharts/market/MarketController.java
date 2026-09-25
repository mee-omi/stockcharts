package com.stockcharts.market;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/market")
@Tag(name = "Market", description = "NSE session status (IST)")
public class MarketController {

    private final NseCalendar calendar;

    public MarketController(NseCalendar calendar) {
        this.calendar = calendar;
    }

    /**
     * @param open       whether the equity session is open now
     * @param nextChange epoch ms of today's close when open, else of the next open
     */
    public record Status(boolean open, long now, long nextChange, String timezone) {
    }

    @GetMapping("/status")
    @Operation(summary = "Is the NSE session open, and when does that change")
    public Status status() {
        Instant now = Instant.now();
        boolean open = calendar.isOpen(now);
        Instant change = open ? NseCalendar.sessionClose(now.atZone(NseCalendar.IST).toLocalDate())
                : calendar.nextOpen(now);
        return new Status(open, now.toEpochMilli(), change.toEpochMilli(), NseCalendar.IST.getId());
    }
}
