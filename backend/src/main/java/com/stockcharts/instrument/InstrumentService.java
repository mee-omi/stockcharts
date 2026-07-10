package com.stockcharts.instrument;

import com.stockcharts.marketdata.MarketDataClient;
import com.stockcharts.marketdata.dto.InstrumentDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Keeps the local instrument table in sync with the data service so the
 * searchable dropdown is served straight from the DB.
 */
@Service
public class InstrumentService {

    private static final Logger log = LoggerFactory.getLogger(InstrumentService.class);

    private final MarketDataClient marketData;
    private final InstrumentRepository repository;

    public InstrumentService(MarketDataClient marketData, InstrumentRepository repository) {
        this.marketData = marketData;
        this.repository = repository;
    }

    /** Sync once the app is ready (don't block startup if the data service is slow). */
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        syncInstruments();
    }

    /** Refresh the universe a few times a day. */
    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT6H")
    public void scheduledSync() {
        syncInstruments();
    }

    public void syncInstruments() {
        try {
            List<InstrumentDto> dtos = marketData.listInstruments();
            if (dtos == null || dtos.isEmpty()) {
                log.warn("Data service returned no instruments; skipping sync");
                return;
            }
            List<Instrument> entities = dtos.stream()
                    .map(d -> new Instrument(d.symbol(), d.name(), d.instrumentToken(), d.exchange()))
                    .toList();
            repository.saveAll(entities);
            log.info("Synced {} instruments from data service", entities.size());
        } catch (DataAccessException e) {
            log.error("Failed to persist instruments", e);
        } catch (Exception e) {
            log.warn("Could not reach data service for instrument sync: {}", e.getMessage());
        }
    }

    public List<Instrument> search(String query) {
        if (query == null || query.isBlank()) {
            return repository.findAll().stream()
                    .sorted((a, b) -> a.getSymbol().compareTo(b.getSymbol()))
                    .toList();
        }
        return repository.search(query.trim());
    }

    public boolean exists(String symbol) {
        return repository.existsById(symbol.toUpperCase());
    }
}
