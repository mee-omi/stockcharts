package com.stockcharts.auth;

import com.stockcharts.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Requires a valid session cookie for the API, the live WebSocket and the API docs. Only the auth
 * endpoints themselves and actuator (not routed by nginx / the ingress) are public. Runs after the
 * rate limiter, so login attempts are throttled before they reach here.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AuthFilter extends OncePerRequestFilter {

    public static final String COOKIE = "sc_session";
    public static final String USER_ATTRIBUTE = "stockcharts.user";

    private final AuthService auth;
    private final AppProperties props;

    public AuthFilter(AuthService auth, AppProperties props) {
        this.auth = auth;
        this.props = props;
    }

    static boolean isProtected(String path) {
        if (path.startsWith("/api/v1/auth/")) {
            return false;
        }
        return path.startsWith("/api/") || path.equals("/ws") || path.startsWith("/ws/")
                || path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !props.getAuth().isEnabled() || !isProtected(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> user = sessionToken(req).flatMap(auth::authenticate);
        if (user.isEmpty()) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"Login required\"}");
            return;
        }
        req.setAttribute(USER_ATTRIBUTE, user.get());
        chain.doFilter(req, res);
    }

    static Optional<String> sessionToken(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie c : cookies) {
            if (COOKIE.equals(c.getName())) {
                return Optional.of(c.getValue());
            }
        }
        return Optional.empty();
    }
}
