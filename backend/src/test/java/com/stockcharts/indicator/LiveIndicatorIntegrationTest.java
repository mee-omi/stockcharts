package com.stockcharts.indicator;

import com.jayway.jsonpath.JsonPath;
import com.stockcharts.candle.Candle;
import com.stockcharts.candle.CandleRepository;
import com.stockcharts.candle.Timeframe;
import com.stockcharts.instrument.Instrument;
import com.stockcharts.instrument.InstrumentRepository;
import com.stockcharts.marketdata.MarketDataClient;
import com.stockcharts.marketdata.dto.CandleDto;
import com.stockcharts.trading.TradingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LiveIndicatorIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    CandleRepository candles;
    @Autowired
    InstrumentRepository instruments;
    @MockBean
    MarketDataClient marketData;
    @MockBean
    TradingClient trading;

    private static final String SPECS = "[{\"type\":\"EMA\",\"params\":{\"period\":20}},"
            + "{\"type\":\"RSI\",\"params\":{\"period\":14}}]";
    private long lastSec;

    @BeforeEach
    void seed() {
        candles.deleteAll();
        instruments.save(new Instrument("INFY", "Infosys Ltd", 408065, "NSE"));
        long now = System.currentTimeMillis() / 60_000 * 60_000;
        List<CandleDto> series = new ArrayList<>();
        double price = 1500;
        for (int i = 0; i < 300; i++) {
            double close = price + Math.sin(i / 5.0) * 4;
            series.add(new CandleDto(now - (300L - i) * 60_000, price, Math.max(price, close) + 1,
                    Math.min(price, close) - 1, close, 1000));
            price = close;
        }
        lastSec = series.get(series.size() - 1).timestamp() / 1000;
        when(marketData.getHistorical(anyString(), anyString(), anyLong(), anyLong())).thenReturn(series);
    }

    private double lastValue(String json, int indicator) {
        List<Double> values = JsonPath.read(json, "$[" + indicator + "].lines[0].points[*].value");
        return values.get(values.size() - 1);
    }

    @Test
    void liveValuesUseTheFormingBarAndMatchAReload() throws Exception {
        // Load history the way the chart does.
        mvc.perform(post("/api/v1/indicators/apply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"INFY\",\"timeframe\":\"1m\",\"indicators\":" + SPECS + "}"))
                .andExpect(status().isOk());

        // The forming bar jumped: +40 on the close.
        String bar = "{\"time\":" + lastSec + ",\"open\":1500,\"high\":1560,\"low\":1499,\"close\":1550,\"volume\":1500}";
        String live = mvc.perform(post("/api/v1/indicators/live").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"INFY\",\"timeframe\":\"1m\",\"indicators\":" + SPECS
                                + ",\"bars\":[" + bar + "],\"tail\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].lines[0].points", hasSize(2)))
                .andExpect(jsonPath("$[0].lines[0].points[1].time").value(lastSec))
                .andReturn().getResponse().getContentAsString();

        // Store that same bar and reload: identical numbers.
        Candle stored = candles.findAll().stream()
                .filter(c -> c.getTimeframe() == Timeframe.ONE_MINUTE && c.getOpenTime().getEpochSecond() == lastSec)
                .findFirst().orElseThrow();
        double before = stored.getClose();
        candles.save(new Candle("INFY", Timeframe.ONE_MINUTE, Instant.ofEpochSecond(lastSec), 1500, 1560, 1499, 1550, 1500));
        String reload = mvc.perform(post("/api/v1/indicators/apply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"INFY\",\"timeframe\":\"1m\",\"indicators\":"
                                + SPECS + "}"))
                .andReturn().getResponse().getContentAsString();
        List<Double> ema = JsonPath.read(reload, "$.indicators[0].lines[0].points[*].value");
        List<Double> rsi = JsonPath.read(reload, "$.indicators[1].lines[0].points[*].value");
        assertEquals(ema.get(ema.size() - 1), lastValue(live, 0), 1e-9);
        assertEquals(rsi.get(rsi.size() - 1), lastValue(live, 1), 1e-9);
        assertNotEquals(before, 1550.0);
    }

    @Test
    void rejectsOversizedRequests() throws Exception {
        StringBuilder bars = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            bars.append(i == 0 ? "" : ",").append("{\"time\":").append(lastSec + i * 60L)
                    .append(",\"open\":1,\"high\":1,\"low\":1,\"close\":1,\"volume\":1}");
        }
        mvc.perform(post("/api/v1/indicators/live").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"INFY\",\"timeframe\":\"1m\",\"indicators\":" + SPECS
                                + ",\"bars\":[" + bars + "]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/indicators/live").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"NOPE\",\"timeframe\":\"1m\",\"indicators\":" + SPECS + "}"))
                .andExpect(status().isNotFound());
    }
}
