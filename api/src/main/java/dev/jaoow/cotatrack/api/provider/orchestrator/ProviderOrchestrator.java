package dev.jaoow.cotatrack.api.provider.orchestrator;

import dev.jaoow.cotatrack.api.cache.BatchQuoteCoalescer;
import dev.jaoow.cotatrack.api.cache.CotaTrackCacheManager;
import dev.jaoow.cotatrack.api.provider.enricher.QuoteEnricher;
import dev.jaoow.cotatrack.api.provider.ProviderResult;
import dev.jaoow.cotatrack.api.provider.StockQuoteProvider;
import dev.jaoow.cotatrack.api.provider.impl.BrapiQuoteProvider;
import dev.jaoow.cotatrack.api.provider.impl.TradingViewQuoteProvider;
import dev.jaoow.cotatrack.api.provider.impl.YahooChartQuoteProvider;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.HistoricalQuote;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.Interval;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.Range;
import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrator service managing the chain of market data providers.
 * Incorporates:
 * <ul>
 *     <li>Multi-layer Caffeine caching with configurable TTLs (intraday 3-5m, history 12h, stale 24h)</li>
 *     <li>Single-Flight request coalescing to eliminate duplicate concurrent provider calls (thundering herd prevention)</li>
 *     <li>Intelligent batching grouping all uncached symbols into single batch calls to TradingView</li>
 *     <li>Resilience4j Circuit Breakers and Rate Limiters per provider</li>
 *     <li>Automated fallback across providers and 24h stale disaster-recovery</li>
 * </ul>
 */
@Slf4j
public class ProviderOrchestrator {

    private final List<StockQuoteProvider> providers;
    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final Map<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();

    @Getter
    private final CotaTrackCacheManager cacheManager;

    @Getter
    private final BatchQuoteCoalescer batchQuoteCoalescer;

    @Getter
    private volatile String lastServingProvider = "UNKNOWN";
    @Getter
    private volatile boolean hadFallback = false;

    public ProviderOrchestrator() {
        this(createDefaultProviders(), CotaTrackCacheManager.getInstance(), new BatchQuoteCoalescer());
    }

    public ProviderOrchestrator(List<StockQuoteProvider> providers) {
        this(providers, CotaTrackCacheManager.getInstance(), new BatchQuoteCoalescer());
    }

    public ProviderOrchestrator(List<StockQuoteProvider> providers, CotaTrackCacheManager cacheManager) {
        this(providers, cacheManager, new BatchQuoteCoalescer());
    }

    public ProviderOrchestrator(List<StockQuoteProvider> providers,
                                CotaTrackCacheManager cacheManager,
                                BatchQuoteCoalescer batchQuoteCoalescer) {
        this.providers = new ArrayList<>(providers);
        this.cacheManager = cacheManager != null ? cacheManager : CotaTrackCacheManager.getInstance();
        this.batchQuoteCoalescer = batchQuoteCoalescer != null ? batchQuoteCoalescer : new BatchQuoteCoalescer();
        initResilience();
    }

    private static List<StockQuoteProvider> createDefaultProviders() {
        String orderProp = System.getProperty("cotatrack.providers.order", "TRADING_VIEW,YAHOO_CHART,BRAPI");
        List<String> order = Arrays.stream(orderProp.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .toList();

        Map<String, StockQuoteProvider> available = new HashMap<>();
        available.put(TradingViewQuoteProvider.PROVIDER_NAME, new TradingViewQuoteProvider());
        available.put(YahooChartQuoteProvider.PROVIDER_NAME, new YahooChartQuoteProvider());
        available.put(BrapiQuoteProvider.PROVIDER_NAME, new BrapiQuoteProvider());

        List<StockQuoteProvider> orderedProviders = new ArrayList<>();
        for (String name : order) {
            StockQuoteProvider p = available.get(name);
            if (p != null) {
                orderedProviders.add(p);
            }
        }

        // Add any remaining providers not explicitly listed in order
        for (StockQuoteProvider p : available.values()) {
            if (!orderedProviders.contains(p)) {
                orderedProviders.add(p);
            }
        }

        return orderedProviders;
    }

    private void initResilience() {
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50.0f)
                .slidingWindowSize(6)
                .minimumNumberOfCalls(3)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build();

        for (StockQuoteProvider p : providers) {
            circuitBreakers.put(p.getName(), CircuitBreaker.of(p.getName(), cbConfig));

            // Rate limiter: specific tuning per provider
            Duration timeout = Duration.ofSeconds(2);
            int limitForPeriod = 100;
            if (BrapiQuoteProvider.PROVIDER_NAME.equals(p.getName())) {
                limitForPeriod = 10; // Brapi free tier preservation
            }
            RateLimiterConfig rlConfig = RateLimiterConfig.custom()
                    .limitForPeriod(limitForPeriod)
                    .limitRefreshPeriod(Duration.ofSeconds(1))
                    .timeoutDuration(timeout)
                    .build();
            rateLimiters.put(p.getName(), RateLimiter.of(p.getName(), rlConfig));
        }
    }

