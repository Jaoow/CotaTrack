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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrapiQuoteProviderTest {

    @Mock
    private HttpClient mockHttpClient;

    @Mock
    private HttpResponse<String> mockResponse;

    private static final String RECORDED_BRAPI_RESPONSE = """
            {
              "results": [
                {
                  "currency": "BRL",
                  "shortName": "PETROBRAS PN",
                  "longName": "Petróleo Brasileiro S.A. - Petrobras",
                  "regularMarketChange": -0.48,
                  "regularMarketChangePercent": -1.25,
                  "regularMarketTime": "2026-10-19T18:00:00.000Z",
                  "regularMarketPrice": 38.50,
                  "regularMarketDayHigh": 39.15,
                  "regularMarketDayRange": "38.40 - 39.15",
                  "regularMarketDayLow": 38.40,
                  "regularMarketVolume": 25000000,
                  "regularMarketPreviousClose": 38.98,
                  "regularMarketOpen": 38.98,
                  "fiftyTwoWeekRange": "28.10 - 42.50",
                  "fiftyTwoWeekLow": 28.10,
                  "fiftyTwoWeekHigh": 42.50,
                  "symbol": "PETR4",
                  "logourl": "https://icons.brapi.dev/icons/PETR4.svg"
                }
              ]
            }
            """;

    @Test
    @DisplayName("Should parse recorded Brapi response correctly")
    void testParseBrapiResponse() throws Exception {
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(RECORDED_BRAPI_RESPONSE);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        BrapiQuoteProvider provider = new BrapiQuoteProvider(mockHttpClient, "mock-token", true);

        Optional<Quote> quoteOpt = provider.fetchQuote("PETR4");
        assertTrue(quoteOpt.isPresent());

        Quote q = quoteOpt.get();
        assertEquals("PETR4.SA", q.getSymbol());
        assertEquals("PETROBRAS PN", q.getShortName());
        assertEquals(38.50, q.getRegularMarketPrice());
        assertEquals("https://icons.brapi.dev/icons/PETR4.svg", q.getLogoUrl());
        assertEquals(28.10, q.getFiftyTwoWeekLow());
        assertEquals(42.50, q.getFiftyTwoWeekHigh());
    }

    @Test
    @DisplayName("Should throw ProviderException on HTTP 401 Unauthorized")
    void testHttp401ThrowsProviderException() throws Exception {
        when(mockResponse.statusCode()).thenReturn(401);
        when(mockResponse.body()).thenReturn("{\"error\": true, \"message\": \"Token inválido\"}");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        BrapiQuoteProvider provider = new BrapiQuoteProvider(mockHttpClient, "invalid-token", true);

        ProviderException ex = assertThrows(ProviderException.class, () -> provider.fetchQuote("PETR4"));
        assertEquals(401, ex.getStatusCode());
    }
}
