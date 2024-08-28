package dev.jaoow.cotatrack.api.tradingview.screener;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.jaoow.cotatrack.api.tradingview.field.FieldKey;
import dev.jaoow.cotatrack.api.tradingview.field.StockField;
import dev.jaoow.cotatrack.api.tradingview.field.enums.Market;
import dev.jaoow.cotatrack.api.tradingview.field.enums.Type;
import dev.jaoow.cotatrack.api.tradingview.filter.Filter;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Getter
public class StockScreener {

    private static final ObjectMapper objectMapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final HttpClient client = HttpClient.newHttpClient();

    private String url;
    private List<Filter> filters = new ArrayList<>();
    private HashMap<String, Object> sort = new HashMap<>();
    private Map<String, Object> options = new HashMap<>();
    private Map<String, Object> misc = new HashMap<>();
    private List<FieldKey> specificFields;
    private List<Market> markets;

    private StockScreener() {
        this.url = getScannerUrl("global");
        this.specificFields = List.of(StockField.values());
        this.markets = Collections.singletonList(Market.BRAZIL);
        withOption("lang", "pt");
        withSortBy(StockField.MARKET_CAPITALIZATION, false);
    }

    // Copy constructor
    private StockScreener(StockScreener other) {
        this();
        this.url = other.url;
        this.filters = other.filters.stream().map(Filter::new).collect(Collectors.toList());
        this.sort = new HashMap<>(other.sort);
        this.options = new HashMap<>(other.options);
        this.misc = new HashMap<>(other.misc);
        this.specificFields = new ArrayList<>(other.specificFields);
        this.markets = new ArrayList<>(other.markets);
    }

    public static StockScreener create() {
        return new StockScreener();
    }

    public static Set<String> getColumnsToRequest(List<FieldKey> fields) {
        return fields.stream()
                .map(FieldKey::getKey)
                .collect(Collectors.toSet());
    }

    public static String getScannerUrl(String subtype) {
        return "https://scanner.tradingview.com/" + subtype + "/scan";
    }

    public StockScreener startIsolatedQuery() {
        return new StockScreener(this);
    }

    public StockScreener withSortBy(FieldKey sortBy, boolean ascending) {
        this.sort.clear();
        this.sort.put("sortBy", sortBy.getKey());
        this.sort.put("sortOrder", ascending ? "asc" : "desc");
        return this;
    }

    public StockScreener withSearch(String name) {
        return addFilter(StockField.NAME, Filter.Operator.MATCH, name);
    }

    public StockScreener withPriceConversion(String currency) {
        return withMisc("price_conversion", Map.of("to_currency", currency));
    }

    public StockScreener withStockFields(StockField... specificFields) {
        List<FieldKey> convertedFields = List.of(specificFields);
        withSpecificFields(convertedFields);
        return this;
    }

    private Map<String, Object> buildPayload(Set<String> requestedColumns) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("filter", filters.stream().map(Filter::toDict).collect(Collectors.toList()));
        payload.put("options", options);
        payload.put("sort", sort);
        payload.put("columns", requestedColumns);
        payload.putAll(misc);
        if (markets != null && !markets.isEmpty()) {
            payload.put("markets", markets.stream().map(Market::getValue).collect(Collectors.toList()));
        }
        return payload;
    }

    public StockScreener withTypes(Type... types) {
        for (Type type : types) {
            // If there is more than one type, the filter will be merged (add changed to IN_RANGE)
            addFilter(StockField.TYPE, Filter.Operator.EQUAL, type.getValue());
        }

        return this;
    }

    public StockScreener withMarkets(Market... markets) {
        this.markets = Arrays.asList(markets);
        return this;
    }

    public Filter getFilter(FieldKey fieldKey) {
        return filters.stream()
                .filter(filter -> filter.getField().equals(fieldKey))
                .findFirst().orElse(null);
    }

    public void removeFilter(FieldKey field) {
        filters.removeIf(filter -> filter.getField().equals(field));
    }

    public StockScreener addFilter(FieldKey filterKey, Filter.Operator operation, String value) {
        return addFilter(filterKey, operation, List.of(value));
    }

    public StockScreener addFilter(FieldKey filterKey, Filter.Operator operation, List<String> values) {
        Filter existingFilter = getFilter(filterKey);
        Filter filter = new Filter(filterKey, operation, values);

        if (existingFilter != null) {
            existingFilter.merge(filter);
        } else {
            filters.add(filter);

        }
        return this;
    }

    public StockScreener withOption(String key, Object value) {
        options.put(key, value);
        return this;
    }

    public StockScreener withMisc(String key, Object value) {
        misc.put(key, value);
        return this;
    }

    public StockScreener withSpecificFields(List<FieldKey> specificFields) {
        this.specificFields = specificFields;
        return this;
    }

    /**
     * Fetch the data from the TradingView API and convert it to the specified class
     *
     * @param clazz the class to convert the data to
     * @param <T>   the type of the class
     * @return the data fetched
     * @throws Exception if an error occurs
     */
    public <T> List<T> fetchAs(Class<T> clazz) throws Exception {
        JsonNode data = fetch();
        if (data == null) {
            return null;
        }

        return objectMapper.convertValue(data, objectMapper.getTypeFactory().constructCollectionType(List.class, clazz));
    }

    /**
     * Fetch the data from the TradingView API
     *
     * @return the data fetched
     * @throws Exception if an error occurs
     */
    public JsonNode fetch() throws Exception {
        Set<String> columns = getColumnsToRequest(specificFields);
        Map<String, Object> payload = buildPayload(columns);

        ObjectMapper mapper = new ObjectMapper();
        String payloadString = mapper.writeValueAsString(payload);

        log.info("Sending request: {} - Payload: {}", url, payloadString);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payloadString))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();

        if (statusCode != 200) {
            log.error("Request failed with status: {} - Response: {}", statusCode, response.body());
            throw new IOException("Failed to fetch data. HTTP Status: " + statusCode);
        }

        JsonNode rootNode = objectMapper.readTree(response.body());
        ArrayNode dataArray = (ArrayNode) rootNode.get("data");
        List<Map<String, Object>> formattedData = new ArrayList<>();
        for (JsonNode dataNode : dataArray) {
            Map<String, Object> formattedRow = new LinkedHashMap<>();
            formattedRow.put("symbol", dataNode.get("s").asText());
            ArrayNode dArray = (ArrayNode) dataNode.get("d");
            int index = 0;
            for (String column : columns) {
                formattedRow.put(column, dArray.get(index++));
            }
            formattedData.add(formattedRow);
        }

        return objectMapper.valueToTree(formattedData);
    }
}
