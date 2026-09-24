package com.stockcharts.screener;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Named index universes for the screener ({@code universe.type = "index"}).
 *
 * <p>Constituent lists are a static snapshot and drift as indices rebalance; symbols that are not in
 * the synced instrument table are ignored at run time, so a stale entry is harmless.
 */
public final class IndexUniverses {

    public record IndexDef(String code, String label, List<String> symbols) {
    }

    public static final String DEFAULT = "NIFTY50";

    private static final Map<String, IndexDef> INDICES = new LinkedHashMap<>();

    static {
        add("NIFTY50", "NIFTY 50", List.of(
                "RELIANCE", "TCS", "HDFCBANK", "INFY", "ICICIBANK", "HINDUNILVR", "ITC", "SBIN",
                "BHARTIARTL", "KOTAKBANK", "LT", "AXISBANK", "ASIANPAINT", "MARUTI", "BAJFINANCE",
                "HCLTECH", "SUNPHARMA", "TITAN", "WIPRO", "ULTRACEMCO", "NESTLEIND", "ONGC", "NTPC",
                "POWERGRID", "TATAMOTORS", "TATASTEEL", "JSWSTEEL", "ADANIENT", "ADANIPORTS", "COALINDIA",
                "BAJAJFINSV", "HDFCLIFE", "GRASIM", "DRREDDY", "CIPLA", "BRITANNIA", "EICHERMOT",
                "HEROMOTOCO", "BAJAJ-AUTO", "INDUSINDBK", "DIVISLAB", "TECHM", "APOLLOHOSP", "TATACONSUM",
                "SBILIFE", "BPCL", "HINDALCO", "UPL", "LTIM", "SHRIRAMFIN"));
        add("BANKNIFTY", "NIFTY BANK", List.of(
                "HDFCBANK", "ICICIBANK", "SBIN", "KOTAKBANK", "AXISBANK", "INDUSINDBK", "BANKBARODA",
                "PNB", "FEDERALBNK", "IDFCFIRSTB", "AUBANK", "BANDHANBNK"));
        add("NIFTYIT", "NIFTY IT", List.of(
                "TCS", "INFY", "HCLTECH", "WIPRO", "TECHM", "LTIM", "PERSISTENT", "COFORGE", "MPHASIS", "LTTS"));
        add("NIFTYAUTO", "NIFTY AUTO", List.of(
                "MARUTI", "TATAMOTORS", "EICHERMOT", "HEROMOTOCO", "BAJAJ-AUTO", "M&M", "TVSMOTOR",
                "ASHOKLEY", "BOSCHLTD", "MOTHERSON"));
        add("NIFTYPHARMA", "NIFTY PHARMA", List.of(
                "SUNPHARMA", "DRREDDY", "CIPLA", "DIVISLAB", "LUPIN", "AUROPHARMA", "TORNTPHARM",
                "ZYDUSLIFE", "ALKEM", "BIOCON"));
        add("NIFTYFMCG", "NIFTY FMCG", List.of(
                "HINDUNILVR", "ITC", "NESTLEIND", "BRITANNIA", "TATACONSUM", "DABUR", "GODREJCP",
                "MARICO", "COLPAL", "UBL"));
    }

    private IndexUniverses() {
    }

    private static void add(String code, String label, List<String> symbols) {
        INDICES.put(code, new IndexDef(code, label, symbols));
    }

    public static List<IndexDef> all() {
        return List.copyOf(INDICES.values());
    }

    /** Look up by code, ignoring case and spaces (so "nifty 50" and "NIFTY50" both work). */
    public static Optional<IndexDef> find(String code) {
        if (code == null || code.isBlank()) {
            return Optional.of(INDICES.get(DEFAULT));
        }
        String key = code.replace(" ", "").toUpperCase();
        return Optional.ofNullable(INDICES.get(key));
    }
}
