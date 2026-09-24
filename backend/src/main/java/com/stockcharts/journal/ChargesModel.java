package com.stockcharts.journal;

import java.util.List;

/**
 * Estimated statutory and brokerage charges for NSE equity trades, per leg. Rates follow the
 * published schedule for discount brokers (FY 2024-25) and are approximations: DP charges,
 * rounding rules and broker-specific plans are not modelled.
 */
public final class ChargesModel {

    private ChargesModel() {
    }

    /** Intraday brokerage: the lower of ₹20 or 0.03% per executed leg; delivery is free. */
    static final double INTRADAY_BROKERAGE_PCT = 0.0003;
    static final double INTRADAY_BROKERAGE_CAP = 20.0;
    static final double STT_DELIVERY = 0.001;          // buy and sell
    static final double STT_INTRADAY_SELL = 0.00025;   // sell side only
    static final double EXCHANGE_TXN = 0.0000297;      // NSE, on turnover
    static final double SEBI = 0.000001;               // ₹10 per crore
    static final double STAMP_DELIVERY_BUY = 0.00015;
    static final double STAMP_INTRADAY_BUY = 0.00003;
    static final double GST = 0.18;                    // on brokerage + exchange + SEBI

    public static final List<String> ASSUMPTIONS = List.of(
            "Charges: discount-broker model — delivery brokerage ₹0, intraday min(₹20, 0.03%) per leg",
            "STT 0.1% on delivery buy+sell, 0.025% on intraday sell; NSE txn 0.00297%; SEBI ₹10/crore",
            "Stamp duty 0.015% (delivery) / 0.003% (intraday) on buys; GST 18% on brokerage+txn+SEBI",
            "DP charges on delivery sells and broker-specific plans are not included");

    /** Charges for one leg of {@code value} rupees. */
    public static double leg(double value, boolean buy, boolean intraday) {
        double brokerage = intraday ? Math.min(INTRADAY_BROKERAGE_CAP, value * INTRADAY_BROKERAGE_PCT) : 0;
        double stt = intraday ? (buy ? 0 : value * STT_INTRADAY_SELL) : value * STT_DELIVERY;
        double txn = value * EXCHANGE_TXN;
        double sebi = value * SEBI;
        double stamp = buy ? value * (intraday ? STAMP_INTRADAY_BUY : STAMP_DELIVERY_BUY) : 0;
        double gst = (brokerage + txn + sebi) * GST;
        return brokerage + stt + txn + sebi + stamp + gst;
    }
}
