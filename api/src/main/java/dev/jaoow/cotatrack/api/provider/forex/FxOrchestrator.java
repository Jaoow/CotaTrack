package dev.jaoow.cotatrack.api.provider.forex;

import dev.jaoow.cotatrack.api.cache.CotaTrackCacheManager;
import dev.jaoow.cotatrack.api.requests.quotes.model.FxQuote;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.*;

/**
 * Orchestrator service managing foreign exchange quotes.
 * Uses Banco Central do Brasil (BCB PTAX) as primary source, with automated fallback
 * to Yahoo Finance when BCB is unavailable, and Caffeine caching.
 */
@Slf4j
public class FxOrchestrator {

    @Getter
    private final FxQuoteProvider primaryProvider;

    @Getter
    private final FxQuoteProvider fallbackProvider;

    @Getter
    private final CotaTrackCacheManager cacheManager;

    public FxOrchestrator() {
        this(new BcbPtaxFxProvider(), new YahooFxProvider(), CotaTrackCacheManager.getInstance());
    }

    public FxOrchestrator(FxQuoteProvider primaryProvider, FxQuoteProvider fallbackProvider, CotaTrackCacheManager cacheManager) {
        this.primaryProvider = primaryProvider != null ? primaryProvider : new BcbPtaxFxProvider();
        this.fallbackProvider = fallbackProvider != null ? fallbackProvider : new YahooFxProvider();
        this.cacheManager = cacheManager != null ? cacheManager : CotaTrackCacheManager.getInstance();
    }

    /**
     * Fetches a single foreign exchange quote with fallback.
     */
    public FxQuote fetchQuote(String symbol) {
        Map<String, FxQuote> map = fetchQuotes(Collections.singletonList(symbol));
        return map.get(symbol);
    }

    /**
     * Fetches multiple foreign exchange quotes:
     * <ol>
     *     <li>Validates all symbols against supported BCB currencies. Throws {@link IllegalArgumentException} if any symbol is invalid.</li>
     *     <li>Checks the cache for existing quotes.</li>
     *     <li>Queries the primary provider (Banco Central PTAX) for uncached symbols.</li>
     *     <li>Automatically falls back to Yahoo Finance for any symbols that failed on BCB.</li>
     *     <li>Stores newly fetched quotes into the cache.</li>
     * </ol>
     */
    public Map<String, FxQuote> fetchQuotes(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Collections.emptyMap();
        }

        // 1. Strict validation: all requested symbols must be supported
        for (String sym : symbols) {
            BcbCurrencyMapper.validateAndMap(sym);
        }

        Map<String, FxQuote> result = new LinkedHashMap<>();
        List<String> uncachedSymbols = new ArrayList<>();

        // 2. Cache check
        for (String sym : symbols) {
            FxQuote cached = cacheManager.getFxQuote(sym);
            if (cached != null) {
                result.put(sym, cached);
            } else {
                uncachedSymbols.add(sym);
            }
        }

        if (uncachedSymbols.isEmpty()) {
            log.debug("All {} requested FX symbols served from cache", symbols.size());
            return result;
        }

        // 3. Primary: Banco Central do Brasil (BCB PTAX)
        List<String> missingAfterPrimary = new ArrayList<>();
        if (primaryProvider != null && primaryProvider.isEnabled()) {
            try {
                log.info("Fetching {} uncached FX symbols from primary provider {}: {}",
                        uncachedSymbols.size(), primaryProvider.getName(), uncachedSymbols);
                Map<String, FxQuote> primaryResults = primaryProvider.fetchFxQuotes(uncachedSymbols);

                for (String sym : uncachedSymbols) {
                    FxQuote q = (primaryResults != null) ? primaryResults.get(sym) : null;
                    if (q != null && q.getPrice() != null && q.getPrice().compareTo(BigDecimal.ZERO) > 0) {
                        result.put(sym, q);
                        cacheManager.putFxQuote(sym, q);
                    } else {
                        missingAfterPrimary.add(sym);
                    }
                }
            } catch (Exception e) {
                log.warn("Primary FX provider {} failed: {}. Triggering fallback...", primaryProvider.getName(), e.getMessage());
                missingAfterPrimary.addAll(uncachedSymbols);
            }
        } else {
            log.info("Primary FX provider is disabled or null. Skipping to fallback...");
            missingAfterPrimary.addAll(uncachedSymbols);
        }

        // 4. Fallback: Yahoo Finance
        if (!missingAfterPrimary.isEmpty() && fallbackProvider != null && fallbackProvider.isEnabled()) {
            log.warn("Triggering FX fallback provider {} for symbols: {}", fallbackProvider.getName(), missingAfterPrimary);
            try {
                Map<String, FxQuote> fallbackResults = fallbackProvider.fetchFxQuotes(missingAfterPrimary);
                if (fallbackResults != null) {
                    for (String sym : missingAfterPrimary) {
                        FxQuote q = fallbackResults.get(sym);
                        if (q != null) {
                            result.put(sym, q);
                            cacheManager.putFxQuote(sym, q);
                            log.info("Symbol {} served by fallback provider {}", sym, fallbackProvider.getName());
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Fallback FX provider {} failed: {}", fallbackProvider.getName(), e.getMessage());
            }
        }

        return result;
    }
}
