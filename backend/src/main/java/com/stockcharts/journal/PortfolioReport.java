package com.stockcharts.journal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Realised P&L from journalled fills using FIFO lot matching per symbol, with estimated charges,
 * Indian capital-gains classification and a per-financial-year tax estimate.
 *
 * <p>Classification of a matched (open, close) pair: INTRADAY when both legs fall on the same IST
 * date or either leg is MIS; otherwise LTCG for a long lot held more than 12 months, else STCG.
 * These are estimates for record keeping, not tax advice.
 */
public final class PortfolioReport {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final double STCG_RATE = 0.20;
    static final double LTCG_RATE = 0.125;
    static final double LTCG_EXEMPTION = 125_000;
    static final double CESS = 0.04;

    public enum Category { INTRADAY, STCG, LTCG }

    public record RealizedTrade(String symbol, String side, long quantity, long openTime, double openPrice,
                                long closeTime, double closePrice, double grossPnl, double charges,
                                double netPnl, long holdingDays, Category category) {
    }

    public record Bucket(String key, int trades, int wins, double turnover, double grossPnl, double charges,
                         double netPnl) {
    }

    public record FinancialYear(String year, double intradayNetPnl, double stcg, double ltcg,
                                double taxableStcg, double taxableLtcg, double estimatedStcgTax,
                                double estimatedLtcgTax) {
    }

    public record DailyPnl(String date, double netPnl, double cumulative) {
    }

    public record OpenLot(String symbol, long quantity, double averagePrice, long since) {
    }

    public record Summary(int trades, int wins, int losses, double winRatePct, double turnover,
                          double grossPnl, double charges, double netPnl) {
    }

    public record Report(Summary summary, List<Bucket> byCategory, List<Bucket> bySymbol,
                         List<FinancialYear> byFinancialYear, List<DailyPnl> daily,
                         List<RealizedTrade> realized, List<OpenLot> openLots, List<String> assumptions) {
    }

    private PortfolioReport() {
    }

    private static final class Lot {
        final JournalEntry.Side side;
        final double price;
        final Instant at;
        final String product;
        long qty;

        Lot(JournalEntry.Side side, long qty, double price, Instant at, String product) {
            this.side = side;
            this.qty = qty;
            this.price = price;
            this.at = at;
            this.product = product;
        }
    }

    /**
     * @param entries all fills (any order); matching always uses the full history
     * @param from    optional: only report trades closed at/after this instant
     * @param to      optional: only report trades closed at/before this instant
     */
    public static Report build(List<JournalEntry> entries, Instant from, Instant to) {
        List<JournalEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(JournalEntry::getExecutedAt)
                .thenComparing(e -> e.getId() == null ? 0L : e.getId()));

        Map<String, Deque<Lot>> books = new TreeMap<>();
        List<RealizedTrade> realized = new ArrayList<>();
        for (JournalEntry e : sorted) {
            Deque<Lot> book = books.computeIfAbsent(e.getSymbol(), k -> new ArrayDeque<>());
            long remaining = e.getQuantity();
            while (remaining > 0 && !book.isEmpty() && book.peekFirst().side != e.getSide()) {
                Lot lot = book.peekFirst();
                long q = Math.min(lot.qty, remaining);
                realized.add(pair(e.getSymbol(), lot, e, q));
                lot.qty -= q;
                remaining -= q;
                if (lot.qty == 0) {
                    book.pollFirst();
                }
            }
            if (remaining > 0) {
                book.addLast(new Lot(e.getSide(), remaining, e.getPrice(), e.getExecutedAt(), e.getProduct()));
            }
        }

        List<RealizedTrade> inRange = realized.stream()
                .filter(t -> from == null || t.closeTime() >= from.toEpochMilli())
                .filter(t -> to == null || t.closeTime() <= to.toEpochMilli())
                .toList();

