package dev.jaoow.cotatrack.api.provider.orchestrator;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrator service managing the chain of providers with circuit breaker,
 * rate limiting, automated fallback, and stale-cache rescue.
 */
@Slf4j
public class ProviderOrchestrator {

    private final List<StockQuoteProvider> providers;
    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final Map<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();

    // Stale cache: preserves the last valid quote for up to 24 hours in case all providers fail
    private final Cache<String, Quote> staleCache = Caffeine.newBuilder()
            .expireAfterWrite(24, TimeUnit.HOURS)
            .maximumSize(5000)
            .build();

    public ProviderOrchestrator() {
        this(createDefaultProviders());
    }

    public ProviderOrchestrator(List<StockQuoteProvider> providers) {
        this.providers = new ArrayList<>(providers);
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
                limitForPeriod = 10; // Brapi free tier rate preservation
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
     * Fetches a single quote with automated fallback across providers.
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

    /**
     * Fetches quotes for multiple symbols with automated fallback across providers.
     */
    public ProviderResult<Map<String, Quote>> fetchQuotes(List<String> symbols) {
        long startTime = System.currentTimeMillis();
        Map<String, Quote> accumulatedQuotes = new HashMap<>();
        Set<String> remainingSymbols = new HashSet<>(symbols);
        String lastServingProvider = "UNKNOWN";
        boolean hadFallback = false;

        for (StockQuoteProvider provider : providers) {
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
                hadFallback = true;
                continue;
            }

            RateLimiter rl = rateLimiters.get(provider.getName());
            if (rl != null && !rl.acquirePermission()) {
                log.warn("Rate limiter for provider {} rejected request. Falling back to next provider...", provider.getName());
                hadFallback = true;
                continue;
            }

            long callStart = System.nanoTime();
            try {
                List<String> queryList = new ArrayList<>(remainingSymbols);
                Map<String, Quote> fetched = provider.fetchQuotes(queryList);

                if (cb != null) {
                    cb.onSuccess(System.nanoTime() - callStart, TimeUnit.NANOSECONDS);
                }

                if (fetched != null && !fetched.isEmpty()) {
                    int servedCount = 0;
                    for (String reqSym : queryList) {
                        Quote q = findQuoteInMap(fetched, reqSym);
                        if (q != null) {
                            accumulatedQuotes.put(reqSym, q);
                            staleCache.put(reqSym, q);
                            remainingSymbols.remove(reqSym);
                            servedCount++;
                        }
                    }

                    lastServingProvider = provider.getName();
                    log.info("Provider {} served {}/{} requested symbols", provider.getName(), servedCount, queryList.size());
                } else {
                    log.warn("Provider {} returned empty results for symbols {}. Trying fallback...", provider.getName(), queryList);
                    hadFallback = true;
                }

            } catch (Exception e) {
                if (cb != null) {
                    cb.onError(System.nanoTime() - callStart, TimeUnit.NANOSECONDS, e);
                }
                hadFallback = true;
                log.warn("Provider {} failed ({}) for symbols: {}. Attempting fallback...",
                        provider.getName(), e.getMessage(), remainingSymbols);
            }
        }

        // Check if any symbols are still missing: rescue with stale cache if available
        boolean servedFromStale = false;
        if (!remainingSymbols.isEmpty()) {
            for (String missingSym : new ArrayList<>(remainingSymbols)) {
                Quote staleQuote = staleCache.getIfPresent(missingSym);
                if (staleQuote == null) {
                    staleQuote = staleCache.getIfPresent(missingSym.replace(".SA", ""));
                }
                if (staleQuote != null) {
                    accumulatedQuotes.put(missingSym, staleQuote);
                    remainingSymbols.remove(missingSym);
                    servedFromStale = true;
                    log.warn("Symbol {} rescued from stale cache", missingSym);
                }
            }
        }

        accumulatedQuotes.values().forEach(QuoteEnricher::enrich);

        long elapsedMs = System.currentTimeMillis() - startTime;
        String providerLabel = hadFallback ? lastServingProvider + " (fallback)" : lastServingProvider;

        return ProviderResult.<Map<String, Quote>>builder()
                .data(accumulatedQuotes)
                .providerName(providerLabel)
                .fromCache(servedFromStale)
                .stale(servedFromStale)
                .executionTimeMs(elapsedMs)
                .build();
    }

    /**
     * Fetches historical quotes using the first capable and healthy provider in the fallback chain.
     */
    public ProviderResult<List<HistoricalQuote>> fetchHistorical(String symbol, LocalDate from, LocalDate to, Interval interval, Range range) {
        long startTime = System.currentTimeMillis();

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
                    log.info("Historical quotes ({} candles) successfully served by provider {}", quotes.size(), provider.getName());
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
