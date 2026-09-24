package com.stockcharts.live;

import com.stockcharts.candle.Candle;
import com.stockcharts.candle.CandleService;
import com.stockcharts.candle.Timeframe;
import com.stockcharts.marketdata.dto.CandleDto;
import com.stockcharts.marketdata.dto.TickDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TickAggregatorTest {

    private static final long MIN = 60_000L;
    private static final long T0 = 1_790_000_040_000L - (1_790_000_040_000L % MIN); // a minute boundary

    private CandleService candles;
    private TickAggregator agg;

    @BeforeEach
    void setUp() {
        candles = mock(CandleService.class);
        when(candles.latest(any(), eq(Timeframe.ONE_MINUTE))).thenReturn(Optional.empty());
        agg = new TickAggregator(candles);
    }

    @Test
    void barSnapshotsReplaceTheBarInsteadOfAccumulating() {
        CandleDto bar = new CandleDto(T0, 100, 105, 99, 104, 5_000);
        LiveUpdate first = agg.onTick(new TickDto("INFY", 104, T0, 0, null, bar));
        LiveUpdate again = agg.onTick(new TickDto("INFY", 104, T0, 0, null,
                new CandleDto(T0, 100, 106, 99, 105, 6_000)));
        assertEquals(5_000, first.volume());
        assertEquals(6_000, again.volume()); // replaced, not 11,000
        assertEquals(106, again.high());
        assertEquals(100, again.open());
    }

    @Test
    void dayVolumeIsTurnedIntoPerBarIncrements() {
        LiveUpdate a = agg.onTick(new TickDto("TCS", 10, T0 + 1_000, 0, 1_000.0, null));
        LiveUpdate b = agg.onTick(new TickDto("TCS", 11, T0 + 2_000, 0, 1_250.0, null));
        LiveUpdate c = agg.onTick(new TickDto("TCS", 9, T0 + MIN + 1_000, 0, 1_400.0, null));
        assertEquals(0, a.volume()); // first tick after connect: no baseline yet
        assertEquals(250, b.volume());
        assertEquals(11, b.high());
        assertEquals(150, c.volume()); // next minute starts from the increment only
        assertEquals(9, c.open());
        LiveUpdate reset = agg.onTick(new TickDto("TCS", 9, T0 + 2 * MIN, 0, 20.0, null));
        assertEquals(0, reset.volume()); // day volume went down → new session, no negative volume
    }

    @Test
    void perTickVolumeStillAccumulatesForTheMockFeed() {
        agg.onTick(new TickDto("SBIN", 800, T0 + 1_000, 30));
        LiveUpdate u = agg.onTick(new TickDto("SBIN", 801, T0 + 5_000, 20));
        assertEquals(50, u.volume());
    }

    @Test
    void aBarThatHistoryAlreadyHasIsContinuedNotOverwritten() {
        Candle stored = new Candle("RELIANCE", Timeframe.ONE_MINUTE, Instant.ofEpochMilli(T0), 2900, 2910, 2895, 2905, 7_000);
        when(candles.latest(eq("RELIANCE"), eq(Timeframe.ONE_MINUTE))).thenReturn(Optional.of(stored));
        LiveUpdate u = agg.onTick(new TickDto("RELIANCE", 2915, T0 + 30_000, 0, 50_000.0, null));
        assertEquals(2900, u.open());
        assertEquals(2915, u.high());
        assertEquals(2895, u.low());
        assertEquals(2915, u.close());
        assertEquals(7_000, u.volume());
    }
}
