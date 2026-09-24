package com.stockcharts.auth;

import com.stockcharts.config.AppProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Login, logout and first-run account setup")
public class AuthController {

    private final AuthService auth;
    private final AppProperties props;

    public AuthController(AuthService auth, AppProperties props) {
        this.auth = auth;
        this.props = props;
    }

    public record Credentials(String username, String password) {
    }

    public record PasswordChange(String currentPassword, String newPassword) {
    }

    public record Status(boolean authEnabled, boolean setupRequired, boolean authenticated, String username) {
    }

    @GetMapping("/status")
    @Operation(summary = "Whether login is enabled, first-run setup is needed, and who is logged in")
    public Status status(HttpServletRequest req) {
        if (!props.getAuth().isEnabled()) {
            return new Status(false, false, true, null);
        }
        Optional<String> user = AuthFilter.sessionToken(req).flatMap(auth::authenticate);
        return new Status(true, auth.setupRequired(), user.isPresent(), user.orElse(null));
    }

    @PostMapping("/setup")
    @Operation(summary = "Create the first account (only while no account exists) and log in")
    public ResponseEntity<Status> setup(@RequestBody Credentials c, HttpServletRequest req) {
        if (!auth.setupRequired()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new Status(true, false, false, null));
        }
        AuthService.Session s = auth.setup(c.username(), c.password());
        return withCookie(s, req);
    }

    @PostMapping("/login")
    @Operation(summary = "Log in; sets an HttpOnly session cookie")
    public ResponseEntity<?> login(@RequestBody Credentials c, HttpServletRequest req) {
        return auth.login(c.username(), c.password())
                .<ResponseEntity<?>>map(s -> withCookie(s, req))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid username or password")));
    }

    @PostMapping("/logout")
    @Operation(summary = "Log out (clears the session cookie)")
    public ResponseEntity<Void> logout(HttpServletRequest req, HttpServletResponse res) {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO, req).toString()).build();
    }

    @PostMapping("/password")
    @Operation(summary = "Change password; other sessions are logged out")
    public ResponseEntity<Status> changePassword(@RequestBody PasswordChange body, HttpServletRequest req) {
        String user = AuthFilter.sessionToken(req).flatMap(auth::authenticate)
                .orElseThrow(() -> new IllegalArgumentException("Login required"));
        return withCookie(auth.changePassword(user, body.currentPassword(), body.newPassword()), req);
    }

    private ResponseEntity<Status> withCookie(AuthService.Session s, HttpServletRequest req) {
        Duration ttl = Duration.between(Instant.now(), s.expiresAt());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(s.token(), ttl, req).toString())
                .body(new Status(true, false, true, s.username()));
    }

    private ResponseCookie cookie(String value, Duration maxAge, HttpServletRequest req) {
        boolean secure = props.getAuth().isCookieSecure() || req.isSecure()
                || "https".equalsIgnoreCase(req.getHeader("X-Forwarded-Proto"));
        return ResponseCookie.from(AuthFilter.COOKIE, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
