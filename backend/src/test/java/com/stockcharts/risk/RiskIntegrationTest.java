package com.stockcharts.risk;

import com.stockcharts.journal.JournalRepository;
import com.stockcharts.marketdata.MarketDataClient;
import com.stockcharts.trading.TradingClient;
import com.stockcharts.trading.dto.OrderDto;
import com.stockcharts.trading.dto.PositionDto;
import com.stockcharts.trading.dto.PositionsDto;
import com.stockcharts.trading.dto.TradingBrokerInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RiskIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RiskService risk;

    @Autowired
    private JournalRepository journal;

    @MockBean
    private MarketDataClient marketData;

    @MockBean
    private TradingClient trading;

    private static final String LIMIT_BUY = """
            {"symbol":"RELIANCE","exchange":"NSE","transaction_type":"BUY","order_type":"LIMIT",
             "product":"MIS","quantity":10,"price":2900}
            """;

    private static final OrderDto FILLED = new OrderDto("o-1", "RELIANCE", "NSE", "BUY", "LIMIT", "MIS", 10, 2900,
            null, "OPEN", 0, 0, System.currentTimeMillis(), "");

    @BeforeEach
    void setup() {
        journal.deleteAll();
        risk.update(RiskSettings.defaults());
        broker("mock");
        when(trading.getOrders()).thenReturn(List.of());
        when(trading.getPositions()).thenReturn(new PositionsDto(List.of(), List.of()));
        when(trading.placeOrder(any())).thenReturn(FILLED);
    }

    @AfterEach
    void restore() {
        risk.update(RiskSettings.defaults());
    }

    private void broker(String name) {
        when(trading.getBroker()).thenReturn(new TradingBrokerInfo(name, List.of("mock", "zerodha")));
    }

    private void settings(String json) throws Exception {
        mvc.perform(put("/api/v1/risk").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    @Test
    void limitsRejectWith422AndReasons() throws Exception {
        settings("""
                {"maxQuantityPerOrder":5,"maxOrderValue":10000,"maxOrdersPerDay":50,"requireConfirmation":true,
                 "applyToPaper":true}
                """);
        mvc.perform(post("/api/v1/trading/orders").contentType(MediaType.APPLICATION_JSON).content(LIMIT_BUY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations[0]").value(containsString("Quantity 10 exceeds")))
                .andExpect(jsonPath("$.violations[1]").value(containsString("exceeds the limit of ₹10000")));
        verify(trading, never()).placeOrder(any());

        // Paper trading can opt out of the limits.
        settings("{\"maxQuantityPerOrder\":5,\"applyToPaper\":false}");
        mvc.perform(post("/api/v1/trading/orders").contentType(MediaType.APPLICATION_JSON).content(LIMIT_BUY))
                .andExpect(status().isOk());
    }

    @Test
    void invalidSettingsAreRejected() throws Exception {
        mvc.perform(put("/api/v1/risk").contentType(MediaType.APPLICATION_JSON).content("{\"maxOrderValue\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dailyOrderCountAndLossLimit() throws Exception {
        settings("{\"maxOrdersPerDay\":1,\"applyToPaper\":true}");
        when(trading.getOrders()).thenReturn(List.of(FILLED));
        mvc.perform(post("/api/v1/trading/orders").contentType(MediaType.APPLICATION_JSON).content(LIMIT_BUY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations[0]").value(containsString("Daily order limit reached (1/1)")));

        settings("{\"maxDailyLoss\":1000,\"applyToPaper\":true}");
        when(trading.getPositions()).thenReturn(new PositionsDto(List.of(
                new PositionDto("RELIANCE", "NSE", "MIS", 20, 20, 0, 3000, 2900, -2000, 0)), List.of()));
        mvc.perform(get("/api/v1/risk")).andExpect(jsonPath("$.lossLimitReached").value(true))
                .andExpect(jsonPath("$.dayPnl").value(-2000.0));
        mvc.perform(post("/api/v1/trading/orders").contentType(MediaType.APPLICATION_JSON).content(LIMIT_BUY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations", hasItem(containsString("Daily loss limit reached"))));

        // Exiting the losing position is always allowed.
        mvc.perform(post("/api/v1/trading/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(LIMIT_BUY.replace("BUY", "SELL")))
                .andExpect(status().isOk());
    }

    @Test
    void killSwitchBlocksNewExposureAndCancelsOpenOrders() throws Exception {
        when(trading.getOrders()).thenReturn(List.of(FILLED,
                new OrderDto("o-2", "TCS", "NSE", "BUY", "MARKET", "CNC", 1, 0, null, "COMPLETE", 1, 3800, 0, "")));
        mvc.perform(post("/api/v1/risk/kill-switch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"cancelOpenOrders\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled[0]").value("o-1"))
                .andExpect(jsonPath("$.state.settings.killSwitch").value(true));
        verify(trading, times(1)).cancelOrder(anyString());

        mvc.perform(post("/api/v1/trading/orders").contentType(MediaType.APPLICATION_JSON).content(LIMIT_BUY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations[0]").value(containsString("Kill switch is ON")));
        mvc.perform(put("/api/v1/trading/orders/o-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1,\"price\":2800}"))
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(post("/api/v1/risk/kill-switch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(jsonPath("$.state.settings.killSwitch").value(false));
        mvc.perform(post("/api/v1/trading/orders").contentType(MediaType.APPLICATION_JSON).content(LIMIT_BUY))
                .andExpect(status().isOk());
    }

    @Test
    void killSwitchEngagesEvenWhenTheBrokerIsUnreachable() throws Exception {
        when(trading.getOrders()).thenThrow(new IllegalStateException("data service down"));
        mvc.perform(post("/api/v1/risk/kill-switch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"cancelOpenOrders\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.settings.killSwitch").value(true))
                .andExpect(jsonPath("$.errors[0]").value(containsString("cancel them in the broker app")));
    }

    @Test
    void liveOrdersNeedASingleUseConfirmationForTheExactOrder() throws Exception {
        broker("zerodha");
        mvc.perform(post("/api/v1/trading/orders").contentType(MediaType.APPLICATION_JSON).content(LIMIT_BUY))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.confirmationRequired").value(true));

        String body = mvc.perform(post("/api/v1/trading/orders/preview").contentType(MediaType.APPLICATION_JSON)
                        .content(LIMIT_BUY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessment.estimatedValue").value(29000.0))
                .andExpect(jsonPath("$.assessment.live").value(true))
                .andReturn().getResponse().getContentAsString();
        String token = com.jayway.jsonpath.JsonPath.read(body, "$.confirmationToken");

        // A token for this order does not authorise a different one.
        mvc.perform(post("/api/v1/trading/orders").header("X-Order-Confirmation", token)
                        .contentType(MediaType.APPLICATION_JSON).content(LIMIT_BUY.replace("10", "11")))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(post("/api/v1/trading/orders").header("X-Order-Confirmation", token)
                        .contentType(MediaType.APPLICATION_JSON).content(LIMIT_BUY))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/trading/orders").header("X-Order-Confirmation", token)
                        .contentType(MediaType.APPLICATION_JSON).content(LIMIT_BUY))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.error").value(containsString("already used")));
        verify(trading, times(1)).placeOrder(any());
    }
}
