package dev.jaoow.cotatrack.api.requests.dividends;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import dev.jaoow.cotatrack.api.requests.dividends.model.DividendsData;
import dev.jaoow.cotatrack.api.requests.dividends.fetch.DividendsFetcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class DividendsRequest {

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final LoadingCache<String, DividendsData> cache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(100)
            .build(DividendsRequest::fetchDividendsData);

    private final String symbol;

    /**
     * Fetches the dividends data from the cache or fetches from the source if not present.
     *
     * @return the dividends data
     */
    public DividendsData getResult() {
        try {
            return cache.get(symbol);
        } catch (Exception e) {
            log.error("Failed to fetch company data", e);
            return null;
        }
    }

    private static DividendsData fetchDividendsData(String symbol) {
        try {
            JsonNode companyDataNode = DividendsFetcher.fetchDividendsData(symbol);

            // Check if companyDataNode is a text node and parse it accordingly
            if (companyDataNode.isTextual()) {
                String jsonString = companyDataNode.asText();
                companyDataNode = objectMapper.readTree(jsonString).get(0); // The response is an array with a single object
            }

            return objectMapper.treeToValue(companyDataNode, DividendsData.class);
        } catch (InterruptedException | IOException e) {
            log.error("Failed to fetch company data", e);
            return null;
        }
    }
}
