package dev.jaoow.cotatrack.api.provider.health;

import lombok.Data;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe tracker for provider health, diagnostics, and metrics.
 * Records the last success and failure timestamps and error summaries per provider without exposing sensitive secrets.
 */
public class ProviderHealthTracker {

    private static final ProviderHealthTracker INSTANCE = new ProviderHealthTracker();

    public static ProviderHealthTracker getInstance() {
        return INSTANCE;
    }

    private final Map<String, ProviderMetrics> metricsMap = new ConcurrentHashMap<>();

    @Data
    public static class ProviderMetrics {
        private final String providerName;
        private volatile boolean enabled = true;
        private volatile Instant lastSuccess;
        private volatile Instant lastFailure;
        private volatile String lastFailureReason;
        private final AtomicLong totalRequests = new AtomicLong();
        private final AtomicLong successfulRequests = new AtomicLong();
        private final AtomicLong failedRequests = new AtomicLong();

        public ProviderMetrics(String providerName) {
            this.providerName = providerName;
        }

        public void recordSuccess() {
            totalRequests.incrementAndGet();
            successfulRequests.incrementAndGet();
            lastSuccess = Instant.now();
        }

        public void recordFailure(String reason) {
            totalRequests.incrementAndGet();
            failedRequests.incrementAndGet();
            lastFailure = Instant.now();
            this.lastFailureReason = sanitizeError(reason);
        }

        public void recordFailure(Throwable t) {
            recordFailure(t != null ? t.getMessage() : "Unknown error");
        }

        /**
         * Sanitizes error message to guarantee no tokens or sensitive headers leak.
         */
        private static String sanitizeError(String msg) {
            if (msg == null) return "Unknown error";
            // Strip any query parameter containing tokens or cookies
            return msg.replaceAll("(?i)(token|key|crumb|cookie)=[^&\\s]+", "$1=***");
        }
    }

    public ProviderMetrics getOrCreate(String providerName) {
        return metricsMap.computeIfAbsent(providerName, ProviderMetrics::new);
    }

    public void recordSuccess(String providerName) {
        getOrCreate(providerName).recordSuccess();
    }

    public void recordFailure(String providerName, String reason) {
        getOrCreate(providerName).recordFailure(reason);
    }

    public void recordFailure(String providerName, Throwable t) {
        getOrCreate(providerName).recordFailure(t);
    }

    public void setEnabled(String providerName, boolean enabled) {
        getOrCreate(providerName).setEnabled(enabled);
    }

    public Map<String, ProviderMetrics> getAllMetrics() {
        return Collections.unmodifiableMap(metricsMap);
    }

    public void reset() {
        metricsMap.clear();
    }
}
