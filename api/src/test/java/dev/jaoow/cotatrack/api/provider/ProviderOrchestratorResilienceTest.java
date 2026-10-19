package dev.jaoow.cotatrack.api.provider;

import dev.jaoow.cotatrack.api.cache.CacheConfig;
import dev.jaoow.cotatrack.api.cache.CotaTrackCacheManager;
import dev.jaoow.cotatrack.api.provider.orchestrator.ProviderOrchestrator;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.HistoricalQuote;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.Interval;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.Range;
import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProviderOrchestratorResilienceTest {

    private CotaTrackCacheManager cacheManager;

    @BeforeEach
    void setUp() {
        cacheManager = new CotaTrackCacheManager(CacheConfig.builder()
                .quoteIntradayTtl(Duration.ofMinutes(5))
                .staleTtl(Duration.ofHours(24))
                .build());
    }

    @Test
    @DisplayName("Should execute automated fallback from Primary to Secondary provider")
    void testFallbackChain() {
        FailingProvider primary = new FailingProvider("PRIMARY_FAIL");
        SuccessfulProvider secondary = new SuccessfulProvider("SECONDARY_SUCCESS");

        ProviderOrchestrator orchestrator = new ProviderOrchestrator(List.of(primary, secondary), cacheManager);

        ProviderResult<Map<String, Quote>> result = orchestrator.fetchQuotes(List.of("PETR4"));

        assertFalse(result.isFromCache());
        assertEquals("SECONDARY_SUCCESS (fallback)", result.getProviderName());
        assertTrue(result.getData().containsKey("PETR4"));
        assertEquals(1, primary.callCount.get());
        assertEquals(1, secondary.callCount.get());
    }

    @Test
    @DisplayName("Should trip circuit breaker after repeated failures and skip provider")
    void testCircuitBreakerTripping() {
        FailingProvider failing = new FailingProvider("FAILING_CB");
        SuccessfulProvider fallback = new SuccessfulProvider("FALLBACK_CB");

        ProviderOrchestrator orchestrator = new ProviderOrchestrator(List.of(failing, fallback), cacheManager);

        // Call 4 times with unique symbols to avoid cache and trigger circuit breaker threshold (min 3 calls)
        for (int i = 0; i < 4; i++) {
            orchestrator.fetchQuotes(List.of("SYM" + i));
        }

        int callsBeforeOpen = failing.callCount.get();
        assertTrue(callsBeforeOpen >= 3);

        // 5th call: circuit breaker should be OPEN for FAILING_CB and skip directly to fallback!
        orchestrator.fetchQuotes(List.of("SYM_SKIP"));
        assertEquals(callsBeforeOpen, failing.callCount.get(), "Failing provider must be skipped when circuit breaker is OPEN");
    }

    @Test
    @DisplayName("Should rescue quotes from 24h stale cache when all providers fail")
    void testDisasterRecoveryFromStaleCache() {
        Quote oldQuote = new Quote();
        oldQuote.setSymbol("PETR4.SA");
        oldQuote.setRegularMarketPrice(36.50);
        cacheManager.getStaleQuoteCache().put("PETR4", oldQuote);

        FailingProvider p1 = new FailingProvider("P1");
        FailingProvider p2 = new FailingProvider("P2");

        ProviderOrchestrator orchestrator = new ProviderOrchestrator(List.of(p1, p2), cacheManager);

        ProviderResult<Map<String, Quote>> result = orchestrator.fetchQuotes(List.of("PETR4"));

        assertTrue(result.isStale());
        assertEquals("STALE_CACHE", result.getProviderName());
        assertTrue(result.getData().containsKey("PETR4"));
        assertEquals(36.50, result.getData().get("PETR4").getRegularMarketPrice());
    }

    @Test
    @DisplayName("Should fallback to next provider when primary returns HTTP 429 Rate Limit")
    void testFallbackOnRateLimit429() {
        StockQuoteProvider rateLimitedProvider = new StockQuoteProvider() {
            @Override public String getName() { return "RATE_LIMITED_YAHOO"; }
            @Override public boolean isEnabled() { return true; }
            @Override public Optional<Quote> fetchQuote(String symbol) { throw new ProviderException("RATE_LIMITED_YAHOO", 429, "Too Many Requests"); }
            @Override public Map<String, Quote> fetchQuotes(List<String> symbols) {
                throw new ProviderException("RATE_LIMITED_YAHOO", 429, "Too Many Requests");
            }
            @Override public List<HistoricalQuote> fetchHistorical(String symbol, LocalDate from, LocalDate to, Interval interval, Range range) {
                return Collections.emptyList();
            }
            @Override public boolean supportsHistorical() { return false; }
        };

        SuccessfulProvider secondary = new SuccessfulProvider("BRAPI_RESCUE");
        ProviderOrchestrator orchestrator = new ProviderOrchestrator(List.of(rateLimitedProvider, secondary), cacheManager);

        ProviderResult<Map<String, Quote>> result = orchestrator.fetchQuotes(List.of("VALE3"));

        assertFalse(result.isFromCache());
        assertEquals("BRAPI_RESCUE (fallback)", result.getProviderName());
        assertTrue(result.getData().containsKey("VALE3"));
        assertEquals(1, secondary.callCount.get());
    }

    @Test
    @DisplayName("Should fallback to next provider when primary encounters network timeout")
    void testFallbackOnTimeout() {
        StockQuoteProvider timeoutProvider = new StockQuoteProvider() {
            @Override public String getName() { return "TIMEOUT_TRADINGVIEW"; }
            @Override public boolean isEnabled() { return true; }
            @Override public Optional<Quote> fetchQuote(String symbol) {
                throw new ProviderException("TIMEOUT_TRADINGVIEW", "Request timed out", new java.util.concurrent.TimeoutException());
            }
            @Override public Map<String, Quote> fetchQuotes(List<String> symbols) {
                throw new ProviderException("TIMEOUT_TRADINGVIEW", "Request timed out", new java.util.concurrent.TimeoutException());
            }
            @Override public List<HistoricalQuote> fetchHistorical(String symbol, LocalDate from, LocalDate to, Interval interval, Range range) {
                return Collections.emptyList();
            }
            @Override public boolean supportsHistorical() { return false; }
        };

        SuccessfulProvider secondary = new SuccessfulProvider("YAHOO_FALLBACK");
        ProviderOrchestrator orchestrator = new ProviderOrchestrator(List.of(timeoutProvider, secondary), cacheManager);

        ProviderResult<Map<String, Quote>> result = orchestrator.fetchQuotes(List.of("ITUB4"));

        assertFalse(result.isFromCache());
        assertEquals("YAHOO_FALLBACK (fallback)", result.getProviderName());
        assertTrue(result.getData().containsKey("ITUB4"));
        assertEquals(1, secondary.callCount.get());
    }

    private static class FailingProvider implements StockQuoteProvider {
        final String name;
        final AtomicInteger callCount = new AtomicInteger();

        FailingProvider(String name) {
            this.name = name;
        }

        @Override public String getName() { return name; }
        @Override public boolean isEnabled() { return true; }
        @Override public Optional<Quote> fetchQuote(String symbol) { throw new RuntimeException("500 Failure"); }
        @Override public Map<String, Quote> fetchQuotes(List<String> symbols) {
            callCount.incrementAndGet();
            throw new RuntimeException("Provider connection failed");
        }
        @Override public List<HistoricalQuote> fetchHistorical(String symbol, LocalDate from, LocalDate to, Interval interval, Range range) {
            throw new RuntimeException("Historical failed");
        }
        @Override public boolean supportsHistorical() { return false; }
    }

    private static class SuccessfulProvider implements StockQuoteProvider {
        final String name;
        final AtomicInteger callCount = new AtomicInteger();

        SuccessfulProvider(String name) {
            this.name = name;
        }

        @Override public String getName() { return name; }
        @Override public boolean isEnabled() { return true; }
        @Override public Optional<Quote> fetchQuote(String symbol) { return Optional.of(makeQuote(symbol)); }
        @Override public Map<String, Quote> fetchQuotes(List<String> symbols) {
            callCount.incrementAndGet();
            Map<String, Quote> map = new HashMap<>();
            for (String s : symbols) {
                map.put(s, makeQuote(s));
            }
            return map;
        }
        @Override public List<HistoricalQuote> fetchHistorical(String symbol, LocalDate from, LocalDate to, Interval interval, Range range) {
            return Collections.emptyList();
        }
        @Override public boolean supportsHistorical() { return false; }

        private Quote makeQuote(String sym) {
            Quote q = new Quote();
            q.setSymbol(sym);
            q.setRegularMarketPrice(50.0);
            return q;
        }
    }
}
