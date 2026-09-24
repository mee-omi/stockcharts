package com.stockcharts.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP-over-WebSocket broker used to push live ticks to the frontend.
 * Clients subscribe to /topic/ticks/{symbol}. SockJS is enabled as a fallback
 * for restrictive mobile networks.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AppProperties props;

    public WebSocketConfig(AppProperties props) {
        this.props = props;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        AppProperties.Cluster cluster = props.getCluster();
        if (cluster.isBrokerRelayEnabled()) {
            // Cluster mode: every replica publishes/subscribes through a shared STOMP broker
            // (e.g. RabbitMQ with the rabbitmq_stomp plugin), so a tick or alert published by one
            // instance reaches browsers connected to any instance.
            registry.enableStompBrokerRelay("/topic")
                    .setRelayHost(cluster.getBrokerRelayHost())
                    .setRelayPort(cluster.getBrokerRelayPort())
                    .setClientLogin(cluster.getBrokerRelayLogin())
                    .setClientPasscode(cluster.getBrokerRelayPasscode())
                    .setSystemLogin(cluster.getBrokerRelayLogin())
                    .setSystemPasscode(cluster.getBrokerRelayPasscode());
        } else {
            registry.enableSimpleBroker("/topic");
        }
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Same-origin by default (guards against cross-site WebSocket hijacking); extra origins
        // come from stockcharts.allowed-origins (e.g. the Vite dev server, which rewrites Host).
        String[] origins = props.getAllowedOrigins().toArray(String[]::new);
        registry.addEndpoint("/ws")
                .setAllowedOrigins(origins)
                .withSockJS();
        // Also expose a raw WebSocket endpoint (no SockJS) for native clients.
        registry.addEndpoint("/ws")
                .setAllowedOrigins(origins);
    }
}
