package com.stockcharts.live;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

/** Starts streaming a symbol as soon as any client subscribes to {@code /topic/ticks/{symbol}}. */
@Component
public class LiveSubscriptionListener {

    static final String PREFIX = "/topic/ticks/";

    private final LiveDataService live;

    public LiveSubscriptionListener(LiveDataService live) {
        this.live = live;
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        String destination = SimpMessageHeaderAccessor.wrap(event.getMessage()).getDestination();
        if (destination != null && destination.startsWith(PREFIX)) {
            live.request(destination.substring(PREFIX.length()));
        }
    }
}
