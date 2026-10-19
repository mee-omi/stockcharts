package com.stockcharts.alert;

import com.stockcharts.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Delivers triggered alerts: always to connected browsers over STOMP ({@code /topic/alerts}),
 * and optionally to Telegram and/or a generic JSON webhook. External deliveries are
 * fire-and-forget so a slow endpoint never stalls alert evaluation.
 */
@Component
public class AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(AlertNotifier.class);
    public static final String TOPIC = "/topic/alerts";
    private static final DateTimeFormatter IST =
            DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(ZoneId.of("Asia/Kolkata"));

    private final SimpMessagingTemplate messaging;
    private final AppProperties.Alerts config;
    private final WebClient http;

    public AlertNotifier(SimpMessagingTemplate messaging, AppProperties props, WebClient.Builder builder) {
        this.messaging = messaging;
        this.config = props.getAlerts();
        this.http = builder.build();
    }

    public void notify(AlertDtos.AlertEventDto event) {
        deliver(TOPIC, event, message(event), event);
    }

    /**
     * Push {@code payload} to STOMP {@code topic} and, when configured, send {@code text} to
     * Telegram and {@code webhookPayload} to the webhook.
     */
    public void deliver(String topic, Object payload, String text, Object webhookPayload) {
        messaging.convertAndSend(topic, payload);
        sendTelegram(text);
        sendWebhook(webhookPayload);
    }

    static String message(AlertDtos.AlertEventDto e) {
        return "🔔 " + e.alertName() + "\n" + e.symbol() + " @ " + e.price()
                + "\nCondition: " + e.expression()
                + "\nBar: " + IST.format(Instant.ofEpochMilli(e.barTime())) + " IST";
    }

    private void sendTelegram(String text) {
        String token = config.getTelegramBotToken();
        String chat = config.getTelegramChatId();
        if (isBlank(token) || isBlank(chat)) {
            return;
        }
        http.post()
                .uri("https://api.telegram.org/bot{token}/sendMessage", token)
                .bodyValue(Map.of("chat_id", chat, "text", text))
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(10))
                // Never log the URI: it contains the bot token.
                .subscribe(r -> { }, e -> log.warn("Telegram alert delivery failed: {}", e.getClass().getSimpleName()));
    }

    private void sendWebhook(Object payload) {
        String url = config.getWebhookUrl();
        if (isBlank(url)) {
            return;
        }
        http.post()
                .uri(url)
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(10))
                .subscribe(r -> { }, e -> log.warn("Alert webhook delivery failed: {}", e.getMessage()));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
