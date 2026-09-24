package com.stockcharts.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitFilterTest {

    @Test
    void tiersAreAssignedByMethodAndPath() {
        assertEquals(RateLimitFilter.Tier.TRADING, RateLimitFilter.tier("POST", "/api/v1/trading/orders"));
        assertEquals(RateLimitFilter.Tier.TRADING, RateLimitFilter.tier("POST", "/api/v1/broker"));
        assertEquals(RateLimitFilter.Tier.DEFAULT, RateLimitFilter.tier("GET", "/api/v1/trading/orders"));
        assertEquals(RateLimitFilter.Tier.HEAVY, RateLimitFilter.tier("POST", "/api/v1/screener/run"));
        assertEquals(RateLimitFilter.Tier.HEAVY, RateLimitFilter.tier("POST", "/api/v1/screener/mine/run"));
        assertEquals(RateLimitFilter.Tier.HEAVY, RateLimitFilter.tier("POST", "/api/v1/backtest"));
        assertEquals(RateLimitFilter.Tier.DEFAULT, RateLimitFilter.tier("POST", "/api/v1/screener"));
    }

    @Test
    void bucketRefillsOverTime() {
        RateLimitFilter.Bucket b = new RateLimitFilter.Bucket(60, 0); // 1 token / second
        for (int i = 0; i < 60; i++) {
            assertEquals(0, b.tryTake(0));
        }
        long wait = b.tryTake(0);
        assertTrue(wait > 0 && wait <= 1000, "wait=" + wait);
        assertEquals(0, b.tryTake(1000));
    }

    @Test
    void overLimitRequestsGet429WithRetryAfter() throws Exception {
        AppProperties props = new AppProperties();
        props.getRateLimit().setTradingPerMinute(2);
        RateLimitFilter filter = new RateLimitFilter(props);

        int[] statuses = new int[3];
        MockHttpServletResponse last = null;
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/trading/orders");
            req.setRemoteAddr("10.0.0.1");
            last = new MockHttpServletResponse();
            filter.doFilter(req, last, new MockFilterChain());
            statuses[i] = last.getStatus();
        }
        assertEquals(200, statuses[0]);
        assertEquals(200, statuses[1]);
        assertEquals(429, statuses[2]);
        assertNotNull(last.getHeader("Retry-After"));

        // A different client has its own bucket.
        MockHttpServletRequest other = new MockHttpServletRequest("POST", "/api/v1/trading/orders");
        other.setRemoteAddr("10.0.0.2");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(other, res, new MockFilterChain());
        assertEquals(200, res.getStatus());
    }

    @Test
    void forwardedForIsOnlyTrustedWhenEnabled() {
        AppProperties props = new AppProperties();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/instruments");
        req.setRemoteAddr("172.18.0.5");
        req.addHeader("X-Forwarded-For", "203.0.113.9, 172.18.0.5");
        assertEquals("172.18.0.5", new RateLimitFilter(props).clientId(req));
        props.getRateLimit().setTrustForwardedFor(true);
        assertEquals("203.0.113.9", new RateLimitFilter(props).clientId(req));
    }
}
