package com.stockcharts.paper;

import com.stockcharts.journal.JournalRepository;
import com.stockcharts.live.LiveDataService;
import com.stockcharts.live.PriceTick;
import com.stockcharts.market.NseCalendar;
import com.stockcharts.marketdata.MarketDataClient;
import com.stockcharts.quote.PriceSource;
import com.stockcharts.trading.RemoteTradingClient;
import com.stockcharts.trading.TradingClient;
import com.stockcharts.trading.dto.FundsDto;
import com.stockcharts.trading.dto.ModifyOrderRequest;
import com.stockcharts.trading.dto.OrderDto;
import com.stockcharts.trading.dto.PlaceOrderRequest;
import com.stockcharts.trading.dto.PositionDto;
import com.stockcharts.trading.dto.TradingBrokerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class PaperBrokerIntegrationTest {

    /** A clock the test moves by hand. */
    static class TestClock extends Clock {
        volatile Instant now = Instant.now();

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

        void setIst(String dateTime) {
            now = LocalDateTime.parse(dateTime).atZone(NseCalendar.IST).toInstant();
        }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }

    @Autowired
    TestClock clock;
    @Autowired
    TradingClient trading;
    @Autowired
    PaperBroker paper;
    @Autowired
    JournalRepository journal;

    @MockBean
    RemoteTradingClient remote;
    @MockBean
    PriceSource prices;
    @MockBean
    LiveDataService live;
    @MockBean
    MarketDataClient marketData;

    @BeforeEach
    void setUp() {
        when(remote.getBroker()).thenReturn(new TradingBrokerInfo("mock", List.of("mock", "zerodha")));
        clock.setIst("2026-09-24T10:00"); // a Thursday, market open
        paper.reset(1_000_000.0);
        journal.deleteAll();
        price("RELIANCE", 2900);
        price("TCS", 3800);
    }

    private void price(String symbol, double ltp) {
        when(prices.ltp(symbol)).thenReturn(OptionalDouble.of(ltp));
    }

    private void tick(String symbol, double ltp) {
        price(symbol, ltp);
        paper.onTick(new PriceTick(symbol, ltp, clock.instant().toEpochMilli()));
    }

    private static PlaceOrderRequest order(String side, String type, String product, int qty, Double price,
                                           Double trigger) {
        return new PlaceOrderRequest("RELIANCE", "NSE", side, type, product, qty, price, trigger, null);
    }

    private PositionDto position(String product) {
        return trading.getPositions().net().stream().filter(p -> p.product().equals(product)).findFirst().orElseThrow();
    }

    @Test
    void marketOrdersFillAtTheLivePriceWithSlippageAndCharges() {
        OrderDto o = trading.placeOrder(order("BUY", "MARKET", "CNC", 10, null, null));
        assertEquals("COMPLETE", o.status());
        assertEquals(2901.45, o.averagePrice(), 1e-9); // 2900 + 0.05% slippage, on the ₹0.05 tick grid
        FundsDto f = trading.getFunds();
        double value = 10 * 2901.45;
        double charges = com.stockcharts.journal.ChargesModel.leg(value, true, false);
        assertEquals(Math.round((1_000_000 - value - charges) * 100) / 100.0, f.availableCash(), 1e-6);
        assertEquals(1, journal.count(), "fills are journalled");
        verify(remote, never()).placeOrder(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void limitOrdersWaitForThePriceAndBlockFunds() {
        OrderDto o = trading.placeOrder(order("BUY", "LIMIT", "CNC", 10, 2850.0, null));
        assertEquals("OPEN", o.status());
        assertEquals(28_500 + com.stockcharts.journal.ChargesModel.leg(28_500, true, false),
                trading.getFunds().usedMargin(), 0.01);

        tick("RELIANCE", 2870);
        assertEquals("OPEN", trading.getOrders().get(0).status());
        tick("RELIANCE", 2845); // crossed: fills at the better price
        OrderDto filled = trading.getOrders().get(0);
        assertEquals("COMPLETE", filled.status());
        assertEquals(2845, filled.averagePrice(), 1e-9);
        assertEquals(0, trading.getFunds().usedMargin(), 1e-9);
        assertEquals(1, journal.count());
    }

    @Test
    void stopLossTriggersOnTheWayDown() {
        trading.placeOrder(order("BUY", "MARKET", "MIS", 10, null, null));
        OrderDto sl = trading.placeOrder(order("SELL", "SL-M", "MIS", 10, null, 2880.0));
        assertEquals("TRIGGER_PENDING", sl.status());
        // A sell stop above the price would fire at once: rejected like a real broker.
        OrderDto bad = trading.placeOrder(order("SELL", "SL-M", "MIS", 10, null, 2950.0));
        assertEquals("REJECTED", bad.status());

        tick("RELIANCE", 2890);
        assertEquals("TRIGGER_PENDING", trading.getOrders().stream()
                .filter(x -> x.orderId().equals(sl.orderId())).findFirst().orElseThrow().status());
        tick("RELIANCE", 2875);
        OrderDto done = trading.getOrders().stream().filter(x -> x.orderId().equals(sl.orderId())).findFirst().orElseThrow();
        assertEquals("COMPLETE", done.status());
        assertEquals(0, position("MIS").quantity());
        assertTrue(position("MIS").pnl() < 0, "stopped out at a loss");
    }

    @Test
    void fundsAndHoldingsAreChecked() {
        OrderDto tooBig = trading.placeOrder(order("BUY", "MARKET", "CNC", 1_000, null, null)); // ₹29 lakh
        assertEquals("REJECTED", tooBig.status());
        assertTrue(tooBig.message().startsWith("Insufficient funds"), tooBig.message());
        // The same quantity fits intraday at 5x leverage.
        assertEquals("COMPLETE", trading.placeOrder(order("BUY", "MARKET", "MIS", 300, null, null)).status());
        assertEquals(300 * 2901.45 / 5, trading.getFunds().usedMargin(), 0.01);

        OrderDto noShares = trading.placeOrder(order("SELL", "MARKET", "CNC", 5, null, null));
        assertEquals("REJECTED", noShares.status());
        assertTrue(noShares.message().startsWith("Insufficient holdings"));
        assertThrows(IllegalArgumentException.class,
                () -> trading.placeOrder(order("BUY", "MARKET", "NRML", 1, null, null)));
    }

    @Test
    void intradayShortIsSquaredOffAt1520() {
        trading.placeOrder(order("SELL", "MARKET", "MIS", 20, null, null)); // short at 2898.55
        price("RELIANCE", 2850);
        clock.setIst("2026-09-24T15:19");
        paper.sessionTick();
        assertEquals(-20, position("MIS").quantity());

        clock.setIst("2026-09-24T15:20");
        paper.sessionTick();
        PositionDto p = position("MIS");
        assertEquals(0, p.quantity());
        assertTrue(p.pnl() > 900, "short profited ~₹950 before charges: " + p.pnl());
        assertTrue(trading.getOrders().stream().anyMatch(o -> o.message().startsWith("Auto square-off")));
        assertEquals(0, trading.getFunds().usedMargin(), 1e-9);
    }

    @Test
    void deliveryBuysBecomeHoldingsNextDayAndCanBeSold() {
        trading.placeOrder(order("BUY", "MARKET", "CNC", 10, null, null));
        assertTrue(trading.getHoldings().isEmpty());

        clock.setIst("2026-09-25T09:30");
        price("RELIANCE", 3000);
        assertTrue(trading.getPositions().net().isEmpty(), "day positions reset");
        var h = trading.getHoldings();
        assertEquals(1, h.size());
        assertEquals(10, h.get(0).quantity());
        assertEquals(2901.45, h.get(0).averagePrice(), 1e-9);
        assertTrue(h.get(0).pnl() > 980);

        OrderDto sell = trading.placeOrder(order("SELL", "MARKET", "CNC", 10, null, null));
        assertEquals("COMPLETE", sell.status());
        assertTrue(trading.getHoldings().isEmpty());
        assertTrue(position("CNC").pnl() > 950, "realised against the holding's average");
    }

    @Test
    void ordersOutsideTheSessionWaitForTheOpenAndDayOrdersExpire() {
        clock.setIst("2026-09-24T19:00");
        OrderDto amo = trading.placeOrder(order("BUY", "MARKET", "CNC", 5, null, null));
        assertEquals("OPEN", amo.status());
        assertTrue(amo.message().startsWith("Queued"));
        tick("RELIANCE", 2905); // after hours: ignored
        assertEquals("OPEN", trading.getOrders().get(0).status());

        clock.setIst("2026-09-25T09:15");
        tick("RELIANCE", 2910);
        assertEquals("COMPLETE", trading.getOrders().stream()
                .filter(o -> o.orderId().equals(amo.orderId())).findFirst().orElseThrow().status());

        OrderDto limit = trading.placeOrder(order("BUY", "LIMIT", "CNC", 5, 2500.0, null));
        clock.setIst("2026-09-25T15:30");
        paper.sessionTick();
        OrderDto expired = trading.getOrders().stream().filter(o -> o.orderId().equals(limit.orderId()))
                .findFirst().orElseThrow();
        assertEquals("CANCELLED", expired.status());
        assertTrue(expired.message().contains("market close"));
    }

    @Test
    void openOrdersCanBeModifiedAndCancelled() {
        OrderDto o = trading.placeOrder(order("BUY", "LIMIT", "CNC", 10, 2800.0, null));
        OrderDto moved = trading.modifyOrder(o.orderId(), new ModifyOrderRequest(20, 2810.0, null, null));
        assertEquals(20, moved.quantity());
        assertEquals(2810.0, moved.price(), 1e-9);
        assertEquals("OPEN", moved.status());
        trading.cancelOrder(o.orderId());
        assertEquals("CANCELLED", trading.getOrders().get(0).status());
        assertEquals(0, trading.getFunds().usedMargin(), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> trading.cancelOrder(o.orderId()));
    }

    @Test
    void realBrokersBypassThePaperAccount() {
        TradingBrokerInfo zerodha = new TradingBrokerInfo("zerodha", List.of("mock", "zerodha"));
        when(remote.getBroker()).thenReturn(zerodha);
        when(remote.setBroker("zerodha", java.util.Map.of())).thenReturn(zerodha);
        trading.setBroker("zerodha", java.util.Map.of());
        when(remote.getOrders()).thenReturn(List.of());
        assertTrue(trading.getOrders().isEmpty());
        verify(remote).getOrders();
        verify(live, never()).request(anyString());
        TradingBrokerInfo mock = new TradingBrokerInfo("mock", List.of("mock", "zerodha"));
        when(remote.getBroker()).thenReturn(mock);
        when(remote.setBroker("mock", java.util.Map.of())).thenReturn(mock);
        trading.setBroker("mock", java.util.Map.of());
        assertTrue(trading.isPaper());
    }
}
