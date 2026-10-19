package dev.jaoow.cotatrack.api.provider.forex;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jaoow.cotatrack.api.provider.ProviderException;
import dev.jaoow.cotatrack.api.requests.quotes.FxQuoteRequest;
import dev.jaoow.cotatrack.api.requests.quotes.model.FxQuote;
import dev.jaoow.cotatrack.api.yahoo.YahooConstants;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Fallback foreign exchange provider using Yahoo Finance v8 chart and v7 quote.
 */
@Slf4j
public class YahooFxProvider implements FxQuoteProvider {

    public static final String PROVIDER_NAME = "YAHOO";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(4);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public YahooFxProvider() {
        this(
                HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(),
                Boolean.parseBoolean(System.getProperty("cotatrack.providers.yahoo-fx.enabled", "true"))
        );
    }

    public YahooFxProvider(HttpClient httpClient, boolean enabled) {
        this.httpClient = httpClient;
        this.enabled = enabled;
        this.objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public String getName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public Optional<FxQuote> fetchFxQuote(String symbol) throws Exception {
        Map<String, FxQuote> map = fetchFxQuotes(Collections.singletonList(symbol));
        return Optional.ofNullable(map.get(symbol));
    }

    @Override
    public Map<String, FxQuote> fetchFxQuotes(List<String> symbols) throws Exception {
        if (symbols == null || symbols.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, FxQuote> results = new LinkedHashMap<>();

        for (String rawSym : symbols) {
            Optional<BcbCurrencyMapper.CurrencyInfo> infoOpt = BcbCurrencyMapper.mapSymbol(rawSym);
            String ticker = infoOpt.map(BcbCurrencyMapper.CurrencyInfo::yahooTicker).orElse(rawSym);
            String name = infoOpt.map(BcbCurrencyMapper.CurrencyInfo::name).orElse(rawSym);

            try {
                FxQuote quote = queryYahooChart(rawSym, ticker, name);
                if (quote != null) {
                    results.put(rawSym, quote);
                } else {
                    // Try legacy FxQuoteRequest
                    FxQuoteRequest request = new FxQuoteRequest(ticker);
                    FxQuote legacyQuote = request.getSingleResult();
                    if (legacyQuote != null) {
                        legacyQuote.setSymbol(rawSym);
                        legacyQuote.setName(name);
                        legacyQuote.setCurrency("BRL");
                        legacyQuote.setProvider(PROVIDER_NAME + " (fallback)");
                        results.put(rawSym, legacyQuote);
                    }
                }
            } catch (Exception e) {
                log.warn("Yahoo FX provider failed for symbol {}: {}", rawSym, e.getMessage());
            }
        }

        return results;
    }

    private FxQuote queryYahooChart(String requestedSymbol, String ticker, String name) {
        try {
            String url = YahooConstants.HISTQUOTES_QUERY2V8_BASE_URL + ticker + "?interval=1d&range=1d";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", YahooConstants.USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(DEFAULT_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode result = root.get("chart").get("result");
                if (result != null && result.isArray() && !result.isEmpty()) {
                    JsonNode meta = result.get(0).get("meta");
                    if (meta != null && meta.has("regularMarketPrice")) {
                        BigDecimal price = BigDecimal.valueOf(meta.get("regularMarketPrice").asDouble());
                        return FxQuote.builder()
                                .symbol(requestedSymbol)
                                .name(name)
                                .currency("BRL")
                                .price(price)
                                .sellPrice(price)
                                .buyPrice(price)
                                .provider(PROVIDER_NAME + " (fallback)")
                                .build();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Yahoo v8 chart query failed for {}: {}", ticker, e.getMessage());
        }
        return null;
    }
}
