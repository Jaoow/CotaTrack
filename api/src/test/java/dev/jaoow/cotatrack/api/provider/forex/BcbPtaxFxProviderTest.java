package dev.jaoow.cotatrack.api.provider.forex;

import dev.jaoow.cotatrack.api.requests.quotes.model.FxQuote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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
class BcbPtaxFxProviderTest {

    @Mock
    private HttpClient mockHttpClient;

    @Mock
    private HttpResponse<String> mockResponse;

    private static final String RECORDED_OLINDA_USD_RESPONSE = """
            {
              "@odata.context": "https://was-p.bcnet.bcb.gov.br/olinda/servico/PTAX/versao/v1/odata$metadata#_CotacaoMoedaPeriodo",
              "value": [
                {
                  "paridadeCompra": 1.00000,
                  "paridadeVenda": 1.00000,
                  "cotacaoCompra": 5.05580,
                  "cotacaoVenda": 5.05640,
                  "dataHoraCotacao": "2026-10-19 13:05:47.20983",
                  "tipoBoletim": "Fechamento"
                }
              ]
            }
            """;

    @Test
    @DisplayName("Should parse recorded Olinda PTAX JSON and return populated FxQuote")
    void testParseRecordedOlindaJson() throws Exception {
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(RECORDED_OLINDA_USD_RESPONSE);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        BcbPtaxFxProvider provider = new BcbPtaxFxProvider(mockHttpClient, true);

        Optional<FxQuote> quoteOpt = provider.fetchFxQuote("USDBRL");

        assertTrue(quoteOpt.isPresent());
        FxQuote quote = quoteOpt.get();
        assertEquals("USDBRL", quote.getSymbol());
        assertEquals("Dólar dos Estados Unidos", quote.getName());
        assertEquals("BRL", quote.getCurrency());
        assertEquals(0, new BigDecimal("5.0564").compareTo(quote.getPrice()));
        assertEquals(0, new BigDecimal("5.0558").compareTo(quote.getBuyPrice()));
        assertEquals(0, new BigDecimal("5.0564").compareTo(quote.getSellPrice()));
        assertEquals("2026-10-19 13:05:47.20983", quote.getQuoteDate());
        assertEquals("BCB_PTAX", quote.getProvider());
    }

    @Test
    @DisplayName("Should handle empty value array and query SGS fallback for USD")
    void testEmptyOlindaFallbackToSgs() throws Exception {
        HttpResponse<String> emptyResponse = org.mockito.Mockito.mock(HttpResponse.class);
        when(emptyResponse.statusCode()).thenReturn(200);
        when(emptyResponse.body()).thenReturn("{\"value\": []}");

        HttpResponse<String> sgsResponse = org.mockito.Mockito.mock(HttpResponse.class);
        when(sgsResponse.statusCode()).thenReturn(200);
        when(sgsResponse.body()).thenReturn("[{\"data\":\"19/10/2026\",\"valor\":\"5.0564\"}]");

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(emptyResponse)
                .thenReturn(sgsResponse);

        BcbPtaxFxProvider provider = new BcbPtaxFxProvider(mockHttpClient, true);

        Optional<FxQuote> quoteOpt = provider.fetchFxQuote("USD");
        assertTrue(quoteOpt.isPresent());
        assertEquals("USD", quoteOpt.get().getSymbol());
        assertEquals(new BigDecimal("5.0564"), quoteOpt.get().getPrice());
        assertEquals("BCB_PTAX_SGS", quoteOpt.get().getProvider());
    }
}
