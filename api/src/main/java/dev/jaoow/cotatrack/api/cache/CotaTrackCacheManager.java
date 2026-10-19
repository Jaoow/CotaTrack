package dev.jaoow.cotatrack.api.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.jaoow.cotatrack.api.requests.dividends.model.DividendsData;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.HistoricalQuote;
import dev.jaoow.cotatrack.api.requests.quotes.model.FxQuote;
import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Centralized manager for all multi-layer Caffeine caches in CotaTrack.
 * Controls caching and eviction policies according to configurable TTLs.
 *
 * <p>Caches managed:</p>
 * <ul>
 *     <li><b>Intraday Quotes:</b> TTL 3 to 5 minutes (default 5m)</li>
 *     <li><b>Historical Daily Candles:</b> TTL 6 to 12 hours (default 12h)</li>
 *     <li><b>Dividends & Proventos:</b> TTL 24 hours</li>
 *     <li><b>Logos & Cadastro:</b> TTL 7 days</li>
 *     <li><b>Forex / Câmbio:</b> TTL 5 minutes</li>
 *     <li><b>Stale Cache:</b> TTL 24 hours (disaster-recovery fallback)</li>
 * </ul>
 */
@Slf4j
@Getter
public class CotaTrackCacheManager {

    private static volatile CotaTrackCacheManager instance;

    private final CacheConfig config;

    private final Cache<String, Quote> intradayQuoteCache;
    private final Cache<String, Quote> staleQuoteCache;
    private final Cache<String, List<HistoricalQuote>> historicalCache;
    private final Cache<String, DividendsData> dividendsCache;
    private final Cache<String, String> logoCache;
    private final Cache<String, FxQuote> fxQuoteCache;

    public CotaTrackCacheManager() {
        this(CacheConfig.fromSystemProperties());
    }

    public CotaTrackCacheManager(CacheConfig config) {
        this.config = config != null ? config : CacheConfig.fromSystemProperties();

        this.intradayQuoteCache = Caffeine.newBuilder()
                .expireAfterWrite(this.config.getQuoteIntradayTtl())
                .maximumSize(this.config.getMaxQuotesCacheSize())
                .recordStats()
                .build();

        this.staleQuoteCache = Caffeine.newBuilder()
                .expireAfterWrite(this.config.getStaleTtl())
                .maximumSize(this.config.getMaxQuotesCacheSize())
                .build();

        this.historicalCache = Caffeine.newBuilder()
                .expireAfterWrite(this.config.getHistoryDailyTtl())
                .maximumSize(this.config.getMaxHistoryCacheSize())
                .recordStats()
                .build();

        this.dividendsCache = Caffeine.newBuilder()
                .expireAfterWrite(this.config.getDividendsTtl())
                .maximumSize(this.config.getMaxDividendsCacheSize())
                .recordStats()
                .build();

        this.logoCache = Caffeine.newBuilder()
                .expireAfterWrite(this.config.getLogoTtl())
                .maximumSize(this.config.getMaxLogosCacheSize())
                .recordStats()
                .build();

        this.fxQuoteCache = Caffeine.newBuilder()
                .expireAfterWrite(this.config.getQuoteIntradayTtl())
                .maximumSize(500)
                .recordStats()
                .build();

        log.info("Initialized CotaTrackCacheManager with TTLs: intraday={}, history={}, dividends={}, logo={}, stale={}",
                this.config.getQuoteIntradayTtl(), this.config.getHistoryDailyTtl(),
                this.config.getDividendsTtl(), this.config.getLogoTtl(), this.config.getStaleTtl());
    }

    public static CotaTrackCacheManager getInstance() {
        if (instance == null) {
            synchronized (CotaTrackCacheManager.class) {
                if (instance == null) {
                    instance = new CotaTrackCacheManager();
                }
            }
        }
        return instance;
    }

    public static synchronized void setInstance(CotaTrackCacheManager customInstance) {
        instance = customInstance;
    }

    // --- Intraday Quote Cache Helpers ---

