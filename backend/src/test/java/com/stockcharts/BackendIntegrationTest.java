package com.stockcharts;

import com.stockcharts.candle.CandleRepository;
import com.stockcharts.instrument.Instrument;
import com.stockcharts.instrument.InstrumentRepository;
import com.stockcharts.marketdata.MarketDataClient;
import com.stockcharts.marketdata.dto.CandleDto;
import com.stockcharts.trading.TradingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the full Spring context on H2 with a mocked data-service client, exercising the
 * REST surface (instruments → candles → indicators → trading) end-to-end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackendIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private InstrumentRepository instrumentRepository;

    @Autowired
    private CandleRepository candleRepository;

    @MockBean
    private MarketDataClient marketData;

    // Mocked so trading endpoints never call the (absent) data service in tests.
    @MockBean
    private TradingClient tradingClient;

    private static final int N = 120;

    @BeforeEach
    void setup() {
        // Clear candle table so each test starts with an empty cache — prevents
        // accumulation when multiple tests trigger the read-through backfill.
        candleRepository.deleteAll();

        instrumentRepository.save(new Instrument("RELIANCE", "Reliance Industries Ltd", 738561, "NSE"));

        long now = System.currentTimeMillis();
        List<CandleDto> candles = new ArrayList<>();
        double price = 2900;
        for (int i = 0; i < N; i++) {
            long ts = now - (long) (N - i) * 60_000L;
            double open = price;
            double close = open + Math.sin(i / 5.0) * 4;
            double high = Math.max(open, close) + 2;
            double low = Math.min(open, close) - 2;
            candles.add(new CandleDto(ts, open, high, low, close, 100000 + i));
            price = close;
        }
        when(marketData.getHistorical(eq("RELIANCE"), anyString(), anyLong(), anyLong()))
                .thenReturn(candles);
    }

    @Test
    void indicatorCatalogIsExposed() throws Exception {
        mvc.perform(get("/api/v1/indicators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(12))
                .andExpect(jsonPath("$[?(@.code=='MACD')]").exists());
    }

    @Test
    void candlesAreBackfilledAndServed() throws Exception {
        mvc.perform(get("/api/v1/candles/RELIANCE").param("timeframe", "1m"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(N))
                .andExpect(jsonPath("$[0].open").exists())
                .andExpect(jsonPath("$[0].time").exists());
    }

    @Test
    void applyIndicatorsReturnsAlignedSeries() throws Exception {
        String body = """
                {
                  "symbol": "RELIANCE",
                  "timeframe": "1m",
                  "indicators": [
                    { "type": "SMA", "params": { "period": 20 } },
                    { "type": "MACD", "params": {} }
                  ]
                }
                """;
        mvc.perform(post("/api/v1/indicators/apply")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("RELIANCE"))
                .andExpect(jsonPath("$.candles.length()").value(N))
                .andExpect(jsonPath("$.indicators.length()").value(2))
                .andExpect(jsonPath("$.indicators[0].key").value("SMA(20)"))
                .andExpect(jsonPath("$.indicators[0].lines[0].points.length()").value(N))
                .andExpect(jsonPath("$.indicators[1].lines.length()").value(3)); // macd/signal/histogram
    }

    @Test
    void unknownSymbolIs404() throws Exception {
        mvc.perform(get("/api/v1/candles/NOPE").param("timeframe", "1m"))
                .andExpect(status().isNotFound());
    }

    @Test
    void customIndicatorCreateAppearsInCatalogAndApplies() throws Exception {
        String create = """
                {"name":"ema_spread","expression":"ema(close,9) - ema(close,21)",
                 "overlay":true,"description":"EMA 9/21 spread"}
                """;
        mvc.perform(post("/api/v1/custom-indicators").contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("ema_spread"));

        mvc.perform(get("/api/v1/indicators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='ema_spread')]").exists());

        String apply = """
                {"symbol":"RELIANCE","timeframe":"1m","indicators":[{"type":"ema_spread","params":{}}]}
                """;
        mvc.perform(post("/api/v1/indicators/apply").contentType(MediaType.APPLICATION_JSON).content(apply))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indicators[0].type").value("CUSTOM"))
                .andExpect(jsonPath("$.indicators[0].lines[0].points.length()").value(N));
    }

    @Test
    void customIndicatorRejectsBadExpression() throws Exception {
        String create = """
                {"name":"bad_one","expression":"frobnicate(close, 5)","overlay":false}
                """;
        mvc.perform(post("/api/v1/custom-indicators").contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isBadRequest());
    }

    @Test
    void watchlistCrud() throws Exception {
        String body = """
                {"name":"mylist","symbols":["RELIANCE","TCS"]}
                """;
        mvc.perform(post("/api/v1/watchlists").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbols.length()").value(2));
        mvc.perform(get("/api/v1/watchlists/mylist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbols[0]").value("RELIANCE"));
    }

    @Test
    void screenerRunReturnsMatches() throws Exception {
        String body = """
                {"expression":"close > 0","timeframe":"1m",
                 "universe":{"type":"list","value":"RELIANCE"},
                 "columns":[{"label":"rsi","expression":"rsi(close,14)"}]}
                """;
        mvc.perform(post("/api/v1/screener/run").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanned").value(1))
                .andExpect(jsonPath("$.matched").value(1))
                .andExpect(jsonPath("$.matches[0].symbol").value("RELIANCE"))
                .andExpect(jsonPath("$.matches[0].close").isNumber())
                .andExpect(jsonPath("$.matches[0].columns.rsi").isNumber());
    }

    @Test
    void screenerRejectsBadExpression() throws Exception {
        String body = """
                {"expression":"frobnicate(close,5) > 1","timeframe":"1m",
                 "universe":{"type":"list","value":"RELIANCE"}}
                """;
        mvc.perform(post("/api/v1/screener/run").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
