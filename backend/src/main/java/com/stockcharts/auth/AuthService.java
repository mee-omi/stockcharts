package com.stockcharts.auth;

import com.stockcharts.config.AppProperties;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Accounts and sessions. The first account is created through a one-time setup step (allowed only
 * while no account exists). Sessions are signed tokens bound to the password hash, so changing the
 * password logs out every other session.
 */
@Service
public class AuthService {

    static final String SESSION = "session";
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,64}");
    static final int MIN_PASSWORD = 10;

    private final AppUserRepository users;
    private final SignedTokens tokens;
    private final AppProperties props;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    // Compared against when the user doesn't exist, so timing doesn't reveal valid usernames.
    private final String dummyHash = encoder.encode("timing-equaliser-not-a-password");

    public AuthService(AppUserRepository users, SignedTokens tokens, AppProperties props) {
        this.users = users;
        this.tokens = tokens;
        this.props = props;
    }

    public record Session(String username, String token, Instant expiresAt) {
    }

    public boolean setupRequired() {
        return users.count() == 0;
    }

    @Transactional
    public Session setup(String username, String password) {
        if (!setupRequired()) {
            throw new IllegalStateException("Setup already completed");
        }
        validate(username, password);
        AppUser user = users.save(new AppUser(username.trim(), encoder.encode(password)));
        return issue(user);
    }

    @Transactional
    public Optional<Session> login(String username, String password) {
        if (username == null || password == null) {
            return Optional.empty();
        }
        Optional<AppUser> user = users.findByUsernameIgnoreCase(username.trim());
        boolean ok = encoder.matches(password, user.map(AppUser::getPasswordHash).orElse(dummyHash));
        if (!ok || user.isEmpty()) {
            return Optional.empty();
        }
        user.get().setLastLoginAt(Instant.now());
        return Optional.of(issue(users.save(user.get())));
    }

    @Transactional
    public Session changePassword(String username, String current, String next) {
        AppUser user = users.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
        if (!encoder.matches(current == null ? "" : current, user.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        validate(username, next);
        user.setPasswordHash(encoder.encode(next));
        return issue(users.save(user));
    }

    /** The username for a valid, unexpired session token whose password hasn't changed since. */
    public Optional<String> authenticate(String token) {
        return tokens.verify(SESSION, token).flatMap(payload -> {
            int sep = payload.lastIndexOf(':');
            if (sep <= 0) {
                return Optional.empty();
            }
            String username = payload.substring(0, sep);
            String fp = payload.substring(sep + 1);
            return users.findByUsernameIgnoreCase(username)
                    .filter(u -> SignedTokens.fingerprint(u.getPasswordHash()).equals(fp))
                    .map(AppUser::getUsername);
        });
    }

    private Session issue(AppUser user) {
        Instant exp = Instant.now().plus(props.getAuth().getSessionTtl());
        String payload = user.getUsername() + ":" + SignedTokens.fingerprint(user.getPasswordHash());
        return new Session(user.getUsername(), tokens.sign(SESSION, payload, exp), exp);
    }

    private static void validate(String username, String password) {
        if (username == null || !USERNAME.matcher(username.trim()).matches()) {
            throw new IllegalArgumentException("Username: 3-64 letters, digits, dot, dash or underscore");
        }
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new IllegalArgumentException("Password must be at least " + MIN_PASSWORD + " characters");
        }
        if (password.length() > 72) {
            throw new IllegalArgumentException("Password must be at most 72 characters");
        }
    }
}