    public Quote getIntradayQuote(String symbol) {
        if (symbol == null) return null;
        String key = normalize(symbol);
        Quote q = intradayQuoteCache.getIfPresent(key);
        if (q == null) {
            q = intradayQuoteCache.getIfPresent(key + ".SA");
        }
        return q;
    }

    public Map<String, Quote> getIntradayQuotes(Collection<String> symbols) {
        if (symbols == null || symbols.isEmpty()) return Collections.emptyMap();
        Map<String, Quote> found = new LinkedHashMap<>();
        for (String s : symbols) {
            Quote q = getIntradayQuote(s);
            if (q != null) {
                found.put(s, q);
            }
        }
        return found;
    }

    public void putIntradayQuote(String symbol, Quote quote) {
        if (symbol == null || quote == null) return;
        String clean = normalize(symbol);
        intradayQuoteCache.put(clean, quote);
        intradayQuoteCache.put(clean + ".SA", quote);
        // Also feed the stale disaster-recovery cache
        staleQuoteCache.put(clean, quote);
        staleQuoteCache.put(clean + ".SA", quote);
    }

    public void putIntradayQuotes(Map<String, Quote> quotes) {
        if (quotes == null) return;
        quotes.forEach(this::putIntradayQuote);
    }

    // --- Stale Disaster-Recovery Cache Helpers ---

    public Quote getStaleQuote(String symbol) {
        if (symbol == null) return null;
        String clean = normalize(symbol);
        Quote q = staleQuoteCache.getIfPresent(clean);
        if (q == null) {
            q = staleQuoteCache.getIfPresent(clean + ".SA");
        }
        return q;
    }

    // --- Historical Quotes Cache Helpers ---

    public List<HistoricalQuote> getHistorical(String cacheKey) {
        return historicalCache.getIfPresent(cacheKey);
    }

    public void putHistorical(String cacheKey, List<HistoricalQuote> data) {
        if (cacheKey != null && data != null) {
            historicalCache.put(cacheKey, data);
        }
    }

    // --- Dividends Cache Helpers ---

    public DividendsData getDividends(String symbol) {
        return symbol != null ? dividendsCache.getIfPresent(normalize(symbol)) : null;
    }

    public void putDividends(String symbol, DividendsData data) {
        if (symbol != null && data != null) {
            dividendsCache.put(normalize(symbol), data);
        }
    }

    // --- Logo Cache Helpers ---

    public String getLogo(String cacheKey) {
        return cacheKey != null ? logoCache.getIfPresent(cacheKey) : null;
    }

    public void putLogo(String cacheKey, String logoId) {
        if (cacheKey != null && logoId != null) {
            logoCache.put(cacheKey, logoId);
        }
    }

    // --- FX Quote Cache Helpers ---

    public FxQuote getFxQuote(String symbol) {
        if (symbol == null) return null;
        return fxQuoteCache.getIfPresent(symbol.trim().toUpperCase());
    }

    public Map<String, FxQuote> getFxQuotes(Collection<String> symbols) {
        if (symbols == null || symbols.isEmpty()) return Collections.emptyMap();
        Map<String, FxQuote> found = new LinkedHashMap<>();
        for (String s : symbols) {
            FxQuote q = getFxQuote(s);
            if (q != null) {
                found.put(s, q);
            }
        }
        return found;
    }

    public void putFxQuote(String symbol, FxQuote quote) {
        if (symbol != null && quote != null) {
            fxQuoteCache.put(symbol.trim().toUpperCase(), quote);
        }
    }

    public void putFxQuotes(Map<String, FxQuote> quotes) {
        if (quotes != null) {
            quotes.forEach(this::putFxQuote);
        }
    }

    public void clearAll() {
        intradayQuoteCache.invalidateAll();
        staleQuoteCache.invalidateAll();
        historicalCache.invalidateAll();
        dividendsCache.invalidateAll();
        logoCache.invalidateAll();
        fxQuoteCache.invalidateAll();
    }

    private String normalize(String s) {
        return s.trim().toUpperCase().replace(".SA", "");
    }
}