        return new Report(summary(inRange), buckets(inRange, true), buckets(inRange, false),
                financialYears(inRange), daily(inRange), inRange, openLots(books), assumptions());
    }

    private static RealizedTrade pair(String symbol, Lot open, JournalEntry close, long qty) {
        boolean isLong = open.side == JournalEntry.Side.BUY;
        double buyPx = isLong ? open.price : close.getPrice();
        double sellPx = isLong ? close.getPrice() : open.price;
        LocalDate openDay = open.at.atZone(IST).toLocalDate();
        LocalDate closeDay = close.getExecutedAt().atZone(IST).toLocalDate();
        boolean intraday = openDay.equals(closeDay)
                || "MIS".equalsIgnoreCase(open.product) || "MIS".equalsIgnoreCase(close.getProduct());
        Category cat;
        if (intraday) {
            cat = Category.INTRADAY;
        } else if (isLong && closeDay.isAfter(openDay.plusYears(1))) {
            cat = Category.LTCG;
        } else {
            cat = Category.STCG;
        }
        double gross = (sellPx - buyPx) * qty;
        double charges = ChargesModel.leg(buyPx * qty, true, intraday) + ChargesModel.leg(sellPx * qty, false, intraday);
        long days = java.time.temporal.ChronoUnit.DAYS.between(openDay, closeDay);
        return new RealizedTrade(symbol, isLong ? "LONG" : "SHORT", qty, open.at.toEpochMilli(), open.price,
                close.getExecutedAt().toEpochMilli(), close.getPrice(), round(gross), round(charges),
                round(gross - charges), days, cat);
    }

    private static Summary summary(List<RealizedTrade> trades) {
        int wins = 0;
        double turnover = 0, gross = 0, charges = 0;
        for (RealizedTrade t : trades) {
            if (t.netPnl() > 0) {
                wins++;
            }
            turnover += (t.openPrice() + t.closePrice()) * t.quantity();
            gross += t.grossPnl();
            charges += t.charges();
        }
        int n = trades.size();
        return new Summary(n, wins, n - wins, n > 0 ? round(wins * 100.0 / n) : 0, round(turnover),
                round(gross), round(charges), round(gross - charges));
    }

    private static List<Bucket> buckets(List<RealizedTrade> trades, boolean byCategory) {
        Map<String, List<RealizedTrade>> groups = new TreeMap<>();
        for (RealizedTrade t : trades) {
            groups.computeIfAbsent(byCategory ? t.category().name() : t.symbol(), k -> new ArrayList<>()).add(t);
        }
        List<Bucket> out = new ArrayList<>();
        groups.forEach((k, list) -> {
            Summary s = summary(list);
            out.add(new Bucket(k, s.trades(), s.wins(), s.turnover(), s.grossPnl(), s.charges(), s.netPnl()));
        });
        return out;
    }

    static String financialYear(long epochMs) {
        LocalDate d = Instant.ofEpochMilli(epochMs).atZone(IST).toLocalDate();
        int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return "FY" + start + "-" + String.format("%02d", (start + 1) % 100);
    }

    private static List<FinancialYear> financialYears(List<RealizedTrade> trades) {
        Map<String, double[]> fy = new TreeMap<>(); // [intradayNet, stcg, ltcg]
        for (RealizedTrade t : trades) {
            double[] acc = fy.computeIfAbsent(financialYear(t.closeTime()), k -> new double[3]);
            switch (t.category()) {
                case INTRADAY -> acc[0] += t.netPnl();
                case STCG -> acc[1] += t.grossPnl();
                case LTCG -> acc[2] += t.grossPnl();
            }
        }
        List<FinancialYear> out = new ArrayList<>();
        fy.forEach((year, a) -> {
            double st = a[1], lt = a[2];
            // Short-term losses may be set off against long-term gains; long-term losses only
            // against long-term gains. Carry-forward across years is not modelled.
            double taxSt = st, taxLt = lt;
            if (taxSt < 0) {
                taxLt += taxSt;
                taxSt = 0;
            }
            taxLt = Math.max(0, taxLt);
            double stTax = taxSt * STCG_RATE * (1 + CESS);
            double ltTax = Math.max(0, taxLt - LTCG_EXEMPTION) * LTCG_RATE * (1 + CESS);
            out.add(new FinancialYear(year, round(a[0]), round(st), round(lt), round(taxSt), round(taxLt),
                    round(stTax), round(ltTax)));
        });
        return out;
    }

    private static List<DailyPnl> daily(List<RealizedTrade> trades) {
        Map<String, Double> byDay = new TreeMap<>();
        for (RealizedTrade t : trades) {
            String day = Instant.ofEpochMilli(t.closeTime()).atZone(IST).toLocalDate().toString();
            byDay.merge(day, t.netPnl(), Double::sum);
        }
        List<DailyPnl> out = new ArrayList<>();
        double cum = 0;
        for (Map.Entry<String, Double> e : byDay.entrySet()) {
            cum += e.getValue();
            out.add(new DailyPnl(e.getKey(), round(e.getValue()), round(cum)));
        }
        return out;
    }

    private static List<OpenLot> openLots(Map<String, Deque<Lot>> books) {
        List<OpenLot> out = new ArrayList<>();
        Map<String, Deque<Lot>> ordered = new LinkedHashMap<>(books);
        ordered.forEach((symbol, book) -> {
            long qty = 0;
            double cost = 0;
            Instant since = null;
            for (Lot l : book) {
                long signed = l.side == JournalEntry.Side.BUY ? l.qty : -l.qty;
                qty += signed;
                cost += l.price * l.qty;
                since = since == null || l.at.isBefore(since) ? l.at : since;
            }
            if (qty != 0) {
                out.add(new OpenLot(symbol, qty, round(cost / Math.abs(qty)), since.toEpochMilli()));
            }
        });
        return out;
    }

    private static List<String> assumptions() {
        List<String> a = new ArrayList<>(ChargesModel.ASSUMPTIONS);
        a.add("Lots are matched FIFO per symbol; intraday = same IST day or MIS product");
        a.add("LTCG = long lot held more than 12 months; STCG taxed at 20%, LTCG at 12.5% above ₹1.25 lakh"
                + " (Budget 2024 rates, plus 4% cess), applied to every year shown");
        a.add("Taxable gains approximated as sale value minus cost; grandfathering, carry-forward and"
                + " intraday (speculative, slab-rated) tax are not computed. Not tax advice.");
        return a;
    }

    /** CSV of the realised trades (tax-report friendly). */
    public static String toCsv(Report report) {
        StringBuilder sb = new StringBuilder(
                "symbol,side,quantity,open_time_ist,open_price,close_time_ist,close_price,holding_days,"
                        + "category,financial_year,gross_pnl,charges,net_pnl\n");
        for (RealizedTrade t : report.realized()) {
            sb.append(csv(t.symbol())).append(',').append(t.side()).append(',').append(t.quantity()).append(',')
                    .append(Instant.ofEpochMilli(t.openTime()).atZone(IST).toLocalDateTime()).append(',')
                    .append(t.openPrice()).append(',')
                    .append(Instant.ofEpochMilli(t.closeTime()).atZone(IST).toLocalDateTime()).append(',')
                    .append(t.closePrice()).append(',').append(t.holdingDays()).append(',')
                    .append(t.category()).append(',').append(financialYear(t.closeTime())).append(',')
                    .append(t.grossPnl()).append(',').append(t.charges()).append(',').append(t.netPnl())
                    .append('\n');
        }
        return sb.toString();
    }

    private static String csv(String s) {
        // Neutralise spreadsheet formula injection and quote when needed.
        String v = s;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        return v.contains(",") || v.contains("\"") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
