package dev.jaoow.cotatrack.api.cache;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * Configuration options for CotaTrack multi-layer Caffeine caches.
 * Allows configuring TTLs and maximum entries per data category.
 *
 * <p>Supported configuration keys (via System properties or Spring Environment):</p>
 * <ul>
 *     <li>{@code cotatrack.cache.quote-intraday-ttl}: Intraday stock quote TTL (default 5m)</li>
 *     <li>{@code cotatrack.cache.history-daily-ttl}: Historical daily candles TTL (default 12h)</li>
 *     <li>{@code cotatrack.cache.dividends-ttl}: Dividends data TTL (default 24h)</li>
 *     <li>{@code cotatrack.cache.logo-ttl}: Logos and registration TTL (default 7d)</li>
 *     <li>{@code cotatrack.cache.stale-ttl}: Stale disaster-recovery cache TTL (default 24h)</li>
 * </ul>
 */
@Data
@Builder
@Slf4j
public class CacheConfig {

    public static final Duration DEFAULT_QUOTE_INTRADAY_TTL = Duration.ofMinutes(5);
    public static final Duration DEFAULT_HISTORY_DAILY_TTL = Duration.ofHours(12);
    public static final Duration DEFAULT_DIVIDENDS_TTL = Duration.ofHours(24);
    public static final Duration DEFAULT_LOGO_TTL = Duration.ofDays(7);
    public static final Duration DEFAULT_STALE_TTL = Duration.ofHours(24);

    public static final long DEFAULT_MAX_QUOTES = 10_000L;
    public static final long DEFAULT_MAX_HISTORY = 2_000L;
    public static final long DEFAULT_MAX_DIVIDENDS = 1_000L;
    public static final long DEFAULT_MAX_LOGOS = 2_000L;

    @Builder.Default
    private Duration quoteIntradayTtl = DEFAULT_QUOTE_INTRADAY_TTL;

    @Builder.Default
    private Duration historyDailyTtl = DEFAULT_HISTORY_DAILY_TTL;

    @Builder.Default
    private Duration dividendsTtl = DEFAULT_DIVIDENDS_TTL;

    @Builder.Default
    private Duration logoTtl = DEFAULT_LOGO_TTL;

    @Builder.Default
    private Duration staleTtl = DEFAULT_STALE_TTL;

    @Builder.Default
    private long maxQuotesCacheSize = DEFAULT_MAX_QUOTES;

    @Builder.Default
    private long maxHistoryCacheSize = DEFAULT_MAX_HISTORY;

    @Builder.Default
    private long maxDividendsCacheSize = DEFAULT_MAX_DIVIDENDS;

    @Builder.Default
    private long maxLogosCacheSize = DEFAULT_MAX_LOGOS;

    /**
     * Builds a {@link CacheConfig} from System properties and Environment variables,
     * falling back to default values.
     */
    public static CacheConfig fromSystemProperties() {
        return CacheConfig.builder()
                .quoteIntradayTtl(parseDuration(getProp("cotatrack.cache.quote-intraday-ttl"), DEFAULT_QUOTE_INTRADAY_TTL))
                .historyDailyTtl(parseDuration(getProp("cotatrack.cache.history-daily-ttl"), DEFAULT_HISTORY_DAILY_TTL))
                .dividendsTtl(parseDuration(getProp("cotatrack.cache.dividends-ttl"), DEFAULT_DIVIDENDS_TTL))
                .logoTtl(parseDuration(getProp("cotatrack.cache.logo-ttl"), DEFAULT_LOGO_TTL))
                .staleTtl(parseDuration(getProp("cotatrack.cache.stale-ttl"), DEFAULT_STALE_TTL))
                .build();
    }

    private static String getProp(String key) {
        String val = System.getProperty(key);
        if (val == null || val.isBlank()) {
            String envKey = key.toUpperCase().replace('.', '_').replace('-', '_');
            val = System.getenv(envKey);
        }
        return val;
    }

    /**
     * Parses human-readable duration strings like "5m", "300s", "12h", "24h", "7d".
     */
    public static Duration parseDuration(String value, Duration defaultDuration) {
        if (value == null || value.isBlank()) {
            return defaultDuration;
        }
        String trimmed = value.trim().toLowerCase();
        try {
            if (trimmed.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(trimmed.substring(0, trimmed.length() - 2).trim()));
            } else if (trimmed.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(trimmed.substring(0, trimmed.length() - 1).trim()));
            } else if (trimmed.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(trimmed.substring(0, trimmed.length() - 1).trim()));
            } else if (trimmed.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(trimmed.substring(0, trimmed.length() - 1).trim()));
            } else if (trimmed.endsWith("d")) {
                return Duration.ofDays(Long.parseLong(trimmed.substring(0, trimmed.length() - 1).trim()));
            }
            return Duration.parse(value);
        } catch (Exception e) {
            log.warn("Invalid duration format: '{}', using default: {}", value, defaultDuration);
            return defaultDuration;
        }
    }
}
