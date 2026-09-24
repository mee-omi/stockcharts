package com.stockcharts.risk;

/**
 * Server-enforced order limits. Null limits are off.
 *
 * @param killSwitch          block every order that doesn't reduce an open position
 * @param maxQuantityPerOrder shares per order
 * @param maxOrderValue       rupees per order (price × quantity; market orders use the last price)
 * @param maxOrdersPerDay     orders placed today at the broker
 * @param maxDailyLoss        rupees; once today's realised + open P&L is below −this, only
 *                            position-reducing orders are allowed
 * @param requireConfirmation live brokers need a preview + confirmation token for each order
 * @param applyToPaper        also apply the limits to the paper (mock) broker
 */
public record RiskSettings(boolean killSwitch, Integer maxQuantityPerOrder, Double maxOrderValue,
                           Integer maxOrdersPerDay, Double maxDailyLoss, boolean requireConfirmation,
                           boolean applyToPaper) {

    public static RiskSettings defaults() {
        return new RiskSettings(false, 1_000, 200_000.0, 50, 10_000.0, true, true);
    }

    RiskSettings withKillSwitch(boolean on) {
        return new RiskSettings(on, maxQuantityPerOrder, maxOrderValue, maxOrdersPerDay, maxDailyLoss,
                requireConfirmation, applyToPaper);
    }
}
