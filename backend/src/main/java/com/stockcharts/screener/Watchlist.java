package com.stockcharts.screener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** A named, reusable list of symbols. Symbols are stored as an uppercase CSV. */
@Entity
@Table(name = "watchlist")
public class Watchlist {

    @Id
    @Column(length = 64)
    private String name;

    @Column(name = "symbols", nullable = false, length = 4000)
    private String symbolsCsv = "";

    protected Watchlist() {
    }

    public Watchlist(String name, List<String> symbols) {
        this.name = name;
        setSymbols(symbols);
    }

    public String getName() {
        return name;
    }

    public List<String> getSymbols() {
        if (symbolsCsv == null || symbolsCsv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(symbolsCsv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public void setSymbols(List<String> symbols) {
        this.symbolsCsv = symbols == null ? "" : symbols.stream()
                .map(s -> s.trim().toUpperCase())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(","));
    }
}
