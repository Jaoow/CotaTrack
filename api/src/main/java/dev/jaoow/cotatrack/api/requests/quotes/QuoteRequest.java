package dev.jaoow.cotatrack.api.requests.quotes;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.jaoow.cotatrack.api.requests.quotes.transform.DataTransformer;
import dev.jaoow.cotatrack.api.util.Utils;
import dev.jaoow.cotatrack.api.yahoo.YahooConstants;
import dev.jaoow.cotatrack.api.yahoo.YahooCredentials;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Getter
@RequiredArgsConstructor
public abstract class QuoteRequest<T> {

    protected static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final HttpClient httpClient = HttpClient.newBuilder().build();

    private static final Cache<String, JsonNode> cache = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    protected final String symbols;
    protected final List<DataTransformer> transformers = new ArrayList<>();

    protected abstract T parseJson(JsonNode node);

    /**
     * Adds a data transformer to the list.
     *
     * @param transformer the data transformer to add
     */
    public void addTransformer(DataTransformer transformer) {
        transformers.add(transformer);
    }

    /**
     * Transforms the JSON data using the added transformers.
     *
     * @param node the JSON data to transform
     * @return the transformed JSON data
     */
    protected JsonNode transformData(JsonNode node) {
        JsonNode copyNode = node.deepCopy();
        for (DataTransformer transformer : transformers) {
            copyNode = transformer.transform(copyNode);
        }
        return copyNode;
    }

    /**
     * Loads data from Yahoo Finance for specific symbols.
     *
     * @param symbols the symbols for which the data should be loaded
     * @return the JsonNode representing the loaded data
     * @throws IOException when there's a connection problem or the request is incorrect
     * @throws InterruptedException if the request is interrupted
     */
    private JsonNode loadFromYahoo(String symbols) throws IOException, InterruptedException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbols", symbols);
        params.put("crumb", YahooCredentials.getCrumb());

        String url = YahooConstants.QUOTES_QUERY2V7_BASE_URL + "?" + Utils.buildUrlParameters(params);

        log.info("Sending request: {}", url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Cookie", YahooCredentials.getCookie())
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            log.info("Parsing JSON response: {}", response.body());
            return objectMapper.readTree(response.body());
        } else {
            throw new IOException("Failed to fetch data: HTTP status code " + response.statusCode());
        }
    }

    /**
     * Fetches the JSON data from Yahoo Finance and the cache.
     *
     * @return List of parsed objects resulting from the Yahoo Finance request
     * @throws IOException when there's a connection problem or the request is incorrect
     */
    public List<T> getResult() throws IOException {
        List<T> result = new ArrayList<>();

        // Split the symbols string into a list
        String[] symbolList = symbols.split(",");

        // Separate cached and non-cached symbols
        Set<String> cachedSymbols = new HashSet<>();
        Set<String> nonCachedSymbols = new HashSet<>();

        for (String symbol : symbolList) {
            JsonNode cachedNode = cache.getIfPresent(symbol);
            if (cachedNode != null) {
                cachedSymbols.add(symbol);
            } else {
                nonCachedSymbols.add(symbol);
            }
        }

        // Fetch data for non-cached symbols
        if (!nonCachedSymbols.isEmpty()) {
            String nonCachedSymbolsString = String.join(",", nonCachedSymbols);
            JsonNode nonCachedData;
            try {
                nonCachedData = loadFromYahoo(nonCachedSymbolsString);
            } catch (InterruptedException e) {
                log.error("Failed to fetch data", e);
                return Collections.emptyList();
            }

            // Process the non-cached data and update the cache
            for (String symbol : nonCachedSymbols) {
                JsonNode symbolData = findSymbolData(nonCachedData, symbol);

                if (symbolData != null) {
                    cache.put(symbol, symbolData);
                    JsonNode transformedData = transformData(symbolData);
                    result.add(this.parseJson(transformedData));
                } else {
                    // Add a null node to the cache to avoid fetching the same symbol again
                    cache.put(symbol, NullNode.getInstance());
                }
            }
        }

        // Add cached data to the result
        for (String symbol : cachedSymbols) {
            JsonNode cachedNode = cache.getIfPresent(symbol);
            if (cachedNode == null || cachedNode.isNull()) continue;

            JsonNode transformedData = transformData(cachedNode);
            T parsedData = this.parseJson(transformedData);
            result.add(parsedData);
        }

        return result;
    }

    /**
     * Gets a single result from the JSON data.
     *
     * @return T representing the first result or null if there are no results
     * @throws IOException when there's a connection problem or the request is incorrect
     */
    public T getSingleResult() throws IOException {
        List<T> results = this.getResult();
        if (!results.isEmpty()) {
            return results.get(0);
        }
        return null;
    }

    /**
     * Finds and returns the data for a specific symbol within the JsonNode.
     *
     * @param dataNode the JsonNode containing data for all symbols
     * @param symbol   the symbol for which data should be found
     * @return the JsonNode containing the data for the symbol
     */
    private JsonNode findSymbolData(JsonNode dataNode, String symbol) {
        if (dataNode.has("quoteResponse") && dataNode.get("quoteResponse").has("result")) {
            JsonNode resultNode = dataNode.get("quoteResponse").get("result");
            for (int i = 0; i < resultNode.size(); i++) {
                JsonNode item = resultNode.get(i);
                if (item.has("symbol") && item.get("symbol").asText().equalsIgnoreCase(symbol)) {
                    return item;
                }
            }
        }
        return null;
    }
}
