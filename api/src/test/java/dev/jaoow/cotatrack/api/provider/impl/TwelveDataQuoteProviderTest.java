package dev.jaoow.cotatrack.api.provider.impl;

import dev.jaoow.cotatrack.api.cache.CotaTrackCacheManager;
import dev.jaoow.cotatrack.api.provider.ProviderException;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.Interval;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.Range;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TwelveDataQuoteProviderTest {
    private HttpClient client;
    private CotaTrackCacheManager cache;
    private Map<String, String> bodies;
    private List<HttpRequest> requests;
    private int status;
    private boolean timeout;

    private static final String CATALOG = """
            {"data":[
              {"symbol":"AAPL","name":"Apple Inc.","currency":"USD","exchange":"NASDAQ","type":"Common Stock"},
              {"symbol":"BRK.B","name":"Berkshire B","currency":"USD","exchange":"NYSE","type":"Common Stock"},
              {"symbol":"CRM","name":"Salesforce","currency":"USD","exchange":"NYSE","type":"Common Stock"},
              {"symbol":"O","name":"Realty Income","currency":"USD","exchange":"NYSE","type":"REIT"},
              {"symbol":"SPY","name":"ETF","currency":"USD","exchange":"NYSE Arca","type":"ETF"},
              {"symbol":"OTC","name":"OTC","currency":"USD","exchange":"OTC","type":"Common Stock"},
              {"symbol":"PETR4","name":"B3","currency":"BRL","exchange":"BVMF","type":"Common Stock"}]}
            """;
    private static final String QUOTE = """
            {"symbol":"AAPL","currency":"USD","close":"200.5","open":"199.5","high":"201",
             "low":"198","previous_close":"200","change":"0.5","percent_change":"0.25",
             "volume":"3000000000","timestamp":1772476200,"last_quote_at":1772480000,
             "fifty_two_week":{"low":"150","high":"250"}}
            """;

    @BeforeEach
    void setUp() throws Exception {
        client = mock(HttpClient.class);
        cache = new CotaTrackCacheManager();
        bodies = new HashMap<>();
        bodies.put("stocks", CATALOG);
        bodies.put("quote", QUOTE);
        requests = new ArrayList<>();
        status = 200;
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenAnswer(call -> {
                    HttpRequest request = call.getArgument(0);
                    requests.add(request);
                    if (timeout && request.uri().getPath().endsWith("quote")) {
                        throw new HttpTimeoutException("timeout");
                    }
                    String endpoint = request.uri().getPath().substring(1);
                    return new Response(request, status, bodies.getOrDefault(endpoint, "{}"));
                });
    }

    private TwelveDataQuoteProvider provider(boolean fundamentals, int minute, int day) {
        return new TwelveDataQuoteProvider(client, "private-test-key", true, cache,
                URI.create("https://provider.test/"), Duration.ofMillis(100), minute, day,
                fundamentals, TwelveDataQuoteProvider.DEFAULT_CREDITS);
    }

    @Test
    void mapsQuoteWithoutB3AliasesAndKeepsRealTimestampAndLongVolume() throws Exception {
        var provider = provider(false, 1000, 1000);
        var q = provider.fetchQuote(" aapl ").orElseThrow();
        assertEquals("AAPL", q.getSymbol());
        assertEquals("USD", q.getCurrency());
        assertEquals("common", q.getSubType());
        assertEquals("EQUITIES", q.getCategory());
        assertEquals(3_000_000_000L, q.getRegularMarketVolume());
        assertEquals(1772480000000L, q.getRegularMarketTime().getTime());
        assertEquals("REALTIME_PARTIAL", q.getMetadata().getMode());
        assertEquals("198.00 - 201.00", q.getRegularMarketDayRange());
        assertNull(q.getMarketCap());
        assertNull(q.getDividendRate());
        for (HttpRequest request : requests) {
            assertEquals("apikey private-test-key", request.headers().firstValue("Authorization").orElseThrow());
            assertEquals(Duration.ofMillis(100), request.timeout().orElseThrow());
            assertFalse(request.uri().toString().contains("private-test-key"));
            assertFalse(request.uri().toString().contains(".SA"));
        }
    }

    @Test
    void filtersAndCachesUsCatalogWithoutEtfsOrOtc() throws Exception {
        var provider = provider(false, 1000, 1000);
        assertEquals(List.of("AAPL", "BRK.B", "CRM", "O"),
                provider.fetchCatalog().stream().map(i -> i.symbol()).toList());
        provider.fetchCatalog();
        assertTrue(provider.fetchQuote("MISSING").isEmpty());
        assertEquals(1, requests.size());
    }

    @Test
    void noInventedHighLowOrClassificationByBrazilianPrefix() throws Exception {
        bodies.put("quote", """
                {"symbol":"CRM","currency":"USD","close":"100","timestamp":1772476200}
                """);
        var q = provider(false, 1000, 1000).fetchQuote("CRM").orElseThrow();
        assertEquals("EQUITIES", q.getCategory());
        assertEquals("common", q.getSubType());
        assertNull(q.getRegularMarketDayHigh());
        assertNull(q.getRegularMarketDayLow());
        assertNull(q.getRegularMarketDayRange());
    }

    @Test
    void mapsFundamentalsAndSeparatesForwardAndTrailingYield() throws Exception {
        bodies.put("profile", "{\"sector\":\"Technology\",\"industry\":\"Hardware\"}");
        bodies.put("logo", "{\"url\":\"https://images.test/apple.png\"}");
        bodies.put("statistics", """
                {"statistics":{"valuations_metrics":{"market_capitalization":4000000000000},
                 "stock_price_summary":{"day_200_ma":180},
                 "stock_statistics":{"avg_10_volume":3000000000.5},
                 "dividends_and_splits":{"forward_annual_dividend_rate":2,
                 "forward_annual_dividend_yield":0.01,"trailing_annual_dividend_rate":1.5,
                 "trailing_annual_dividend_yield":0.0075}}}
                """);
        var provider = provider(true, 1000, 1000);
        var q = provider.fetchQuote("AAPL").orElseThrow();
        assertEquals(4_000_000_000_000L, q.getMarketCap());
        assertEquals(3_000_000_000L, q.getAverageDailyVolume10Day());
        assertEquals(1.0, q.getDividendYield());
        assertEquals(0.75, q.getTrailingAnnualDividendYield());
        assertEquals(2, q.getDividendRate());
        assertEquals(1.5, q.getTrailingAnnualDividendRate());
        provider.fetchQuote("AAPL");
        assertEquals(1, requests.stream().filter(r -> r.uri().getPath().equals("/statistics")).count());
    }

    @Test
    void mapsDailyHistoryInExchangeTimezoneWithoutFabricatingAdjustedClose() throws Exception {
        bodies.put("time_series", """
                {"meta":{"symbol":"AAPL","currency":"USD"},"values":[
                 {"datetime":"2026-03-10","open":"100","high":"102","low":"99","close":"101","volume":"20"},
                 {"datetime":"2026-03-06","open":"99","high":"100","low":"98","close":"99","volume":"10"}]}
                """);
        var history = provider(false, 1000, 1000).fetchHistorical("AAPL",
                LocalDate.of(2026, 3, 6), LocalDate.of(2026, 3, 10), Interval.ONE_DAY, null);
        assertEquals(2, history.size());
        assertTrue(history.get(0).getDate() < history.get(1).getDate());
        assertNull(history.get(0).getAdjClose());
        assertEquals(LocalDate.of(2026, 3, 6).atStartOfDay(ZoneId.of("America/New_York")).toEpochSecond(),
                history.get(0).getDate());
        assertEquals(LocalDate.of(2026, 3, 10).atStartOfDay(ZoneId.of("America/New_York")).toEpochSecond(),
                history.get(1).getDate());
        HttpRequest historicalRequest = requests.stream()
                .filter(r -> r.uri().getPath().equals("/time_series")).findFirst().orElseThrow();
        assertTrue(historicalRequest.uri().getQuery().contains("adjust=none"));
        assertTrue(historicalRequest.uri().getQuery().contains("23:59:59"));
        assertTrue(historicalRequest.uri().getQuery().contains("exchange=NASDAQ"));
    }

    @Test
    void rejectsUnsupportedAndUnboundedHistoryBeforeNetworkCalls() {
        var provider = provider(false, 1000, 1000);
        assertThrows(IllegalArgumentException.class,
                () -> provider.fetchHistorical("AAPL", null, null, Interval.TWO_MINUTES, Range.ONE_DAY));
        assertThrows(IllegalArgumentException.class,
                () -> provider.validateHistorical(null, null, Interval.ONE_DAY, Range.MAX));
        assertThrows(IllegalArgumentException.class,
                () -> provider.validateHistorical(null, null, Interval.ONE_MINUTE, Range.ONE_YEAR));
        assertThrows(IllegalArgumentException.class,
                () -> provider.validateHistorical(LocalDate.now().minusDays(10), null, null, Range.ONE_YEAR));
        assertTrue(requests.isEmpty());
    }

    @Test
    void mapsDividendExDateWithoutInventingComDateAndKeepsSplitFactors() throws Exception {
        bodies.put("dividends", """
                {"meta":{"symbol":"AAPL","currency":"USD"},"dividends":[{"ex_date":"2026-02-10","amount":0.25}]}
                """);
        bodies.put("splits", """
                {"meta":{"symbol":"AAPL","currency":"USD"},"splits":[
                 {"date":"2020-08-31","from_factor":4,"to_factor":1,"description":"4-for-1 split"}]}
                """);
        var result = provider(false, 1000, 1000).fetchDividends("AAPL");
        var cash = result.getCashDividends().get(0);
        assertEquals("2026-02-10", cash.getExDate());
        assertEquals("0.25", cash.getRate());
        assertEquals("USD", cash.getCurrency());
        assertNull(cash.getLastDatePrior());
        assertNull(cash.getPaymentDate());
        assertNull(cash.getAssetIssued());
        var split = result.getStockDividends().get(0);
        assertEquals("SPLIT", split.getEventType());
        assertEquals(4, split.getFromFactor().intValueExact());
        assertEquals(1, split.getToFactor().intValueExact());
        assertNull(split.getFactor());
    }

    @Test
    void enforcesWeightedMinuteAndDailyQuotasWithoutSendingRejectedCalls() throws Exception {
        var daily = provider(false, 100, 2);
        daily.fetchQuote("AAPL");
        assertEquals(429, assertThrows(ProviderException.class, () -> daily.fetchQuote("AAPL")).getStatusCode());
        assertEquals(2, requests.size());
        requests.clear();
        var minute = provider(false, 1, 100);
        minute.fetchQuote("AAPL");
        assertEquals(429, assertThrows(ProviderException.class, () -> minute.fetchQuote("AAPL")).getStatusCode());
        assertEquals(1, requests.size());
        requests.clear();
        assertEquals(429, assertThrows(ProviderException.class, () -> minute.fetchDividends("AAPL")).getStatusCode());
        assertTrue(requests.isEmpty());
    }

    @Test
    void reportsTimeoutAuthAndPayloadErrorsWithoutLeakingKey() throws Exception {
        var provider = provider(false, 1000, 1000);
        provider.fetchCatalog();
        timeout = true;
        ProviderException error = assertThrows(ProviderException.class, () -> provider.fetchQuote("AAPL"));
        assertEquals(503, error.getStatusCode());
        assertFalse(error.getMessage().contains("private-test-key"));
        timeout = false;
        status = 401;
        assertEquals(401, assertThrows(ProviderException.class, () -> provider.fetchQuote("AAPL")).getStatusCode());
        status = 200;
        bodies.put("quote", "{\"status\":\"error\",\"code\":429,\"message\":\"private-test-key\"}");
        error = assertThrows(ProviderException.class, () -> provider.fetchQuote("AAPL"));
        assertEquals(429, error.getStatusCode());
        assertFalse(error.getMessage().contains("private-test-key"));
        bodies.put("quote", "not json");
        assertEquals(502, assertThrows(ProviderException.class, () -> provider.fetchQuote("AAPL")).getStatusCode());
    }

    @Test
    void rejectsWrongInstrumentAndInvalidNumbers() throws Exception {
        bodies.put("quote", QUOTE.replace("\"AAPL\"", "\"MSFT\""));
        var provider = provider(false, 1000, 1000);
        assertThrows(ProviderException.class, () -> provider.fetchQuote("AAPL"));
        bodies.put("quote", QUOTE.replace("\"200.5\"", "\"NaN\""));
        assertThrows(ProviderException.class, () -> provider.fetchQuote("AAPL"));
        bodies.put("quote", QUOTE.replace("\"3000000000\"", "\"9223372036854775808\""));
        assertThrows(ProviderException.class, () -> provider.fetchQuote("AAPL"));
    }

    @Test
    void boundsIntradayWindowsAndRejectsPotentialTruncation() throws Exception {
        bodies.put("time_series", "{\"meta\":{\"symbol\":\"AAPL\",\"currency\":\"USD\"},\"values\":[]}");
        var provider = provider(false, 1000, 1000);
        assertTrue(provider.fetchHistorical("AAPL", LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 11), Interval.ONE_MINUTE, null).isEmpty());
        List<HttpRequest> historyRequests = requests.stream()
                .filter(r -> r.uri().getPath().equals("/time_series")).toList();
        assertEquals(3, historyRequests.size());
        assertTrue(historyRequests.get(0).uri().getQuery().contains("end_date=2026-03-05"));
        assertTrue(historyRequests.get(1).uri().getQuery().contains("start_date=2026-03-06"));
        String row = "{\"datetime\":\"2026-03-01\",\"close\":\"1\"}";
        bodies.put("time_series", "{\"meta\":{\"symbol\":\"AAPL\",\"currency\":\"USD\"},\"values\":["
                + String.join(",", Collections.nCopies(5000, row)) + "]}");
        assertEquals(502, assertThrows(ProviderException.class, () -> provider.fetchHistorical("AAPL",
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 1), Interval.ONE_MINUTE, null)).getStatusCode());
    }

    @Test
    void sharedCacheClearAlsoInvalidatesCatalogAndFundamentals() throws Exception {
        bodies.put("profile", "{\"sector\":\"Technology\"}");
        bodies.put("statistics", "{\"statistics\":{}}");
        bodies.put("logo", "{}");
        var provider = provider(true, 1000, 1000);
        provider.fetchQuote("AAPL");
        assertEquals(1, cache.getStockFundamentalsCache().estimatedSize());
        cache.clearAll();
        provider.fetchQuote("AAPL");
        assertEquals(2, requests.stream().filter(r -> r.uri().getPath().equals("/profile")).count());
        assertEquals(2, requests.stream().filter(r -> r.uri().getPath().equals("/stocks")).count());
    }

    @Test
    void requiresKeyOnlyWhenEnabled() {
        assertThrows(IllegalArgumentException.class, () -> new TwelveDataQuoteProvider(client, "", true, cache));
        var disabled = new TwelveDataQuoteProvider(client, "", false, cache);
        assertFalse(disabled.isEnabled());
        assertThrows(ProviderException.class, disabled::fetchCatalog);
        assertTrue(requests.isEmpty());
    }

    private record Response(HttpRequest request, int statusCode, String body) implements HttpResponse<String> {
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
