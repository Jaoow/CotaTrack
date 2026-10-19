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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProviderOrchestratorBatchCacheTest {

    private CotaTrackCacheManager cacheManager;
    private MockProvider mockProvider;
    private ProviderOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        CacheConfig config = CacheConfig.builder()
                .quoteIntradayTtl(Duration.ofMinutes(5))
                .historyDailyTtl(Duration.ofHours(12))
                .staleTtl(Duration.ofHours(24))
                .build();
        cacheManager = new CotaTrackCacheManager(config);
        mockProvider = new MockProvider();
        orchestrator = new ProviderOrchestrator(List.of(mockProvider), cacheManager);
    }

    @Test
    @DisplayName("Should serve consecutive requests from cache without calling provider")
    void testConsecutiveRequestsHitCache() {
        ProviderResult<Map<String, Quote>> result1 = orchestrator.fetchQuotes(List.of("PETR4", "VALE3"));

        assertFalse(result1.isFromCache());
        assertEquals(1, mockProvider.fetchQuotesCallCount.get());
        assertEquals(List.of("PETR4", "VALE3"), mockProvider.lastRequestedBatch);

        // Second call for same symbols: must be served from cache!
        ProviderResult<Map<String, Quote>> result2 = orchestrator.fetchQuotes(List.of("PETR4", "VALE3"));

        assertTrue(result2.isFromCache());
        assertEquals("CACHE", result2.getProviderName());
        assertEquals(1, mockProvider.fetchQuotesCallCount.get(), "Provider must not be called again");
        assertEquals(2, result2.getData().size());
    }

    @Test
    @DisplayName("Should fetch only uncached symbols in a single batch when partial cache exists")
    void testPartialCacheHitsOnlyFetchUncached() {
        // Pre-populate PETR4 in intraday cache
        Quote petr4 = new Quote();
        petr4.setSymbol("PETR4.SA");
        petr4.setRegularMarketPrice(38.0);
        cacheManager.putIntradayQuote("PETR4", petr4);

        // Query PETR4 and two uncached symbols: ITUB4, BBDC4
        ProviderResult<Map<String, Quote>> result = orchestrator.fetchQuotes(List.of("PETR4", "ITUB4", "BBDC4"));

        assertEquals(1, mockProvider.fetchQuotesCallCount.get());
        // Provider must only be called with the 2 uncached symbols!
        assertEquals(List.of("ITUB4", "BBDC4"), mockProvider.lastRequestedBatch);

        Map<String, Quote> data = result.getData();
        assertEquals(3, data.size());
        assertTrue(data.containsKey("PETR4"));
        assertTrue(data.containsKey("ITUB4"));
        assertTrue(data.containsKey("BBDC4"));
    }

    @Test
    @DisplayName("Should cache historical quotes and serve subsequent requests from cache")
    void testHistoricalQuotesCaching() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 6, 1);

        ProviderResult<List<HistoricalQuote>> res1 = orchestrator.fetchHistorical("PETR4", from, to, Interval.ONE_DAY, Range.ONE_MONTH);
        assertNotNull(res1.getData());
        assertEquals(1, res1.getData().size());
        assertEquals(1, mockProvider.fetchHistoricalCallCount.get());

        // Second call with same parameters: must hit historical cache
        ProviderResult<List<HistoricalQuote>> res2 = orchestrator.fetchHistorical("PETR4", from, to, Interval.ONE_DAY, Range.ONE_MONTH);
        assertEquals("CACHE", res2.getProviderName());
        assertEquals(1, mockProvider.fetchHistoricalCallCount.get(), "Provider must not be called again for historical quotes");
    }

    @Test
    @DisplayName("Should rescue failed symbols from 24h stale disaster cache")
    void testStaleDisasterRecovery() {
        // Pre-populate stale cache only (simulating an earlier quote from hours ago)
        Quote staleQuote = new Quote();
        staleQuote.setSymbol("PETR4.SA");
        staleQuote.setRegularMarketPrice(37.50);
        cacheManager.getStaleQuoteCache().put("PETR4", staleQuote);

        // Provider is configured to fail
        mockProvider.shouldFail = true;

        ProviderResult<Map<String, Quote>> result = orchestrator.fetchQuotes(List.of("PETR4"));

        assertTrue(result.isStale());
        assertTrue(result.getData().containsKey("PETR4"));
        assertEquals(37.50, result.getData().get("PETR4").getRegularMarketPrice());
    }

    private static class MockProvider implements StockQuoteProvider {
        final AtomicInteger fetchQuotesCallCount = new AtomicInteger(0);
        final AtomicInteger fetchHistoricalCallCount = new AtomicInteger(0);
        volatile List<String> lastRequestedBatch = Collections.emptyList();
        volatile boolean shouldFail = false;

        @Override
        public String getName() {
            return "MOCK_PROVIDER";
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public Optional<Quote> fetchQuote(String symbol) {
            return Optional.ofNullable(fetchQuotes(List.of(symbol)).get(symbol));
        }

        @Override
        public Map<String, Quote> fetchQuotes(List<String> symbols) {
            fetchQuotesCallCount.incrementAndGet();
            lastRequestedBatch = new ArrayList<>(symbols);
            if (shouldFail) {
                throw new RuntimeException("Mock provider error");
            }
            Map<String, Quote> map = new LinkedHashMap<>();
            for (String sym : symbols) {
                Quote q = new Quote();
                q.setSymbol(sym.endsWith(".SA") ? sym : sym + ".SA");
                q.setRegularMarketPrice(50.0);
                map.put(sym, q);
            }
            return map;
        }

        @Override
        public List<HistoricalQuote> fetchHistorical(String symbol, LocalDate from, LocalDate to, Interval interval, Range range) {
            fetchHistoricalCallCount.incrementAndGet();
            if (shouldFail) {
                throw new RuntimeException("Mock provider error");
            }
            HistoricalQuote hq = new HistoricalQuote(1700000000L, BigDecimal.valueOf(50), BigDecimal.valueOf(49),
                    BigDecimal.valueOf(51), BigDecimal.valueOf(50.5), BigDecimal.valueOf(50.5), 5000L);
            return List.of(hq);
        }

        @Override
        public boolean supportsHistorical() {
            return true;
        }
    }
}
