package com.stockcharts.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-client token buckets for {@code /api/**}. Three tiers: trading (orders, broker switching),
 * heavy (screener runs, backtests, alert evaluation) and everything else. Over-limit requests get
 * HTTP 429 with {@code Retry-After}. State is per instance, which is adequate protection against a
 * runaway client; a shared limiter (e.g. at the ingress) is the cluster-wide answer.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    enum Tier { DEFAULT, TRADING, HEAVY, AUTH }

    private final AppProperties.RateLimit config;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(AppProperties props) {
        this.config = props.getRateLimit();
    }

    /** Classic token bucket refilled continuously at capacity-per-minute. */
    static final class Bucket {
        private final double capacity;
        private final double refillPerMs;
        private double tokens;
        private long last;
        volatile long lastSeen;

        Bucket(int perMinute, long now) {
            this.capacity = perMinute;
            this.refillPerMs = perMinute / 60_000.0;
            this.tokens = perMinute;
            this.last = now;
            this.lastSeen = now;
        }

        /** Returns 0 if a token was taken, else the milliseconds until one is available. */
        synchronized long tryTake(long now) {
            tokens = Math.min(capacity, tokens + (now - last) * refillPerMs);
            last = now;
            lastSeen = now;
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return (long) Math.ceil((1 - tokens) / refillPerMs);
        }
    }

    static Tier tier(String method, String path) {
        boolean write = !"GET".equalsIgnoreCase(method);
        if (write && (path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/setup")
                || path.equals("/api/v1/auth/password"))) {
            return Tier.AUTH;
        }
        if (write && (path.startsWith("/api/v1/trading/") || path.equals("/api/v1/broker")
                || path.equals("/api/v1/datasource"))) {
            return Tier.TRADING;
        }
        if (write && (path.startsWith("/api/v1/screener/") && path.endsWith("/run")
                || path.startsWith("/api/v1/backtest") || path.equals("/api/v1/alerts/evaluate")
                || path.equals("/api/v1/history-sync/run"))) {
            return Tier.HEAVY;
        }
        return Tier.DEFAULT;
    }

    private int limit(Tier t) {
        return switch (t) {
            case TRADING -> config.getTradingPerMinute();
            case HEAVY -> config.getHeavyPerMinute();
            case AUTH -> config.getAuthPerMinute();
            default -> config.getRequestsPerMinute();
        };
    }

    String clientId(HttpServletRequest req) {
        if (config.isTrustForwardedFor()) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return req.getRemoteAddr();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !config.isEnabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Tier tier = tier(req.getMethod(), req.getRequestURI());
        int perMinute = limit(tier);
        long now = System.currentTimeMillis();
        Bucket bucket = buckets.computeIfAbsent(clientId(req) + "|" + tier, k -> new Bucket(perMinute, now));
        long waitMs = bucket.tryTake(now);
        if (waitMs > 0) {
            res.setStatus(429);
            res.setHeader("Retry-After", String.valueOf(Math.max(1, (waitMs + 999) / 1000)));
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"Too many requests — slow down and retry shortly\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    /** Drop idle buckets so the map cannot grow without bound. */
    @Scheduled(fixedDelay = 300_000)
    public void evictIdle() {
        long cutoff = System.currentTimeMillis() - 600_000;
        buckets.values().removeIf(b -> b.lastSeen < cutoff);
    }
}
