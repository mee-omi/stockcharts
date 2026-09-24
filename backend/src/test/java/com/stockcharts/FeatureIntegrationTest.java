package com.stockcharts;

import com.stockcharts.alert.AlertEventRepository;
import com.stockcharts.alert.AlertRepository;
import com.stockcharts.candle.CandleRepository;
import com.stockcharts.instrument.Instrument;
import com.stockcharts.instrument.InstrumentRepository;
import com.stockcharts.journal.JournalRepository;
import com.stockcharts.live.LiveDataService;
import com.stockcharts.marketdata.MarketDataClient;
import com.stockcharts.marketdata.dto.CandleDto;
import com.stockcharts.trading.TradingClient;
import com.stockcharts.trading.dto.OrderDto;
import com.stockcharts.trading.dto.TradingBrokerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage for alerts, backtests, quotes, UI state, the trade journal / portfolio
 * report, index universes and observability endpoints, on H2 with a mocked data service.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class FeatureIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private InstrumentRepository instruments;

    @Autowired
    private CandleRepository candles;

    @Autowired
    private AlertRepository alerts;

    @Autowired
    private AlertEventRepository alertEvents;

    @Autowired
    private JournalRepository journal;

    @Autowired
    private LiveDataService live;

    @MockBean
    private MarketDataClient marketData;

    @MockBean
    private TradingClient tradingClient;

    private static final int N = 200;

    @BeforeEach
    void setup() {
        candles.deleteAll();
        alerts.deleteAll();
        alertEvents.deleteAll();
        journal.deleteAll();
        instruments.save(new Instrument("RELIANCE", "Reliance Industries Ltd", 738561, "NSE"));
        instruments.save(new Instrument("TCS", "Tata Consultancy Services Ltd", 2953217, "NSE"));
        instruments.save(new Instrument("NIFTY50", "NIFTY 50", 256265, "NSE", Instrument.TYPE_INDEX));

        long now = System.currentTimeMillis();
        List<CandleDto> series = new ArrayList<>();
        double price = 2900;
        for (int i = 0; i < N; i++) {
            long ts = now - (long) (N - i) * 60_000L;
            double open = price;
            double close = open + Math.sin(i / 7.0) * 6;
            series.add(new CandleDto(ts, open, Math.max(open, close) + 2, Math.min(open, close) - 2, close, 1000 + i));
            price = close;
        }
        when(marketData.getHistorical(anyString(), anyString(), anyLong(), anyLong())).thenReturn(series);
        when(tradingClient.getBroker()).thenReturn(new TradingBrokerInfo("mock", List.of("mock")));
    }

    // ---- alerts ----

    @Test
    void alertLifecycleFiresOnceAndRecordsHistory() throws Exception {
        mvc.perform(post("/api/v1/alerts").contentType(MediaType.APPLICATION_JSON).content("""
                        {"symbol":"reliance","timeframe":"1m","expression":"close > 0","triggerMode":"ONCE"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("RELIANCE"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.name").value(containsString("close > 0")));

        mvc.perform(post("/api/v1/alerts/evaluate")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].symbol").value("RELIANCE"));

        // ONCE: second evaluation does not fire again.
        mvc.perform(post("/api/v1/alerts/evaluate")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mvc.perform(get("/api/v1/alerts")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("TRIGGERED"))
                .andExpect(jsonPath("$[0].triggerCount").value(1));
        mvc.perform(get("/api/v1/alerts/events")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        long id = alerts.findAll().get(0).getId();
        mvc.perform(post("/api/v1/alerts/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(delete("/api/v1/alerts/" + id)).andExpect(status().isNoContent());
    }

    @Test
    void everyBarAlertFiresOncePerBar() throws Exception {
        mvc.perform(post("/api/v1/alerts").contentType(MediaType.APPLICATION_JSON).content("""
                        {"symbol":"TCS","timeframe":"1m","expression":"close > 0","triggerMode":"EVERY_BAR"}
                        """))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/alerts/evaluate")).andExpect(jsonPath("$", hasSize(1)));
        // Same latest bar → no repeat.
        mvc.perform(post("/api/v1/alerts/evaluate")).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/alerts")).andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void alertValidationRejectsBadInput() throws Exception {
        mvc.perform(post("/api/v1/alerts").contentType(MediaType.APPLICATION_JSON).content("""
                        {"symbol":"RELIANCE","timeframe":"1m","expression":"nope(close) > 1"}
                        """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/alerts").contentType(MediaType.APPLICATION_JSON).content("""
                        {"symbol":"NOSUCH","timeframe":"1m","expression":"close > 1"}
                        """))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/alerts").contentType(MediaType.APPLICATION_JSON).content("""
                        {"symbol":"RELIANCE","timeframe":"7m","expression":"close > 1"}
                        """))
                .andExpect(status().isBadRequest());
    }

    // ---- backtest ----

    @Test
    void backtestReturnsTradesMetricsAndEquity() throws Exception {
        mvc.perform(post("/api/v1/backtest").contentType(MediaType.APPLICATION_JSON).content("""
                        {"symbol":"RELIANCE","timeframe":"1m",
                         "entry":"crossover(ema(close,5), ema(close,20))",
                         "exit":"crossunder(ema(close,5), ema(close,20))",
                         "stopLossPct":2,"initialCapital":100000,"commissionPct":0.03}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("RELIANCE"))
                .andExpect(jsonPath("$.metrics.bars").value(N))
                .andExpect(jsonPath("$.metrics.trades").isNumber())
                .andExpect(jsonPath("$.equity[0].equity").isNumber())
                .andExpect(jsonPath("$.trades").isArray());
    }

    @Test
    void backtestRejectsInvalidParameters() throws Exception {
        mvc.perform(post("/api/v1/backtest").contentType(MediaType.APPLICATION_JSON).content("""
                        {"symbol":"RELIANCE","timeframe":"1m","entry":"close > 0","positionSizePct":250}
                        """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/backtest").contentType(MediaType.APPLICATION_JSON).content("""
                        {"symbol":"RELIANCE","timeframe":"1m"}
                        """))
                .andExpect(status().isBadRequest());
    }

    // ---- quotes, universes, live ----

    @Test
    void quotesSkipUnknownSymbols() throws Exception {
        mvc.perform(get("/api/v1/quotes").param("symbols", "RELIANCE,NOSUCH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].symbol").value("RELIANCE"))
                .andExpect(jsonPath("$[0].ltp").isNumber())
                .andExpect(jsonPath("$[0].changePct").isNumber());
    }

    @Test
    void screenerUniversesAndAllExcludesIndices() throws Exception {
        mvc.perform(get("/api/v1/screener/universes")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("NIFTY50"))
                .andExpect(jsonPath("$[?(@.code=='BANKNIFTY')]").exists());
        mvc.perform(post("/api/v1/screener/run").contentType(MediaType.APPLICATION_JSON).content("""
                        {"expression":"close > 0","timeframe":"1m","universe":{"type":"all"}}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanned").value(2));
        mvc.perform(post("/api/v1/screener/run").contentType(MediaType.APPLICATION_JSON).content("""
                        {"expression":"close > 0","timeframe":"1m","universe":{"type":"index","value":"NIFTYIT"}}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanned").value(1)); // only TCS is a known NIFTY IT member here
        mvc.perform(post("/api/v1/screener/run").contentType(MediaType.APPLICATION_JSON).content("""
                        {"expression":"close > 0","timeframe":"1m","universe":{"type":"index","value":"NOPE"}}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void liveRequestsIgnoreUnknownSymbols() {
        assertFalse(live.request("NOT-A-SYMBOL"));
    }

    // ---- ui state ----

    @Test
    void uiStateRoundTripsJson() throws Exception {
        mvc.perform(get("/api/v1/ui-state/drawings:RELIANCE")).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/ui-state/drawings:RELIANCE").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"type\":\"hline\",\"price\":2950.5}]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/ui-state/drawings:RELIANCE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].price").value(2950.5));
        mvc.perform(put("/api/v1/ui-state/bad key!").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/ui-state/drawings:RELIANCE")).andExpect(status().isNoContent());
    }

    // ---- journal & portfolio ----

    @Test
    void filledOrdersAreJournalledAndReported() throws Exception {
        long t = System.currentTimeMillis();
        when(tradingClient.placeOrder(any()))
                .thenReturn(new OrderDto("o-1", "RELIANCE", "NSE", "BUY", "MARKET", "MIS", 10, 0, null,
                        "COMPLETE", 10, 2900, t - 60_000, ""))
                .thenReturn(new OrderDto("o-2", "RELIANCE", "NSE", "SELL", "MARKET", "MIS", 10, 0, null,
                        "COMPLETE", 10, 2950, t, ""))
                .thenReturn(new OrderDto("o-3", "RELIANCE", "NSE", "BUY", "LIMIT", "MIS", 5, 2000, null,
                        "OPEN", 0, 0, t, ""));
        String order = """
                {"symbol":"RELIANCE","exchange":"NSE","transaction_type":"BUY","order_type":"MARKET",
                 "product":"MIS","quantity":10}
                """;
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/trading/orders").contentType(MediaType.APPLICATION_JSON).content(order))
                    .andExpect(status().isOk());
        }

        mvc.perform(get("/api/v1/journal")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2))) // the OPEN order is not a fill
                .andExpect(jsonPath("$[0].side").value("SELL"))
                .andExpect(jsonPath("$[0].source").value("APP"));

        mvc.perform(get("/api/v1/portfolio/report")).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.trades").value(1))
                .andExpect(jsonPath("$.summary.grossPnl").value(500.0))
                .andExpect(jsonPath("$.realized[0].category").value("INTRADAY"))
                .andExpect(jsonPath("$.assumptions").isArray());

        mvc.perform(get("/api/v1/portfolio/report.csv")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("stockcharts-pnl.csv")))
                .andExpect(content().string(containsString("RELIANCE,LONG,10")));

        // Sync is idempotent for orders already journalled.
        when(tradingClient.getOrders()).thenReturn(List.of(
                new OrderDto("o-2", "RELIANCE", "NSE", "SELL", "MARKET", "MIS", 10, 0, null, "COMPLETE", 10,
                        2950, t, ""),
                new OrderDto("o-9", "TCS", "NSE", "BUY", "MARKET", "CNC", 1, 0, null, "COMPLETE", 1, 3800, t, "")));
        mvc.perform(post("/api/v1/journal/sync")).andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1));
    }

    @Test
    void manualJournalEntriesAreValidatedAndAnnotatable() throws Exception {
        mvc.perform(post("/api/v1/journal").contentType(MediaType.APPLICATION_JSON).content("""
                        {"symbol":"INFY","side":"BUY","quantity":0,"price":1500}
                        """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/journal").contentType(MediaType.APPLICATION_JSON).content("""
                        {"symbol":"infy","side":"BUY","quantity":3,"price":1500,"strategy":"breakout"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("INFY"))
                .andExpect(jsonPath("$.source").value("MANUAL"));
        long id = journal.findAll().get(0).getId();
        mvc.perform(put("/api/v1/journal/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strategy\":\"swing\",\"notes\":\"held through results\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").value("held through results"));
        mvc.perform(get("/api/v1/portfolio/report")).andExpect(jsonPath("$.openLots[0].symbol").value("INFY"));
    }

    // ---- observability ----

    @Test
    void prometheusEndpointExposesAppMetrics() throws Exception {
        mvc.perform(post("/api/v1/backtest").contentType(MediaType.APPLICATION_JSON).content("""
                        {"symbol":"RELIANCE","timeframe":"1m","entry":"close > open"}
                        """))
                .andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andExpect(content().string(containsString("stockcharts_backtest_duration")));
    }
}
