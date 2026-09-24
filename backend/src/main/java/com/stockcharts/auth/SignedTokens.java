package com.stockcharts.auth;

import com.stockcharts.settings.SecretService;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Compact HMAC-SHA256 signed tokens: {@code base64url(purpose|expiry|payload).base64url(mac)}.
 * The purpose is part of the signed data, so a token issued for one use (a session) can't be
 * replayed for another (an order confirmation).
 */
@Component
public class SignedTokens {

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private final SecretService secrets;

    public SignedTokens(SecretService secrets) {
        this.secrets = secrets;
    }

    public String sign(String purpose, String payload, Instant expiresAt) {
        String body = purpose + "|" + expiresAt.getEpochSecond() + "|" + payload;
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        return ENC.encodeToString(bodyBytes) + "." + ENC.encodeToString(mac(bodyBytes));
    }

    /** The payload if the token is authentic, for {@code purpose}, and not expired. */
    public Optional<String> verify(String purpose, String token) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        try {
            byte[] body = DEC.decode(token.substring(0, dot));
            byte[] sig = DEC.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(sig, mac(body))) {
                return Optional.empty();
            }
            String[] parts = new String(body, StandardCharsets.UTF_8).split("\\|", 3);
            if (parts.length != 3 || !parts[0].equals(purpose)
                    || Instant.ofEpochSecond(Long.parseLong(parts[1])).isBefore(Instant.now())) {
                return Optional.empty();
            }
            return Optional.of(parts[2]);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private byte[] mac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secrets.key(), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    /** Short fingerprint of a value (e.g. a password hash) for binding tokens to it. */
    public static String fingerprint(String value) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return ENC.encodeToString(d).substring(0, 12);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
