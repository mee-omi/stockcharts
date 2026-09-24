package com.stockcharts.journal;

import com.stockcharts.journal.PortfolioReport.Category;
import com.stockcharts.journal.PortfolioReport.RealizedTrade;
import com.stockcharts.journal.PortfolioReport.Report;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortfolioReportTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static JournalEntry fill(String symbol, JournalEntry.Side side, String product, int qty, double price,
                                     LocalDateTime at) {
        return new JournalEntry(null, "mock", symbol, "NSE", side, product, qty, price,
                at.atZone(IST).toInstant(), null, JournalEntry.Source.MANUAL);
    }

    @Test
    void fifoMatchingSplitsLotsAndClassifiesHoldingPeriods() {
        LocalDateTime d0 = LocalDateTime.of(2023, 4, 10, 10, 0);
        Report r = PortfolioReport.build(List.of(
                fill("INFY", JournalEntry.Side.BUY, "CNC", 10, 100, d0),
                fill("INFY", JournalEntry.Side.BUY, "CNC", 5, 110, d0.plusDays(100)),
                fill("INFY", JournalEntry.Side.SELL, "CNC", 12, 120, d0.plusDays(400))), null, null);

        assertEquals(2, r.realized().size());
        RealizedTrade first = r.realized().get(0);
        assertEquals(10, first.quantity());
        assertEquals(Category.LTCG, first.category());
        assertEquals(200, first.grossPnl(), 1e-9);

        RealizedTrade second = r.realized().get(1);
        assertEquals(2, second.quantity());
        assertEquals(Category.STCG, second.category());
        assertEquals(20, second.grossPnl(), 1e-9);

        assertEquals(1, r.openLots().size());
        assertEquals(3, r.openLots().get(0).quantity());
        assertEquals(110, r.openLots().get(0).averagePrice(), 1e-9);
    }

    @Test
    void sameDayAndMisTradesAreIntradayIncludingShorts() {
        LocalDateTime d = LocalDateTime.of(2025, 1, 15, 9, 30);
        Report r = PortfolioReport.build(List.of(
                fill("SBIN", JournalEntry.Side.SELL, "MIS", 5, 200, d),
                fill("SBIN", JournalEntry.Side.BUY, "MIS", 5, 190, d.plusHours(3)),
                fill("TCS", JournalEntry.Side.BUY, "CNC", 1, 3000, d),
                fill("TCS", JournalEntry.Side.SELL, "CNC", 1, 3010, d.plusHours(1))), null, null);

        RealizedTrade shortTrade = r.realized().stream().filter(t -> t.symbol().equals("SBIN")).findFirst().orElseThrow();
        assertEquals("SHORT", shortTrade.side());
        assertEquals(Category.INTRADAY, shortTrade.category());
        assertEquals(50, shortTrade.grossPnl(), 1e-9);
        assertTrue(shortTrade.charges() > 0);
        assertEquals(shortTrade.grossPnl() - shortTrade.charges(), shortTrade.netPnl(), 0.011);

        RealizedTrade sameDayCnc = r.realized().stream().filter(t -> t.symbol().equals("TCS")).findFirst().orElseThrow();
        assertEquals(Category.INTRADAY, sameDayCnc.category());
        assertTrue(r.openLots().isEmpty());
    }

    @Test
    void financialYearBoundaryIsFirstOfApril() {
        long mar31 = LocalDateTime.of(2025, 3, 31, 23, 0).atZone(IST).toInstant().toEpochMilli();
        long apr1 = LocalDateTime.of(2025, 4, 1, 0, 30).atZone(IST).toInstant().toEpochMilli();
        assertEquals("FY2024-25", PortfolioReport.financialYear(mar31));
        assertEquals("FY2025-26", PortfolioReport.financialYear(apr1));
    }

    @Test
    void shortTermLossesOffsetLongTermGainsBeforeExemption() {
        LocalDateTime d0 = LocalDateTime.of(2023, 5, 1, 10, 0);
        Report r = PortfolioReport.build(List.of(
                // LTCG +200,000
                fill("LT", JournalEntry.Side.BUY, "CNC", 100, 1000, d0),
                fill("LT", JournalEntry.Side.SELL, "CNC", 100, 3000, d0.plusDays(400)),
                // STCG -1,000
                fill("ITC", JournalEntry.Side.BUY, "CNC", 100, 400, d0.plusDays(300)),
                fill("ITC", JournalEntry.Side.SELL, "CNC", 100, 390, d0.plusDays(390))), null, null);

        assertEquals(1, r.byFinancialYear().size());
        PortfolioReport.FinancialYear fy = r.byFinancialYear().get(0);
        assertEquals(-1000, fy.stcg(), 1e-9);
        assertEquals(200_000, fy.ltcg(), 1e-9);
        assertEquals(0, fy.taxableStcg(), 1e-9);
        assertEquals(199_000, fy.taxableLtcg(), 1e-9);
        assertEquals((199_000 - 125_000) * 0.125 * 1.04, fy.estimatedLtcgTax(), 0.01);
    }

    @Test
    void dateFilterAppliesToCloseTimeButMatchingUsesFullHistory() {
        LocalDateTime d0 = LocalDateTime.of(2024, 1, 1, 10, 0);
        List<JournalEntry> fills = List.of(
                fill("HDFCBANK", JournalEntry.Side.BUY, "CNC", 10, 100, d0),
                fill("HDFCBANK", JournalEntry.Side.SELL, "CNC", 10, 150, d0.plusDays(30)));
        var from = d0.plusDays(10).atZone(IST).toInstant();
        Report r = PortfolioReport.build(fills, from, null);
        assertEquals(1, r.realized().size());
        assertEquals(500, r.summary().grossPnl(), 1e-9);
        assertEquals(1, r.daily().size());
    }

    @Test
    void csvNeutralisesFormulaInjection() {
        LocalDateTime d = LocalDateTime.of(2025, 1, 15, 9, 30);
        Report r = PortfolioReport.build(List.of(
                fill("=CMD", JournalEntry.Side.BUY, "MIS", 1, 10, d),
                fill("=CMD", JournalEntry.Side.SELL, "MIS", 1, 11, d.plusMinutes(5))), null, null);
        String csv = PortfolioReport.toCsv(r);
        assertTrue(csv.contains("\n'=CMD,"), csv);
    }
}
