package com.stockcharts.candle;

import com.stockcharts.marketdata.MarketDataClient;
import com.stockcharts.marketdata.dto.CandleDto;
import com.stockcharts.trading.TradingClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Regression: two chart loads at once (e.g. restoring a saved layout) used to race. The second
 * saw the first's download as "already attempted", skipped it, and returned only the few bars
 * built from live ticks, because the first hadn't committed yet.
 */
@SpringBootTest
@ActiveProfiles("test")
class CandleCoverageConcurrencyTest {

    @Autowired
    CandleService service;
    @Autowired
    CandleRepository repository;
    @MockBean
    MarketDataClient marketData;
    @MockBean
    TradingClient trading;

    @Test
    void concurrentLoadsBothSeeTheFullHistory() throws Exception {
        repository.deleteAll();
        Instant to = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        Instant from = to.minusSeconds(3 * 24 * 3600);
        // Two bars from the live tick aggregator already exist.
        repository.save(new Candle("SBIN", Timeframe.ONE_MINUTE, to.minusSeconds(60), 800, 801, 799, 800, 10));
        repository.save(new Candle("SBIN", Timeframe.ONE_MINUTE, to, 800, 801, 799, 800, 10));

        List<CandleDto> history = new ArrayList<>();
        for (long t = from.toEpochMilli(); t < to.minusSeconds(60).toEpochMilli(); t += 60_000) {
            history.add(new CandleDto(t, 800, 801, 799, 800, 100));
        }
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(marketData.getHistorical(anyString(), anyString(), anyLong(), anyLong())).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS); // hold the first download open
            return history;
        });

        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(
                () -> service.getCandles("SBIN", Timeframe.ONE_MINUTE, from, to).size());
        entered.await(5, TimeUnit.SECONDS);
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(
                () -> service.getCandles("SBIN", Timeframe.ONE_MINUTE, from, to).size());
        Thread.sleep(300); // let the second call reach the coverage check
        release.countDown();

        int expected = history.size() + 2;
        assertEquals(expected, first.get(10, TimeUnit.SECONDS));
        assertEquals(expected, second.get(10, TimeUnit.SECONDS));
    }
}
