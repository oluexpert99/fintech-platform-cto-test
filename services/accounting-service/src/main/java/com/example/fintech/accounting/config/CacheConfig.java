package com.example.fintech.accounting.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cache wiring for the trial-balance projection.
 *
 * <p>Spring Boot 4 moved cache auto-configuration out of {@code spring-boot-autoconfigure} into a
 * separate optional module, so {@code @EnableCaching} alone no longer auto-provides a
 * {@link CacheManager}. We declare one explicitly to keep the dependency surface minimal.
 *
 * <p>{@link ConcurrentMapCacheManager} is intentional for the test deliverable: the trial-balance
 * cache is invalidated by {@code ReconciliationJob} before every run (see ARCHITECTURE.md §10.1),
 * so cached entries never outlive a reconciliation interval in practice. A true time-based TTL
 * (the {@code reports.cache.trial-balance-summary-ttl-seconds} property) would require Caffeine
 * — added when production traffic justifies it.
 */
@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager("trial-balance-summary");
    }
}
