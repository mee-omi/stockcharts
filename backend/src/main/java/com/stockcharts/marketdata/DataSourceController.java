package com.stockcharts.marketdata;

import com.stockcharts.instrument.InstrumentService;
import com.stockcharts.live.LiveDataService;
import com.stockcharts.marketdata.dto.ProviderInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** View and switch the live data source (mock ↔ Zerodha) at runtime. */
@RestController
@RequestMapping("/api/v1/datasource")
@Tag(name = "Data source", description = "Switch the market-data provider")
public class DataSourceController {

    private final MarketDataClient marketData;
    private final InstrumentService instrumentService;
    private final LiveDataService liveDataService;

    public DataSourceController(MarketDataClient marketData, InstrumentService instrumentService,
                               LiveDataService liveDataService) {
        this.marketData = marketData;
        this.instrumentService = instrumentService;
        this.liveDataService = liveDataService;
    }

    public record SetProviderRequest(String provider, String enctoken) {
    }

    @GetMapping
    @Operation(summary = "Get the active data source and the available options")
    public ProviderInfo current() {
        return marketData.getProvider();
    }

    @PostMapping
    @Operation(summary = "Switch the data source; re-syncs instruments and reconnects the live stream")
    public ProviderInfo switchProvider(@RequestBody SetProviderRequest req) {
        ProviderInfo info = marketData.setProvider(req.provider(), req.enctoken());
        // The instrument universe and live feed differ per source — refresh both.
        instrumentService.syncInstruments();
        liveDataService.restart();
        return info;
    }
}
