package dev.jaoow.cotatrack.api.tradingview.field;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jaoow.cotatrack.api.util.Utils;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class FieldFetcher {

    private static final String BASE_URL = "https://scanner.tradingview.com/symbol?";
    private static final HttpClient client = HttpClient.newHttpClient();

    /**
     * Fetches the field from the TradingView API.
     *
     * @param exchangeKey the exchange key
     * @param symbol      the symbol
     * @param field       the field to fetch
     * @return the field fetched
     */
    public static String fetchField(String exchangeKey, String symbol, FieldKey field) {
        String urlStr = buildUrl(exchangeKey, symbol, List.of(field));
        return fetchFieldFromAPI(urlStr, field);
    }

    /**
     * Fetches the fields from the TradingView API.
     *
     * @param exchangeKey the exchange key
     * @param symbol      the symbol
     * @param fields      the fields to fetch
     * @return the fields fetched
     */
    public static Map<String, String> fetchFields(String exchangeKey, String symbol, List<FieldKey> fields) {
        String urlStr = buildUrl(exchangeKey, symbol, fields);
        return fetchFieldsFromAPI(urlStr, fields);
    }

    private static String buildUrl(String exchangeKey, String symbol, List<FieldKey> fields) {

        if (symbol.contains(".")) {
            symbol = symbol.substring(0, symbol.indexOf('.'));
        }

        Map<String, String> params = new HashMap<>();
        params.put("symbol", exchangeKey + ":" + symbol);
        params.put("fields", fields.stream().map(FieldKey::getKey).reduce((a, b) -> a + "," + b).orElse(""));
        params.put("no_404", "true");

        return BASE_URL + Utils.buildUrlParameters(params);
    }

    private static String fetchFieldFromAPI(String urlStr, FieldKey field) {
        String key = field.getKey();
        try {
            JsonNode jsonNode = fetchFromAPI(urlStr);
            JsonNode fieldNode = jsonNode.get(key);

            if (fieldNode != null && !fieldNode.isNull()) {
                return fieldNode.asText();
            } else {
                log.warn("{} not found in the response for URL: {}", key, urlStr);
                return null;
            }
        } catch (Exception e) {
            log.warn("Error fetching {} from URL: {}", key, urlStr, e);
            return null;
        }
    }

    private static Map<String, String> fetchFieldsFromAPI(String urlStr, List<FieldKey> fields) {
        Map<String, String> result = new HashMap<>();
        try {
            JsonNode jsonNode = fetchFromAPI(urlStr);
            for (FieldKey field : fields) {
                String key = field.getKey();
                JsonNode fieldNode = jsonNode.get(key);
                if (fieldNode != null && !fieldNode.isNull()) {
                    result.put(key, fieldNode.asText());
                } else {
                    log.warn("{} not found in the response for URL: {}", field, urlStr);
                    result.put(key, null);
                }
            }
        } catch (Exception e) {
            log.warn("Error fetching fields from URL: {}", urlStr, e);
        }
        return result;
    }

    /**
     * Fetches the JSON data from the TradingView API.
     *
     * @param urlStr the URL to fetch the data from
     * @return the JSON data
     * @throws Exception if an error occurs while fetching the data
     */
    private static JsonNode fetchFromAPI(String urlStr) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(urlStr))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readTree(response.body());
    }
}
