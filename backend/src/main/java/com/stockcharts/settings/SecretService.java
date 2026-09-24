package com.stockcharts.settings;

import com.stockcharts.config.AppProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * The HMAC key for session and order-confirmation tokens: {@code stockcharts.auth.secret} if set,
 * otherwise a random key generated once and stored in {@code app_setting} (so every replica and
 * every restart signs with the same key).
 */
@Service
public class SecretService {

    static final String KEY = "auth.secret";

    private final AppSettingRepository settings;
    private final AppProperties props;
    private volatile byte[] cached;

    public SecretService(AppSettingRepository settings, AppProperties props) {
        this.settings = settings;
        this.props = props;
    }

    @Transactional
    public byte[] key() {
        byte[] k = cached;
        if (k != null) {
            return k;
        }
        synchronized (this) {
            if (cached != null) {
                return cached;
            }
            String configured = props.getAuth().getSecret();
            if (configured != null && configured.length() >= 32) {
                cached = configured.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                return cached;
            }
            AppSetting s = settings.findById(KEY).orElseGet(() -> {
                byte[] random = new byte[32];
                new SecureRandom().nextBytes(random);
                return settings.save(new AppSetting(KEY, Base64.getEncoder().encodeToString(random)));
            });
            cached = Base64.getDecoder().decode(s.getValue());
            return cached;
        }
    }
}
