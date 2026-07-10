package com.stockcharts.candle;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CandleRepository extends JpaRepository<Candle, CandleId> {

    List<Candle> findBySymbolAndTimeframeAndOpenTimeBetweenOrderByOpenTimeAsc(
            String symbol, Timeframe timeframe, Instant from, Instant to);

    Optional<Candle> findFirstBySymbolAndTimeframeOrderByOpenTimeDesc(
            String symbol, Timeframe timeframe);

    Optional<Candle> findFirstBySymbolAndTimeframeOrderByOpenTimeAsc(
            String symbol, Timeframe timeframe);
}
