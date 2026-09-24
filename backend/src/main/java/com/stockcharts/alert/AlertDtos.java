package com.stockcharts.alert;

/** Request/response shapes for the alerts API. Times are epoch milliseconds (UTC). */
public final class AlertDtos {

    private AlertDtos() {
    }

    public record AlertRequest(String name, String symbol, String timeframe, String expression,
                               Alert.TriggerMode triggerMode) {
    }

    public record AlertDto(long id, String name, String symbol, String timeframe, String expression,
                           Alert.TriggerMode triggerMode, Alert.Status status, long createdAt,
                           Long lastTriggeredAt, int triggerCount) {

        static AlertDto from(Alert a) {
            return new AlertDto(a.getId(), a.getName(), a.getSymbol(), a.getTimeframe(), a.getExpression(),
                    a.getTriggerMode(), a.getStatus(), a.getCreatedAt().toEpochMilli(),
                    a.getLastTriggeredAt() == null ? null : a.getLastTriggeredAt().toEpochMilli(),
                    a.getTriggerCount());
        }
    }

    public record AlertEventDto(long id, long alertId, String alertName, String symbol, String expression,
                                double price, long barTime, long triggeredAt) {

        static AlertEventDto from(AlertEvent e) {
            return new AlertEventDto(e.getId(), e.getAlertId(), e.getAlertName(), e.getSymbol(),
                    e.getExpression(), e.getPrice(), e.getBarTime().toEpochMilli(),
                    e.getTriggeredAt().toEpochMilli());
        }
    }
}
