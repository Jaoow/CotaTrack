package dev.jaoow.cotatrack.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jaoow.cotatrack.api.cache.BatchQuoteCoalescer;
import dev.jaoow.cotatrack.api.cache.CotaTrackCacheManager;
import dev.jaoow.cotatrack.api.classification.AssetMarket;
import dev.jaoow.cotatrack.api.provider.*;
import dev.jaoow.cotatrack.api.provider.enricher.QuoteEnricher;
import dev.jaoow.cotatrack.api.provider.orchestrator.ProviderOrchestrator;
import dev.jaoow.cotatrack.api.requests.dividends.model.DividendsData;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.HistoricalQuote;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.Interval;
import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StocksQueryTest {
    private final CotaTrackCacheManager cache = new CotaTrackCacheManager();

    private StockQuoteProvider provider(String name) {
        StockQuoteProvider provider = mock(StockQuoteProvider.class);
        when(provider.getName()).thenReturn(name);
        when(provider.getMarket()).thenReturn(AssetMarket.US);
        when(provider.isEnabled()).thenReturn(true);
        return provider;
    }

    private ProviderOrchestrator orchestrator(StockQuoteProvider... providers) {
        return new ProviderOrchestrator(AssetMarket.US, List.of(providers), cache, new BatchQuoteCoalescer());
    }

    private Quote quote(String symbol) {
        return Quote.builder().symbol(symbol).currency("USD").regularMarketPrice(200.0)
                .metadata(QuoteMetadata.builder().market("US").provider("PRIMARY")
                        .feed("TEST_PARTIAL").mode("REALTIME_PARTIAL").build()).build();
    }

    @Test
    void isolatesB3CacheAndReturnsCacheMetadataWithoutMutatingOriginal() throws Exception {
        cache.putIntradayQuote("AAPL", Quote.builder().symbol("AAPL.SA").currency("BRL").build());
        var provider = provider("PRIMARY");
        Quote original = quote("AAPL");
        when(provider.fetchQuote("AAPL")).thenReturn(Optional.of(original));
        var orchestrator = orchestrator(provider);
        var first = orchestrator.fetchQuote("AAPL");
        var second = orchestrator.fetchQuote("AAPL");
        assertEquals("USD", first.getData().getCurrency());
        assertFalse(first.isFromCache());
        assertTrue(second.isFromCache());
        assertTrue(second.getData().getMetadata().isFromCache());
        assertFalse(original.getMetadata().isFromCache());
        assertEquals("BRL", cache.getIntradayQuote("AAPL").getCurrency());
        assertNull(cache.getIntradayQuote(AssetMarket.US, "MSFT"));
        assertFalse(cache.getIntradayQuoteCache().asMap().containsKey("US:AAPL.SA"));
        verify(provider, times(1)).fetchQuote("AAPL");
    }

    @Test
    void fallsBackOnTimeoutAnd429AndOpensCircuitWithoutCallingB3() throws Exception {
        var primary = provider("PRIMARY");
        var secondary = provider("SECONDARY");
        when(primary.fetchQuote(anyString())).thenThrow(new ProviderException("PRIMARY", 429, "limit"));
        when(secondary.fetchQuote(anyString())).thenAnswer(invocation ->
                Optional.of(quote(invocation.getArgument(0)).toBuilder().metadata(
                        QuoteMetadata.builder().market("US").provider("SECONDARY").build()).build()));
        var orchestrator = orchestrator(primary, secondary);
        for (int i = 0; i < 5; i++) {
            assertNotNull(orchestrator.fetchQuote("SYM" + i).getData());
        }
        assertEquals("OPEN", orchestrator.getCircuitBreakers().get("PRIMARY").getState().name());
        verify(primary, times(3)).fetchQuote(anyString());
        verify(secondary, times(5)).fetchQuote(anyString());
        assertThrows(IllegalArgumentException.class, () -> new ProviderOrchestrator(AssetMarket.US,
                List.of(mock(StockQuoteProvider.class)), cache, new BatchQuoteCoalescer()));
    }

    @Test
    void rescuesStaleQuotesAndDoesNotHideConfirmedNotFound() throws Exception {
        var provider = provider("PRIMARY");
        cache.putIntradayQuote(AssetMarket.US, "AAPL", quote("AAPL"));
        cache.getIntradayQuoteCache().invalidate("US:AAPL");
        when(provider.fetchQuote("AAPL")).thenThrow(new java.net.http.HttpTimeoutException("timeout"));
        var result = orchestrator(provider).fetchQuote("AAPL");
        assertTrue(result.isStale());
        assertTrue(result.isFromCache());
        assertTrue(result.getData().getMetadata().isStale());
        doReturn(Optional.empty()).when(provider).fetchQuote("AAPL");
        result = orchestrator(provider).fetchQuote("AAPL");
        assertNull(result.getData());
        assertEquals(404, result.getError().getStatusCode());
    }

    @Test
    void distinguishesUnavailableMissingAndPartialResults() throws Exception {
        var provider = provider("PRIMARY");
        when(provider.fetchQuote("AAPL")).thenReturn(Optional.of(quote("AAPL")));
        when(provider.fetchQuote("UNKNOWN")).thenReturn(Optional.empty());
        when(provider.fetchQuote("MSFT")).thenThrow(new ProviderException("PRIMARY", 401, "auth"));
        var result = StocksQuery.builder().orchestrator(orchestrator(provider))
                .symbols(List.of("AAPL", "UNKNOWN", "MSFT")).build().fetch();
        assertEquals(1, result.results().size());
        assertEquals(List.of(404, 503), result.errors().stream().map(e -> e.status()).toList());
        assertEquals(List.of("UNKNOWN", "MSFT"), result.errors().stream().map(e -> e.symbol()).toList());
    }

    @Test
    void supplementalFailureIsExplicitAndDoesNotLeakAcrossCachedRequests() throws Exception {
        var provider = provider("PRIMARY");
        when(provider.fetchQuote("AAPL")).thenReturn(Optional.of(quote("AAPL")));
        when(provider.supportsHistorical()).thenReturn(true);
        when(provider.supportsDividends()).thenReturn(true);
        List<HistoricalQuote> history = List.of(new HistoricalQuote(1772476200,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, null, 1L));
        when(provider.fetchHistorical(eq("AAPL"), any(), any(), any(), any())).thenReturn(history);
        when(provider.fetchDividends("AAPL")).thenThrow(new ProviderException("PRIMARY", 403, "plan"));
        var orchestrator = orchestrator(provider);
        var withHistory = StocksQuery.builder().orchestrator(orchestrator).symbols(List.of("AAPL"))
                .interval(Interval.ONE_DAY).includeDividends(true).build().fetch();
        assertEquals(1, withHistory.results().get(0).getHistoricalQuotes().size());
        assertEquals("dividends", withHistory.errors().get(0).operation());
        assertNull(withHistory.results().get(0).getTwoHundredDayAverage());
        var plain = StocksQuery.builder().orchestrator(orchestrator).symbols(List.of("AAPL")).build().fetch();
        assertNull(plain.results().get(0).getHistoricalQuotes());
        assertNull(plain.results().get(0).getDividends());
        assertTrue(plain.errors().isEmpty());
        StocksQuery.builder().orchestrator(orchestrator).symbols(List.of("AAPL"))
                .interval(Interval.ONE_DAY).build().fetch();
        verify(provider, times(1)).fetchHistorical(eq("AAPL"), any(), any(), any(), any());
    }

    @Test
    void cachesSuccessfulEmptyCorporateActionsButNotFailures() throws Exception {
        var provider = provider("PRIMARY");
        when(provider.supportsDividends()).thenReturn(true);
        when(provider.fetchDividends("AAPL")).thenReturn(new DividendsData(List.of(), List.of()));
        var orchestrator = orchestrator(provider);
        assertFalse(orchestrator.fetchDividends("AAPL").isFromCache());
        assertTrue(orchestrator.fetchDividends("AAPL").isFromCache());
        verify(provider, times(1)).fetchDividends("AAPL");
    }

    @Test
    void supplementalFallbackCachesSuccessAndRetriesFailures() throws Exception {
        var primary = provider("PRIMARY");
        var secondary = provider("SECONDARY");
        when(primary.supportsHistorical()).thenReturn(true);
        when(secondary.supportsHistorical()).thenReturn(true);
        when(primary.fetchHistorical(anyString(), any(), any(), any(), any()))
                .thenThrow(new ProviderException("PRIMARY", 429, "limit"));
        when(secondary.fetchHistorical(anyString(), any(), any(), any(), any())).thenReturn(List.of());
        var orchestrator = orchestrator(primary, secondary);
        assertEquals("SECONDARY", orchestrator.fetchHistorical("AAPL", null, null, Interval.ONE_DAY, null).getProviderName());
        assertTrue(orchestrator.fetchHistorical("AAPL", null, null, Interval.ONE_DAY, null).isFromCache());
        verify(primary, times(1)).fetchHistorical(anyString(), any(), any(), any(), any());
        when(primary.supportsDividends()).thenReturn(true);
        when(secondary.supportsDividends()).thenReturn(true);
        when(primary.fetchDividends("AAPL")).thenThrow(new ProviderException("PRIMARY", 403, "plan"));
        when(secondary.fetchDividends("AAPL")).thenThrow(new ProviderException("SECONDARY", 503, "down"));
        assertThrows(ProviderException.class, () -> orchestrator.fetchDividends("AAPL"));
        assertThrows(ProviderException.class, () -> orchestrator.fetchDividends("AAPL"));
        assertNull(cache.getDividendsCache().getIfPresent("US:AAPL"));
        verify(secondary, times(2)).fetchDividends("AAPL");
    }

    @Test
    void rejectsLegacyB3FetchesOnUsQuotesAndKeepsLegacyEventJsonShape() throws Exception {
        Quote us = quote("AAPL");
        assertThrows(IllegalStateException.class, us::fetchDividendsData);
        assertThrows(IllegalStateException.class, () -> us.fetchHistoricalQuotes(
                dev.jaoow.cotatrack.api.requests.historicalquotes.HistoricalQuotesRequest.builder()
                        .symbol("AAPL").build()));
        var legacyCash = new dev.jaoow.cotatrack.api.requests.dividends.model.CashDividend(
                "ON", null, "0,25", null, null, null, "DIVIDENDO", null, null);
        var legacyStock = new dev.jaoow.cotatrack.api.requests.dividends.model.StockDividend(
                "ON", "10", null, null, "BONIFICACAO", null, null);
        var mapper = new ObjectMapper();
        String cashJson = mapper.writeValueAsString(legacyCash);
        String stockJson = mapper.writeValueAsString(legacyStock);
        assertFalse(cashJson.contains("exDate"));
        assertFalse(cashJson.contains("currency"));
        assertFalse(stockJson.contains("eventType"));
        assertFalse(stockJson.contains("fromFactor"));
        assertTrue(cashJson.contains("\"paymentDate\":null"));
    }

    @Test
    void doesNotInventIncompleteIndicatorsOrCopyForwardIntoTrailing() throws Exception {
        Quote q = quote("CRM");
        q.setDividendRate(2.0);
        QuoteEnricher.enrich(q, AssetMarket.US);
        assertEquals("common", q.getSubType());
        assertNull(q.getTrailingAnnualDividendRate());
        assertNull(q.getRegularMarketDayHigh());
        List<HistoricalQuote> candles = new ArrayList<>();
        for (int i = 0; i < 252; i++) {
            candles.add(new HistoricalQuote(i, BigDecimal.ONE, BigDecimal.ONE,
                    BigDecimal.TEN, BigDecimal.valueOf(i + 1), null, 100L));
        }
        QuoteEnricher.enrichFromHistory(q, candles.subList(0, 9), AssetMarket.US);
        assertNull(q.getTwoHundredDayAverage());
        assertNull(q.getAverageDailyVolume10Day());
        assertNull(q.getFiftyTwoWeekHigh());
        QuoteEnricher.enrichFromHistory(q, candles, AssetMarket.US);
        assertEquals(152.5, q.getTwoHundredDayAverage());
        assertEquals(100L, q.getAverageDailyVolume3Month());
        String json = new ObjectMapper().writeValueAsString(q);
        assertFalse(json.contains("metadata"));
        assertFalse(json.contains("exchange"));
    }

    @Test
    void coalescesConcurrentUsRequestsAndCleansInFlightOnFailure() throws Exception {
        var coalescer = new BatchQuoteCoalescer();
        var executor = Executors.newFixedThreadPool(2);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Thread> secondWorker = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.function.Supplier<ProviderResult<Quote>> fetch = () -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                assertTrue(release.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return ProviderResult.of(quote("AAPL"), "PRIMARY", 0);
        };
        try {
            Future<ProviderResult<Quote>> first = executor.submit(() -> coalescer.coalesce(AssetMarket.US, "AAPL", fetch));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Future<ProviderResult<Quote>> second = executor.submit(() -> {
                secondWorker.set(Thread.currentThread());
                return coalescer.coalesce(AssetMarket.US, "aapl", fetch);
            });
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                while (secondWorker.get() == null || secondWorker.get().getState() != Thread.State.TIMED_WAITING) {
                    Thread.sleep(1);
                }
            });
            release.countDown();
            assertNotNull(first.get(2, TimeUnit.SECONDS).getData());
            assertNotNull(second.get(2, TimeUnit.SECONDS).getData());
            assertEquals(1, calls.get());
            assertEquals(0, coalescer.getInFlightCount());
            assertThrows(IllegalStateException.class, () -> coalescer.coalesce(AssetMarket.US, "AAPL",
                    () -> { throw new IllegalStateException("failure"); }));
            assertEquals(0, coalescer.getInFlightCount());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