    /**
     * Fetches a single quote with caching, coalescing, and automated fallback.
     */
    public ProviderResult<Quote> fetchQuote(String symbol) {
        ProviderResult<Map<String, Quote>> multi = fetchQuotes(Collections.singletonList(symbol));
        Quote q = multi.getData() != null ? findQuoteInMap(multi.getData(), symbol) : null;
        return ProviderResult.<Quote>builder()
                .data(q)
                .providerName(multi.getProviderName())
                .fromCache(multi.isFromCache())
                .stale(multi.isStale())
                .executionTimeMs(multi.getExecutionTimeMs())
                .build();
    }

    private static class FetchContext {
        String lastServingProvider;
        boolean fallbackOccurred;
    }

    private final ThreadLocal<FetchContext> currentFetchContext = ThreadLocal.withInitial(FetchContext::new);

    /**
     * Fetches quotes for multiple symbols:
     * <ol>
     *     <li>Checks the intraday cache for each symbol.</li>
     *     <li>If all symbols are cached, returns immediately without network calls.</li>
     *     <li>Groups uncached symbols into a single batch call.</li>
     *     <li>Coalesces concurrent requests for identical symbols via Single-Flight.</li>
     *     <li>Falls back across providers and 24h stale disaster cache when needed.</li>
     * </ol>
     */
    public ProviderResult<Map<String, Quote>> fetchQuotes(List<String> symbols) {
        long startTime = System.currentTimeMillis();
        if (symbols == null || symbols.isEmpty()) {
            return ProviderResult.of(Collections.emptyMap(), "NONE", 0);
        }

        List<String> cleanSymbols = symbols.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();

        Map<String, Quote> accumulatedQuotes = new LinkedHashMap<>();
        List<String> uncachedSymbols = new ArrayList<>();

        // 1. Check intraday cache (TTL 3-5m)
        for (String sym : cleanSymbols) {
            Quote cached = cacheManager.getIntradayQuote(sym);
            if (cached != null) {
                accumulatedQuotes.put(sym, cached);
            } else {
                uncachedSymbols.add(sym);
            }
        }

        // If all symbols are already cached, return immediately
        if (uncachedSymbols.isEmpty()) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.debug("All {} requested symbols served from intraday cache in {}ms", cleanSymbols.size(), elapsed);
            return ProviderResult.<Map<String, Quote>>builder()
                    .data(accumulatedQuotes)
                    .providerName("CACHE")
                    .fromCache(true)
                    .stale(false)
                    .executionTimeMs(elapsed)
                    .build();
        }

        currentFetchContext.remove();
        FetchContext context = currentFetchContext.get();

