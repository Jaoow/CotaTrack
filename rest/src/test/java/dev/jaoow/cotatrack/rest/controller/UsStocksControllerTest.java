package dev.jaoow.cotatrack.rest.controller;

import dev.jaoow.cotatrack.api.cache.BatchQuoteCoalescer;
import dev.jaoow.cotatrack.api.cache.CotaTrackCacheManager;
import dev.jaoow.cotatrack.api.classification.AssetMarket;
import dev.jaoow.cotatrack.api.provider.ProviderException;
import dev.jaoow.cotatrack.api.provider.QuoteMetadata;
import dev.jaoow.cotatrack.api.provider.StockQuoteProvider;
import dev.jaoow.cotatrack.api.provider.orchestrator.ProviderOrchestrator;
import dev.jaoow.cotatrack.api.requests.dividends.model.CashDividend;
import dev.jaoow.cotatrack.api.requests.dividends.model.DividendsData;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.HistoricalQuote;
import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;
import dev.jaoow.cotatrack.api.requests.quotes.model.StockInstrument;
import dev.jaoow.cotatrack.rest.config.StocksConfiguration;
import dev.jaoow.cotatrack.rest.dto.QueryResponse;
import dev.jaoow.cotatrack.rest.service.StockQueryService;
import dev.jaoow.cotatrack.rest.service.StockSearchService;
import dev.jaoow.cotatrack.rest.service.UsStocksService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.*;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UsStocksControllerTest {
    private StockQuoteProvider provider;
    private CotaTrackCacheManager cache;
    private ProviderOrchestrator orchestrator;
    private MockMvc mvc;
    private StockQueryService b3Service;

    @BeforeEach
    void setUp() throws Exception {
        cache = new CotaTrackCacheManager();
        provider = mock(StockQuoteProvider.class);
        when(provider.getMarket()).thenReturn(AssetMarket.US);
        when(provider.getName()).thenReturn("TWELVE_DATA");
        when(provider.isEnabled()).thenReturn(true);
        when(provider.fetchQuote(anyString())).thenAnswer(invocation ->
                Optional.of(Quote.builder().symbol(invocation.getArgument(0)).currency("USD")
                        .regularMarketPrice(200.5).regularMarketVolume(3_000_000_000L)
                        .metadata(QuoteMetadata.builder().market("US").provider("TWELVE_DATA")
                                .feed("TWELVE_DATA_US_DEFAULT").mode("REALTIME_PARTIAL").build()).build()));
        orchestrator = new ProviderOrchestrator(AssetMarket.US, List.of(provider), cache, new BatchQuoteCoalescer());
        b3Service = mock(StockQueryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new UsStocksController(new UsStocksService(orchestrator)),
                        new StockQueryController(b3Service, mock(StockSearchService.class)))
                .setControllerAdvice(new ExceptionHandler(), new ExceptionHandler.StocksExceptionHandler()).build();
    }

    @Test
    void handlesProviderFailuresThroughControllerAdvice() throws Exception {
        UsStocksService service = mock(UsStocksService.class);
        MockMvc adviceMvc = MockMvcBuilders.standaloneSetup(new UsStocksController(service))
                .setControllerAdvice(new ExceptionHandler(), new ExceptionHandler.StocksExceptionHandler()).build();

        when(service.list(null, null, null, "asc", 20, 0))
                .thenThrow(new ProviderException("TWELVE_DATA", 404, "provider details"))
                .thenThrow(new ProviderException("TWELVE_DATA", 429, "quota"));

        adviceMvc.perform(get("/api/stocks/list")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is(true)))
                .andExpect(jsonPath("$.message", is("US stock not found.")));
        adviceMvc.perform(get("/api/stocks/list")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error", is(true)))
                .andExpect(jsonPath("$.message", is("US market data provider unavailable.")));
    }

    @Test
    void handlesInvalidParameterTypesThroughControllerAdvice() throws Exception {
        for (String path : List.of("/api/stocks/AAPL?from=not-a-date", "/api/stocks/list?page=invalid")) {
            mvc.perform(get(path)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", is(true)))
                    .andExpect(jsonPath("$.message", is("Invalid STOCKS request parameter.")));
        }
        verify(provider, never()).fetchQuote(anyString());
        verify(provider, never()).fetchCatalog();
    }

    @Test
    void exposesUsQuotesAndPerSymbolCacheMetadataWithoutB3Fields() throws Exception {
        mvc.perform(get("/api/stocks/aapl,MSFT,AAPL")).andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(2)))
                .andExpect(jsonPath("$.results[0].symbol", is("AAPL")))
                .andExpect(jsonPath("$.results[0].currency", is("USD")))
                .andExpect(jsonPath("$.results[0].metadata").doesNotExist())
                .andExpect(jsonPath("$.metadata.AAPL.market", is("US")))
                .andExpect(jsonPath("$.metadata.AAPL.fromCache", is(false)))
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.elapsedTime", instanceOf(String.class)))
                .andExpect(jsonPath("$.requestedAt").exists());
        mvc.perform(get("/api/stocks/AAPL")).andExpect(status().isOk())
                .andExpect(jsonPath("$.metadata.AAPL.fromCache", is(true)));
        verify(provider, times(1)).fetchQuote("AAPL");
        verifyNoInteractions(b3Service);
    }

    @Test
    void returnsConfirmed404Unavailable503AndExplicitPartialErrors() throws Exception {
        doReturn(Optional.empty()).when(provider).fetchQuote("MISSING");
        mvc.perform(get("/api/stocks/MISSING")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is(true)));
        doThrow(new ProviderException("TWELVE_DATA", 429, "quota")).when(provider).fetchQuote("MSFT");
        mvc.perform(get("/api/stocks/MSFT")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message", not(containsString("quota"))));
        mvc.perform(get("/api/stocks/AAPL,MISSING")).andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].symbol", is("MISSING")))
                .andExpect(jsonPath("$.errors[0].status", is(404)));
    }

    @Test
    void validatesUsSymbolsIntervalsAndPaginationBeforeProviderCalls() throws Exception {
        for (String path : List.of("/api/stocks/PETR4.SA", "/api/stocks/AAPL,",
                "/api/stocks/list?sortBy=volume", "/api/stocks/list?limit=0",
                "/api/stocks/list?limit=51", "/api/stocks/list?page=-1",
                "/api/stocks/list?sortOrder=random", "/api/stocks/list?type=etf",
                "/api/stocks/AAPL?interval=invalid", "/api/stocks/AAPL?range=invalid",
                "/api/stocks/AAPL?from=not-a-date", "/api/stocks/list?page=invalid",
                "/api/stocks/AAPL?from=2026-10-20&to=2026-10-01")) {
            mvc.perform(get(path)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", is(true)));
        }
        verify(provider, never()).fetchQuote(anyString());
        verify(provider, never()).fetchCatalog();
    }

    @Test
        void includesHistoryAndDividendsWithoutChangingCachedBase() throws Exception {
        when(provider.supportsHistorical()).thenReturn(true);
        when(provider.supportsDividends()).thenReturn(true);
        when(provider.fetchHistorical(eq("AAPL"), any(), any(), any(), any())).thenReturn(
                List.of(new HistoricalQuote(1772476200, BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.TEN, BigDecimal.TEN, null, 20L)));
        CashDividend event = new CashDividend();
        event.setExDate("2026-02-10");
        event.setRate("0.25");
        event.setCurrency("USD");
        when(provider.fetchDividends("AAPL")).thenReturn(new DividendsData(List.of(event), List.of()));
        mvc.perform(get("/api/stocks/AAPL").param("includeDividends", "true").param("interval", "1d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].historicalQuotes", hasSize(1)))
                .andExpect(jsonPath("$.results[0].dividends.cashDividends[0].exDate", is("2026-02-10")))
                .andExpect(jsonPath("$.results[0].twoHundredDayAverage").doesNotExist());
        mvc.perform(get("/api/stocks/AAPL")).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].historicalQuotes").doesNotExist())
                .andExpect(jsonPath("$.results[0].dividends").doesNotExist());
        verify(provider, times(1)).fetchDividends("AAPL");
    }

    @Test
    void listsSortedCatalogBeforePaginationAndUsesLongVolumes() throws Exception {
        when(provider.supportsCatalog()).thenReturn(true);
        when(provider.fetchCatalog()).thenReturn(List.of(
                new StockInstrument("MSFT", "Microsoft", "USD", "NASDAQ", "XNGS", "Common Stock"),
                new StockInstrument("AAPL", "Apple", "USD", "NASDAQ", "XNGS", "Common Stock"),
                new StockInstrument("BRK.B", "Berkshire", "USD", "NYSE", "XNYS", "Common Stock")));
        mvc.perform(get("/api/stocks/list?limit=1&page=1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.results[0].name", is("BRK.B")))
                .andExpect(jsonPath("$.results[0].volume", is(3_000_000_000L)))
                .andExpect(jsonPath("$.currentPage", is(2)))
                .andExpect(jsonPath("$.totalPages", is(3)))
                .andExpect(jsonPath("$.totalCount", is(3)))
                .andExpect(jsonPath("$.hasNextPage", is(true)));
        verify(provider, never()).fetchQuote("AAPL");
        verify(provider, never()).fetchQuote("MSFT");
        mvc.perform(get("/api/stocks/list?search=apple&limit=1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount", is(1)))
                .andExpect(jsonPath("$.results[0].name", is("AAPL")));
        mvc.perform(get("/api/stocks/list?page=2147483646&limit=50"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.results", hasSize(0)));
        verify(provider, times(1)).fetchCatalog();
    }

    @Test
    void leavesExistingB3ContractAndRoutingUntouched() throws Exception {
        when(b3Service.fetchStockQuotes(eq("PETR4"), isNull(), isNull(), isNull(), isNull(), eq(false)))
                .thenReturn(new QueryResponse<>(List.of(Quote.builder().symbol("PETR4.SA").currency("BRL").build()), 1));
        mvc.perform(get("/api/quote/PETR4")).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].symbol", is("PETR4.SA")))
                .andExpect(jsonPath("$.metadata").doesNotExist())
                .andExpect(jsonPath("$.errors").doesNotExist());
        verify(provider, never()).fetchQuote(anyString());
        doThrow(new ProviderException("BRAPI", "old error")).when(b3Service)
                .fetchStockQuotes(eq("PETR4"), isNull(), isNull(), isNull(), isNull(), eq(false));
        mvc.perform(get("/api/quote/PETR4")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message", is("Internal Server Error")));
    }

    @Test
    void configDefaultsToDisabledAndExposesUsDiagnosticsWithoutSecrets() {
        var configuration = new StocksConfiguration();
        var disabled = configuration.usProviderOrchestrator(cache, new MockEnvironment());
        assertEquals(AssetMarket.US, disabled.getMarket());
        assertFalse(disabled.getProviders().get(0).isEnabled());
        var diagnostics = new DiagnosticsController(new ProviderOrchestrator(List.of()), cache);
        diagnostics.setUsOrchestrator(disabled);
        var us = diagnostics.getDiagnostics().getProviders().stream()
                .filter(p -> p.getName().equals("TWELVE_DATA")).findFirst().orElseThrow();
        assertEquals("US", us.getMarket());
        assertEquals("DISABLED", us.getStatus());
        assertThrows(IllegalArgumentException.class, () -> configuration.usProviderOrchestrator(cache,
                new MockEnvironment().withProperty("cotatrack.stocks.enabled", "true")));
    }
}
