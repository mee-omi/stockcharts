package com.stockcharts.scanschedule;

import com.stockcharts.TestClock;
import com.stockcharts.alert.AlertNotifier;
import com.stockcharts.backtest.BacktestDtos;
import com.stockcharts.backtest.BacktestDtos.ScanRequest;
import com.stockcharts.backtest.BacktestDtos.ScanResult;
import com.stockcharts.backtest.BacktestDtos.ScanRow;
import com.stockcharts.backtest.BacktestService;
import com.stockcharts.marketdata.MarketDataClient;
import com.stockcharts.trading.TradingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClock.Config.class)
class ScanScheduleIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestClock clock;
    @Autowired
    ScanScheduleService service;
    @Autowired
    ScanScheduleRepository schedules;
    @Autowired
    ScanRunRepository runs;

    @MockBean
    BacktestService backtest;
    @MockBean
    AlertNotifier notifier;
    @MockBean
    MarketDataClient marketData;
    @MockBean
    TradingClient trading;

    private static final String SCAN = """
            {"universe":{"type":"index","value":"NIFTY50"},
             "strategy":{"timeframe":"1d","entry":"crossover(ema(close,10), ema(close,30))",
                         "exit":"crossunder(ema(close,10), ema(close,30))","from":1700000000000,"to":1760000000000},
             "sortBy":"PROFIT_FACTOR","onlySignals":true}
            """;

    @BeforeEach
    void setUp() {
        runs.deleteAll();
        schedules.deleteAll();
        clock.setIst("2026-09-24T16:00"); // Thursday after the close
    }

    private static ScanRow row(String symbol, BacktestDtos.SignalState signal) {
        var m = new BacktestDtos.Metrics(100_000, 125_000, 25_000, 25, 5, 8, 5, 3, 62.5, 3, 7, -2, 1.8, 7, 1.1,
                30, 6, 50, 750);
        return new ScanRow(symbol, symbol + " Ltd", new BacktestDtos.Latest(1_790_000_000, 1234.5, signal, null,
                null, null), m);
    }

    private static ScanResult result(ScanRow... rows) {
        return new ScanResult("1d", 50, 50, rows.length, 0, false, 1_790_000_000L, 1200, List.of(rows), List.of());
    }

    private long create(String extra) throws Exception {
        String body = mvc.perform(post("/api/v1/scan-schedules").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"EMA cross\",\"scan\":" + SCAN + extra + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    @Test
    void validatesTheTimetable() throws Exception {
        mvc.perform(post("/api/v1/scan-schedules").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"scan\":" + SCAN + ",\"times\":\"15:35\",\"intervalMinutes\":15}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("either times")));
        mvc.perform(post("/api/v1/scan-schedules").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"scan\":" + SCAN + ",\"times\":\"25:61\"}"))
                .andExpect(status().isBadRequest());
        create(",\"times\":\"15:35\"");
        mvc.perform(post("/api/v1/scan-schedules").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ema CROSS\",\"scan\":" + SCAN + ",\"times\":\"9:30\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("already exists")));
    }

    @Test
    void nextRunSkipsWeekendsAndHolidaysAndFollowsTheSession() throws Exception {
        long id = create(",\"times\":\"09:30, 15:35\"");
        mvc.perform(get("/api/v1/scan-schedules/" + id))
                .andExpect(jsonPath("$.times").value("09:30,15:35"))
                .andExpect(jsonPath("$.description").value("At 09:30, 15:35 IST on trading days"))
                .andExpect(jsonPath("$.nextRunAt").value(ist("2026-09-25T09:30").toEpochMilli()));

        ScanSchedule s = schedules.findById(id).orElseThrow();
        assertEquals(ist("2026-09-28T09:30"), service.nextRun(s, ist("2026-09-25T15:35"))); // Fri → Mon
        s.intervalMinutes = 30;
        s.runTimes = null;
        assertEquals(ist("2026-09-24T09:45"), service.nextRun(s, ist("2026-09-24T08:00")));
        assertEquals(ist("2026-09-24T15:15"), service.nextRun(s, ist("2026-09-24T14:50")));
        assertEquals(ist("2026-09-25T09:45"), service.nextRun(s, ist("2026-09-24T15:15")));
    }

    @Test
    void dueSchedulesRunNotifyAndReportOnlyNewMatches() throws Exception {
        long id = create(",\"times\":\"15:35\",\"onlyNew\":true,\"topN\":5");
        when(backtest.scan(any())).thenReturn(result(row("TCS", BacktestDtos.SignalState.ENTRY),
                row("INFY", BacktestDtos.SignalState.ENTRY)));

        clock.setIst("2026-09-25T15:35");
        service.runDue();
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(notifier).deliver(eq(ScanScheduleService.TOPIC), any(), text.capture(), any());
        assertTrue(text.getValue().startsWith("📊 Scan: EMA cross — 2 matches (2 new)"), text.getValue());
        assertTrue(text.getValue().contains("1. TCS ENTRY @ 1234.50 · +25.0% · win 63% · 8 trades 🆕"), text.getValue());
        // The saved fixed range became a rolling window ending now.
        ArgumentCaptor<ScanRequest> scan = ArgumentCaptor.forClass(ScanRequest.class);
        verify(backtest).scan(scan.capture());
        assertEquals(null, scan.getValue().strategy().to());
        assertEquals(clock.instant().toEpochMilli() - (1760000000000L - 1700000000000L),
                scan.getValue().strategy().from());
        ScanSchedule s = schedules.findById(id).orElseThrow();
        assertEquals(ist("2026-09-28T15:35"), s.nextRunAt);

        // Same matches next day: nothing new, so no notification.
        clearInvocations(notifier);
        clock.setIst("2026-09-28T15:35");
        service.runDue();
        verify(notifier, never()).deliver(anyString(), any(), anyString(), any());

        // One new symbol: only it is reported.
        when(backtest.scan(any())).thenReturn(result(row("TCS", BacktestDtos.SignalState.ENTRY),
                row("SBIN", BacktestDtos.SignalState.EXIT)));
        clock.setIst("2026-09-29T15:35");
        service.runDue();
        verify(notifier).deliver(eq(ScanScheduleService.TOPIC), any(), text.capture(), any());
        assertTrue(text.getValue().contains("(1 new)") && text.getValue().contains("1. SBIN EXIT"), text.getValue());
        assertTrue(!text.getValue().contains("TCS"), text.getValue());

        mvc.perform(get("/api/v1/scan-schedules/" + id + "/runs"))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].newMatches").value(1))
                .andExpect(jsonPath("$[0].rows[1].symbol").value("SBIN"))
                .andExpect(jsonPath("$[0].rows[1].isNew").value(true));
    }

    @Test
    void failuresAreRecordedAndReported() throws Exception {
        long id = create(",\"times\":\"15:35\"");
        when(backtest.scan(any())).thenThrow(new IllegalStateException("data service down"));
        mvc.perform(post("/api/v1/scan-schedules/" + id + "/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.message").value("data service down"));
        verify(notifier).deliver(eq(ScanScheduleService.TOPIC), any(), eq("📊 Scan 'EMA cross' failed: data service down"), any());
        // A manual run doesn't move the timetable.
        mvc.perform(get("/api/v1/scan-schedules/" + id))
                .andExpect(jsonPath("$.nextRunAt").value(ist("2026-09-25T15:35").toEpochMilli()))
                .andExpect(jsonPath("$.lastRun.status").value("FAILED"));
    }

    @Test
    void schedulesCanBePausedEditedAndDeleted() throws Exception {
        long id = create(",\"intervalMinutes\":15");
        mvc.perform(put("/api/v1/scan-schedules/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.nextRunAt").doesNotExist())
                .andExpect(jsonPath("$.intervalMinutes").value(15));
        mvc.perform(put("/api/v1/scan-schedules/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"times\":\"15:40\",\"notify\":false}"))
                .andExpect(jsonPath("$.times").value("15:40"))
                .andExpect(jsonPath("$.intervalMinutes").doesNotExist())
                .andExpect(jsonPath("$.notify").value(false));
        mvc.perform(delete("/api/v1/scan-schedules/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/scan-schedules")).andExpect(jsonPath("$", hasSize(0)));
    }

    private static Instant ist(String t) {
        return LocalDateTime.parse(t).atZone(com.stockcharts.market.NseCalendar.IST).toInstant();
    }
}