        try {
            // 2. Coalesce concurrent calls for uncached symbols & execute single batch provider fetch
            log.info("Fetching {} uncached symbols in batch: {}", uncachedSymbols.size(), uncachedSymbols);
            Map<String, Quote> newlyFetched = batchQuoteCoalescer.coalesceAndFetch(uncachedSymbols, this::executeProviderFallbackChain);

            if (newlyFetched != null && !newlyFetched.isEmpty()) {
                accumulatedQuotes.putAll(newlyFetched);
            }

            // 3. Disaster-recovery: check stale 24h cache for any symbols that failed across all providers
            boolean servedFromStale = false;
            for (String sym : cleanSymbols) {
                if (!accumulatedQuotes.containsKey(sym)) {
                    Quote staleQuote = cacheManager.getStaleQuote(sym);
                    if (staleQuote != null) {
                        accumulatedQuotes.put(sym, staleQuote);
                        servedFromStale = true;
                        log.warn("Symbol {} rescued from 24h stale disaster cache", sym);
                    }
                }
            }

            long elapsedMs = System.currentTimeMillis() - startTime;
            boolean allFromCache = uncachedSymbols.isEmpty();
            String providerLabel;
            if (allFromCache) {
                providerLabel = "CACHE";
            } else if (servedFromStale && (newlyFetched == null || newlyFetched.isEmpty())) {
                providerLabel = "STALE_CACHE";
            } else if (context.lastServingProvider != null) {
                providerLabel = context.fallbackOccurred
                        ? context.lastServingProvider + " (fallback)"
                        : context.lastServingProvider;
            } else {
                providerLabel = servedFromStale ? "STALE_CACHE" : "MULTI_PROVIDER";
            }

            return ProviderResult.<Map<String, Quote>>builder()
                    .data(accumulatedQuotes)
                    .providerName(providerLabel)
                    .fromCache(allFromCache || (servedFromStale && (newlyFetched == null || newlyFetched.isEmpty())))
                    .stale(servedFromStale)
                    .executionTimeMs(elapsedMs)
                    .build();
        } finally {
            currentFetchContext.remove();
        }
    }

    /**
     * Executes the provider fallback chain for a batch of uncached symbols.
     * Primary TradingView is called with ALL symbols in a single POST request.
     */
    private Map<String, Quote> executeProviderFallbackChain(List<String> batch) {
        Map<String, Quote> result = new LinkedHashMap<>();
        Set<String> remainingSymbols = new LinkedHashSet<>(batch);
        FetchContext context = currentFetchContext.get();

        for (int i = 0; i < providers.size(); i++) {
            StockQuoteProvider provider = providers.get(i);
            if (!provider.isEnabled()) {
                log.debug("Provider {} is disabled, skipping.", provider.getName());
                continue;
            }
            if (remainingSymbols.isEmpty()) {
                break;
            }

            CircuitBreaker cb = circuitBreakers.get(provider.getName());
            if (cb != null && !cb.tryAcquirePermission()) {
                log.warn("Circuit breaker for provider {} is OPEN. Falling back to next provider...", provider.getName());
                dev.jaoow.cotatrack.api.provider.health.ProviderHealthTracker.getInstance()
                        .recordFailure(provider.getName(), "Circuit breaker is OPEN");
                context.fallbackOccurred = true;
                continue;
            }

            RateLimiter rl = rateLimiters.get(provider.getName());
            if (rl != null && !rl.acquirePermission()) {
                log.warn("Rate limiter for provider {} rejected request. Falling back to next provider...", provider.getName());
                dev.jaoow.cotatrack.api.provider.health.ProviderHealthTracker.getInstance()
                        .recordFailure(provider.getName(), "Rate limiter rejected request");
                context.fallbackOccurred = true;
                continue;
            }

            long callStart = System.nanoTime();
            try {
                List<String> queryList = new ArrayList<>(remainingSymbols);
                log.info("Dispatching batch of {} symbols to provider {}: {}", queryList.size(), provider.getName(), queryList);
                Map<String, Quote> fetched = provider.fetchQuotes(queryList);

                if (cb != null) {
                    cb.onSuccess(System.nanoTime() - callStart, TimeUnit.NANOSECONDS);
                }

                if (fetched != null && !fetched.isEmpty()) {
                    dev.jaoow.cotatrack.api.provider.health.ProviderHealthTracker.getInstance()
                            .recordSuccess(provider.getName());
                    int servedCount = 0;
                    for (String reqSym : queryList) {
                        Quote q = findQuoteInMap(fetched, reqSym);
                        if (q != null) {
                            Quote enriched = QuoteEnricher.enrich(q);
                            result.put(reqSym, enriched);
                            // Populate intraday cache (TTL 3-5m) and stale cache (TTL 24h)
                            cacheManager.putIntradayQuote(reqSym, enriched);
                            remainingSymbols.remove(reqSym);
                            servedCount++;
                        }
                    }
                    if (servedCount > 0) {
                        context.lastServingProvider = provider.getName();
                        if (i > 0) {
                            context.fallbackOccurred = true;
                        }
                    }
                    log.info("Provider {} served {}/{} requested symbols in batch", provider.getName(), servedCount, queryList.size());
                    if (!remainingSymbols.isEmpty()) {
                        context.fallbackOccurred = true;
                    }
                } else {
                    log.warn("Provider {} returned empty results for batch {}. Trying fallback...", provider.getName(), queryList);
                    dev.jaoow.cotatrack.api.provider.health.ProviderHealthTracker.getInstance()
                            .recordFailure(provider.getName(), "Returned empty batch results");
                    context.fallbackOccurred = true;
                }

            } catch (Exception e) {
                if (cb != null) {
                    cb.onError(System.nanoTime() - callStart, TimeUnit.NANOSECONDS, e);
                }
                dev.jaoow.cotatrack.api.provider.health.ProviderHealthTracker.getInstance()
                        .recordFailure(provider.getName(), e);
                context.fallbackOccurred = true;
                log.warn("Provider {} failed ({}) for batch: {}. Attempting fallback...",
                        provider.getName(), e.getMessage(), remainingSymbols);
            }
        }

        return result;
    }

    /**
     * Fetches historical quotes using the 6-12h historical cache with automated fallback across providers.
     */
    public ProviderResult<List<HistoricalQuote>> fetchHistorical(String symbol, LocalDate from, LocalDate to, Interval interval, Range range) {
        long startTime = System.currentTimeMillis();
        if (symbol == null || symbol.isBlank()) {
            return ProviderResult.of(Collections.emptyList(), "NONE", 0);
        }

        String cacheKey = symbol.toUpperCase().replace(".SA", "") + ":" + from + ":" + to + ":" + interval + ":" + range;

        // 1. Check historical cache (TTL 6-12h)
        List<HistoricalQuote> cached = cacheManager.getHistorical(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.debug("Historical quotes for {} served from cache ({} candles) in {}ms", symbol, cached.size(), elapsed);
            return ProviderResult.of(cached, "CACHE", elapsed);
        }

        // 2. Fetch through providers with circuit breaker and fallback
        for (StockQuoteProvider provider : providers) {
            if (!provider.isEnabled() || !provider.supportsHistorical()) {
                continue;
            }

            CircuitBreaker cb = circuitBreakers.get(provider.getName());
            if (cb != null && !cb.tryAcquirePermission()) {
                log.warn("Circuit breaker for historical on provider {} is OPEN. Trying fallback...", provider.getName());
                continue;
            }

            long callStart = System.nanoTime();
            try {
                List<HistoricalQuote> quotes = provider.fetchHistorical(symbol, from, to, interval, range);
                if (cb != null) {
                    cb.onSuccess(System.nanoTime() - callStart, TimeUnit.NANOSECONDS);
                }
                if (quotes != null && !quotes.isEmpty()) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    // Cache the successful historical result (TTL 6-12h)
                    cacheManager.putHistorical(cacheKey, quotes);
                    log.info("Historical quotes ({} candles) successfully served by provider {} and cached", quotes.size(), provider.getName());
                    return ProviderResult.of(quotes, provider.getName(), elapsed);
                }
            } catch (Exception e) {
                if (cb != null) {
                    cb.onError(System.nanoTime() - callStart, TimeUnit.NANOSECONDS, e);
                }
                log.warn("Historical fetch failed on provider {} ({}). Trying next provider...", provider.getName(), e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        return ProviderResult.of(Collections.emptyList(), "NONE", elapsed);
    }

    private Quote findQuoteInMap(Map<String, Quote> map, String requestedSymbol) {
        if (map == null) return null;
        if (map.containsKey(requestedSymbol)) return map.get(requestedSymbol);
        String clean = requestedSymbol.replace(".SA", "");
        if (map.containsKey(clean)) return map.get(clean);
        if (map.containsKey(clean + ".SA")) return map.get(clean + ".SA");
        return null;
    }

    public List<StockQuoteProvider> getProviders() {
        return Collections.unmodifiableList(providers);
    }

    public Map<String, CircuitBreaker> getCircuitBreakers() {
        return Collections.unmodifiableMap(circuitBreakers);
    }
}
