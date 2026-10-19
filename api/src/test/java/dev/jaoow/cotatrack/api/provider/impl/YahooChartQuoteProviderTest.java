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
class YahooChartQuoteProviderTest {

    @Mock
    private HttpClient mockHttpClient;

    @Mock
    private HttpResponse<String> mockResponse;

    private static final String RECORDED_YAHOO_CHART_RESPONSE = """
            {
              "chart": {
                "result": [
                  {
                    "meta": {
                      "currency": "BRL",
                      "symbol": "PETR4.SA",
                      "exchangeName": "SAO",
                      "fullExchangeName": "São Paulo",
                      "instrumentType": "EQUITY",
                      "regularMarketPrice": 38.50,
                      "regularMarketDayHigh": 39.15,
                      "regularMarketDayLow": 38.40,
                      "regularMarketVolume": 25000000,
                      "chartPreviousClose": 38.98,
                      "shortName": "PETROBRAS PN",
                      "longName": "Petróleo Brasileiro S.A. - Petrobras"
                    },
                    "timestamp": [1700000000],
                    "indicators": {
                      "quote": [
                        {
                          "open": [38.98],
                          "high": [39.15],
                          "low": [38.40],
                          "close": [38.50],
                          "volume": [25000000]
                        }
                      ]
                    }
                  }
                ],
                "error": null
              }
            }
            """;

    @Test
    @DisplayName("Should parse recorded Yahoo v8 chart response correctly")
    void testParseYahooChartResponse() throws Exception {
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(RECORDED_YAHOO_CHART_RESPONSE);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        YahooChartQuoteProvider provider = new YahooChartQuoteProvider(mockHttpClient, true);

        Optional<Quote> quoteOpt = provider.fetchQuote("PETR4");
        assertTrue(quoteOpt.isPresent());

        Quote q = quoteOpt.get();
        assertEquals("PETR4.SA", q.getSymbol());
        assertEquals("PETROBRAS PN", q.getShortName());
        assertEquals(38.50, q.getRegularMarketPrice());
        assertEquals(38.98, q.getRegularMarketOpen());
        assertEquals(39.15, q.getRegularMarketDayHigh());
        assertEquals(38.40, q.getRegularMarketDayLow());
        assertEquals(25000000L, q.getRegularMarketVolume());
        assertEquals("BRL", q.getCurrency());
    }

    @Test
    @DisplayName("Should throw ProviderException on HTTP 429 Too Many Requests")
    void testHttp429ThrowsProviderException() throws Exception {
        when(mockResponse.statusCode()).thenReturn(429);
        when(mockResponse.body()).thenReturn("Too Many Requests");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        YahooChartQuoteProvider provider = new YahooChartQuoteProvider(mockHttpClient, true);

        ProviderException ex = assertThrows(ProviderException.class, () -> provider.fetchQuote("PETR4"));
        assertEquals(429, ex.getStatusCode());
    }
}
