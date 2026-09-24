package com.stockcharts.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * In-process caches for hot read paths (instrument search as the user types, symbol existence
 * checks on every chart load). Short TTLs keep them safe even without explicit eviction; the
 * instrument sync also clears them.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String INSTRUMENT_SEARCH = "instrumentSearch";
    public static final String INSTRUMENT_EXISTS = "instrumentExists";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(INSTRUMENT_SEARCH, INSTRUMENT_EXISTS);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .maximumSize(10_000)
                .recordStats());
        return manager;
    }
}
