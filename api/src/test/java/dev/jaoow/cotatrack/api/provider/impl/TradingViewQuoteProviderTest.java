package dev.jaoow.cotatrack.api.provider.impl;

import dev.jaoow.cotatrack.api.provider.ProviderException;
import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradingViewQuoteProviderTest {

    @Mock
    private HttpClient mockHttpClient;

    @Mock
    private HttpResponse<String> mockResponse;

    private static final String FULL_TV_RESPONSE = """
            {
              "totalCount": 1,
              "data": [
                {
                  "s": "BMFBOVESPA:PETR4",
                  "d": [
                    "PETR4",
                    "PETROLEO BRASILEIRO S.A. PETROBRAS",
                    38.50,
                    -1.25,
                    -0.48,
                    38.98,
                    39.15,
                    38.40,
                    25000000,
                    450000000000,
                    "BRL",
                    "BMFBOVESPA",
                    "stock",
                    "common",
                    22000000,
                    24000000,
                    21000000,
                    42.50,
                    28.10,
                    35.20,
                    "petrobras--big",
                    6.5,
                    6.5,
                    2.50,
                    0.80,
                    1792444000
                  ]
                }
              ]
            }
            """;

    private static final String PARTIAL_TV_RESPONSE = """
            {
              "totalCount": 1,
              "data": [
                {
                  "s": "BMFBOVESPA:VALE3",
                  "d": [
                    "VALE3",
                    "VALE S.A.",
                    65.00,
                    0.50,
                    0.77,
                    64.80,
                    65.50,
                    64.50,
                    12000000,
                    null,
                    "BRL",
                    "BMFBOVESPA",
                    "stock",
                    "common",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    1792444000
                  ]
                }
              ]
            }
            """;

    @Test
    @DisplayName("Should parse full TradingView scanner response and map all fields")
    void testParseFullResponse() throws Exception {
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(FULL_TV_RESPONSE);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        TradingViewQuoteProvider provider = new TradingViewQuoteProvider(mockHttpClient, true);

        Optional<Quote> quoteOpt = provider.fetchQuote("PETR4");
        assertTrue(quoteOpt.isPresent());

        Quote q = quoteOpt.get();
        assertEquals("PETR4.SA", q.getSymbol());
        assertEquals("PETR4", q.getShortName());
        assertEquals("PETROLEO BRASILEIRO S.A. PETROBRAS", q.getLongName());
        assertEquals(38.50, q.getRegularMarketPrice());
        assertEquals(-0.48, q.getRegularMarketChange());
        assertEquals(-1.25, q.getRegularMarketChangePercent());
        assertEquals(38.98, q.getRegularMarketOpen());
        assertEquals(39.15, q.getRegularMarketDayHigh());
        assertEquals(38.40, q.getRegularMarketDayLow());
        assertEquals(25000000L, q.getRegularMarketVolume());
        assertEquals(35.20, q.getTwoHundredDayAverage());
        assertEquals(42.50, q.getFiftyTwoWeekHigh());
        assertEquals(28.10, q.getFiftyTwoWeekLow());
        assertEquals("https://s3-symbol-logo.tradingview.com/petrobras--big.svg", q.getLogoUrl());
        assertEquals(6.5, q.getDividendYield());
    }

    @Test
    @DisplayName("Should handle partial response gracefully without throwing NPE")
    void testParsePartialResponse() throws Exception {
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(PARTIAL_TV_RESPONSE);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        TradingViewQuoteProvider provider = new TradingViewQuoteProvider(mockHttpClient, true);

        Optional<Quote> quoteOpt = provider.fetchQuote("VALE3");
        assertTrue(quoteOpt.isPresent());

        Quote q = quoteOpt.get();
        assertEquals("VALE3.SA", q.getSymbol());
        assertEquals(65.00, q.getRegularMarketPrice());
        assertNull(q.getTwoHundredDayAverage());
        assertNull(q.getFiftyTwoWeekHigh());
        assertNull(q.getLogoUrl());
        assertNull(q.getMarketCap());
    }

    @Test
    @DisplayName("Should throw ProviderException on HTTP error status")
    void testHttpErrorThrowsProviderException() throws Exception {
        when(mockResponse.statusCode()).thenReturn(500);
        when(mockResponse.body()).thenReturn("Internal Server Error");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        TradingViewQuoteProvider provider = new TradingViewQuoteProvider(mockHttpClient, true);

        assertThrows(ProviderException.class, () -> provider.fetchQuote("PETR4"));
    }
}
