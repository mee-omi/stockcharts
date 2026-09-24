package com.stockcharts;

import com.stockcharts.candle.Candle;
import com.stockcharts.candle.CandleRepository;
import com.stockcharts.candle.Timeframe;
import com.stockcharts.history.HistorySyncService;
import com.stockcharts.instrument.Instrument;
import com.stockcharts.instrument.InstrumentRepository;
import com.stockcharts.marketdata.MarketDataClient;
import com.stockcharts.marketdata.dto.CandleDto;
import com.stockcharts.marketdata.dto.ProviderInfo;
import com.stockcharts.screener.Watchlist;
import com.stockcharts.screener.WatchlistRepository;
import com.stockcharts.trading.TradingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Scheduled history sync: plan, mock skip, incremental tail fetch, status endpoint. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "stockcharts.history-sync.timeframes=1d:30",
        "stockcharts.history-sync.indices=",
})
class HistorySyncIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private HistorySyncService sync;
    @Autowired
    private InstrumentRepository instruments;
    @Autowired
    private WatchlistRepository watchlists;
    @Autowired
    private CandleRepository candles;

    @MockBean
    private MarketDataClient marketData;
    @MockBean
    private TradingClient tradingClient;

    private static final long DAY = 86_400_000L;

    @BeforeEach
    void setUp() {
        candles.deleteAll();
        watchlists.deleteAll();
        instruments.save(new Instrument("INFY", "Infosys Ltd", 408065, "NSE"));
        watchlists.save(new Watchlist("core", List.of("INFY", "NOT-LISTED")));
        when(marketData.getProvider()).thenReturn(new ProviderInfo("zerodha", List.of("mock", "zerodha"), Map.of()));
        long today = System.currentTimeMillis() / DAY * DAY;
        List<CandleDto> bars = new ArrayList<>();
        for (int i = 29; i >= 0; i--) {
            bars.add(new CandleDto(today - i * DAY, 1500, 1510, 1490, 1505, 1000));
        }
        when(marketData.getHistorical(anyString(), anyString(), anyLong(), anyLong())).thenReturn(bars);
    }

    @Test
    void planUsesWatchlistsAndSkipsUnknownSymbols() {
        HistorySyncService.Plan plan = sync.plan();
        assertEquals(List.of("INFY"), plan.symbols());
        assertEquals(1, plan.timeframes().size());
    }

    @Test
    void firstRunDownloadsTheLookbackThenOnlyTheTail() {
        HistorySyncService.Status first = sync.runNow("test");
        assertFalse(first.running());
        assertEquals(1, first.symbols());
        assertEquals(30, first.candlesStored());
        assertEquals(0, first.failures());

        clearInvocations(marketData);
        Instant newest = candles.findFirstBySymbolAndTimeframeOrderByOpenTimeDesc("INFY", Timeframe.ONE_DAY)
                .map(Candle::getOpenTime).orElseThrow();
        sync.runNow("test");
        ArgumentCaptor<Long> from = ArgumentCaptor.forClass(Long.class);
        verify(marketData, atLeastOnce()).getHistorical(eq("INFY"), eq("day"), from.capture(), anyLong());
        // Only the tail since the newest stored bar is requested on the second run.
        assertTrue(from.getAllValues().stream().allMatch(f -> f >= newest.toEpochMilli()), from.getAllValues().toString());
    }

    @Test
    void mockProviderIsSkippedByDefault() {
        when(marketData.getProvider()).thenReturn(new ProviderInfo("mock", List.of("mock"), Map.of()));
        HistorySyncService.Status s = sync.runNow("test");
        assertNotNull(s.skippedReason());
        assertEquals(0, s.candlesStored());
    }

    @Test
    void statusEndpointDescribesTheSync() throws Exception {
        sync.runNow("test");
        mvc.perform(get("/api/v1/history-sync")).andExpect(status().isOk())
                .andExpect(jsonPath("$.trackedSymbols").value(1))
                .andExpect(jsonPath("$.lookbackDays.1d").value(30))
                .andExpect(jsonPath("$.status.trigger").value("test"))
                .andExpect(jsonPath("$.status.candlesStored").isNumber());
    }
}
