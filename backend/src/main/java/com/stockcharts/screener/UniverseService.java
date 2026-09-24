package com.stockcharts.screener;

import com.stockcharts.config.AppProperties;
import com.stockcharts.instrument.Instrument;
import com.stockcharts.instrument.InstrumentRepository;
import com.stockcharts.screener.dto.ScreenerRequest;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves a universe (index / list / watchlist / all) to concrete symbols, keeping only symbols
 * present in the instrument table and capping the size at {@code stockcharts.screener.max-universe}.
 * Shared by the screener and the backtest scanner.
 */
@Service
public class UniverseService {

    /** Resolved symbols plus their display names; {@code truncated} when the size cap applied. */
    public record Resolved(List<String> symbols, Map<String, String> names, boolean truncated) {
    }

    private final InstrumentRepository instruments;
    private final WatchlistRepository watchlists;
    private final AppProperties props;

    public UniverseService(InstrumentRepository instruments, WatchlistRepository watchlists, AppProperties props) {
        this.instruments = instruments;
        this.watchlists = watchlists;
        this.props = props;
    }

    public Resolved resolve(ScreenerRequest.Universe u) {
        List<Instrument> all = instruments.findAll();
        Map<String, String> names = all.stream()
                .collect(Collectors.toMap(Instrument::getSymbol, Instrument::getName, (a, b) -> a));
        Set<String> equities = all.stream().filter(i -> !i.isIndex())
                .map(Instrument::getSymbol).collect(Collectors.toSet());

        String type = u == null || u.type() == null ? "index" : u.type().toLowerCase();
        String value = u == null ? null : u.value();
        List<String> symbols = switch (type) {
            case "all" -> equities.stream().sorted().collect(Collectors.toList());
            case "index" -> IndexUniverses.find(value)
                    .map(d -> d.symbols().stream().sorted().collect(Collectors.toList()))
                    .orElseThrow(() -> new IllegalArgumentException("Unknown index universe: " + value));
            case "list" -> value == null ? List.of()
                    : Arrays.stream(value.split(","))
                    .map(x -> x.trim().toUpperCase()).filter(x -> !x.isEmpty()).toList();
            case "watchlist" -> watchlists.findById(value == null ? "" : value)
                    .map(Watchlist::getSymbols)
                    .orElseThrow(() -> new NoSuchElementException("Watchlist not found: " + value));
            default -> throw new IllegalArgumentException("Unknown universe type: " + type);
        };
        List<String> filtered = symbols.stream().filter(names::containsKey).distinct().collect(Collectors.toList());
        int max = props.getScreener().getMaxUniverse();
        boolean truncated = filtered.size() > max;
        if (truncated) {
            filtered = filtered.subList(0, max);
        }
        return new Resolved(filtered, names, truncated);
    }
}
