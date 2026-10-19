package dev.jaoow.cotatrack.api.provider.forex;

import dev.jaoow.cotatrack.api.cache.CacheConfig;
import dev.jaoow.cotatrack.api.cache.CotaTrackCacheManager;
import dev.jaoow.cotatrack.api.requests.quotes.model.FxQuote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FxOrchestratorTest {

    private CotaTrackCacheManager cacheManager;
    private MockFxProvider primaryProvider;
    private MockFxProvider fallbackProvider;
    private FxOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        CacheConfig config = CacheConfig.builder()
                .quoteIntradayTtl(Duration.ofMinutes(5))
                .build();
        cacheManager = new CotaTrackCacheManager(config);
        primaryProvider = new MockFxProvider("BCB_MOCK", false);
        fallbackProvider = new MockFxProvider("YAHOO_MOCK", false);
        orchestrator = new FxOrchestrator(primaryProvider, fallbackProvider, cacheManager);
    }

    @Test
    @DisplayName("Should fetch from primary BCB and serve subsequent calls from cache")
    void testPrimarySuccessAndCaching() {
        Map<String, FxQuote> res1 = orchestrator.fetchQuotes(List.of("USD", "EUR"));
        assertEquals(2, res1.size());
        assertEquals(1, primaryProvider.callCount.get());
        assertEquals(0, fallbackProvider.callCount.get());

        // Subsequent call must hit cache!
        Map<String, FxQuote> res2 = orchestrator.fetchQuotes(List.of("USD", "EUR"));
        assertEquals(2, res2.size());
        assertEquals(1, primaryProvider.callCount.get(), "Primary provider must not be called on cache hit");
        assertEquals(0, fallbackProvider.callCount.get());
    }

    @Test
    @DisplayName("Should automatically fallback to Yahoo when primary BCB fails")
    void testFallbackWhenBcbFails() {
        primaryProvider.shouldFail = true;

        Map<String, FxQuote> result = orchestrator.fetchQuotes(List.of("USD"));

        assertEquals(1, primaryProvider.callCount.get());
        assertEquals(1, fallbackProvider.callCount.get(), "Fallback provider must be called when primary fails");

        assertTrue(result.containsKey("USD"));
        assertEquals("YAHOO_MOCK", result.get("USD").getProvider());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when symbol is not supported")
    void testUnsupportedSymbolThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                orchestrator.fetchQuotes(List.of("INVALID_CURRENCY"))
        );

        assertTrue(ex.getMessage().contains("Moeda ou par não suportado: 'INVALID_CURRENCY'"));
    }

    private static class MockFxProvider implements FxQuoteProvider {
        final String name;
        final AtomicInteger callCount = new AtomicInteger(0);
        volatile boolean shouldFail;

        MockFxProvider(String name, boolean shouldFail) {
            this.name = name;
            this.shouldFail = shouldFail;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public Optional<FxQuote> fetchFxQuote(String symbol) {
            return Optional.ofNullable(fetchFxQuotes(List.of(symbol)).get(symbol));
        }

        @Override
        public Map<String, FxQuote> fetchFxQuotes(List<String> symbols) {
            callCount.incrementAndGet();
            if (shouldFail) {
                throw new RuntimeException("Mock provider error");
            }
            Map<String, FxQuote> map = new HashMap<>();
            for (String sym : symbols) {
                FxQuote q = FxQuote.builder()
                        .symbol(sym)
                        .name("Mock Currency " + sym)
                        .price(BigDecimal.valueOf(5.0))
                        .provider(name)
                        .build();
                map.put(sym, q);
            }
            return map;
        }
    }
}
