package dev.jaoow.cotatrack.api.requests.historicalquotes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.HistoricalQuote;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.Interval;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.Range;
import dev.jaoow.cotatrack.api.util.Utils;
import dev.jaoow.cotatrack.api.yahoo.YahooCredentials;
import dev.jaoow.cotatrack.api.yahoo.YahooConstants;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@lombok.Builder(builderClassName = "Builder")
public class HistoricalQuotesRequest {

    public static final LocalDate DEFAULT_FROM = LocalDate.now().minusYears(1);
    public static final LocalDate DEFAULT_TO = LocalDate.now();

    public static final Interval DEFAULT_INTERVAL = Interval.ONE_DAY;
    public static final Range DEFAULT_RANGE = Range.FIVE_DAYS;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder().build();

    private final String symbol;

    @lombok.Builder.Default
    private final LocalDate from = DEFAULT_FROM;

    @lombok.Builder.Default
    private final LocalDate to = DEFAULT_TO;

    @lombok.Builder.Default
    private final Interval interval = DEFAULT_INTERVAL;

    @lombok.Builder.Default
    private final Range range = DEFAULT_RANGE;


    /**
     * Fetches the historical quotes from the Yahoo Finance API.
     *
     * @return the historical quotes
     */
    public List<HistoricalQuote> getResult() {
        String cleanSymbol = this.symbol != null ? this.symbol.toUpperCase().replace(".SA", "") : "";
        String cacheKey = cleanSymbol + ":" + this.from + ":" + this.to + ":" + this.interval + ":" + this.range;

        List<HistoricalQuote> cached = dev.jaoow.cotatrack.api.cache.CotaTrackCacheManager.getInstance().getHistorical(cacheKey);
        if (cached != null) {
            return cached;
        }

        JsonNode data = null;
        try {
            data = fetchData();
        } catch (InterruptedException | IOException e) {
            log.error("Failed to fetch data", e);
        }

        if (data == null) {
            return Collections.emptyList();
        }

        JsonNode resultNode = data.get("chart").get("result").get(0);
        JsonNode timestamps = resultNode.get("timestamp");
        JsonNode indicators = resultNode.get("indicators");

        List<HistoricalQuote> quotes = IntStream.range(0, timestamps.size())
                .mapToObj(i -> createHistoricalQuote(timestamps, indicators, i))
                .collect(Collectors.toList());

        if (!quotes.isEmpty()) {
            dev.jaoow.cotatrack.api.cache.CotaTrackCacheManager.getInstance().putHistorical(cacheKey, quotes);
        }

        return quotes;
    }

    private HistoricalQuote createHistoricalQuote(JsonNode timestamps, JsonNode indicators, int index) {
        long timestamp = timestamps.get(index).asLong();
        JsonNode quoteNode = indicators.get("quote").get(0);

        BigDecimal adjClose = indicators.get("adjclose").get(0).get("adjclose").get(index).decimalValue();

        long volume = quoteNode.get("volume").get(index).asLong();
        BigDecimal open = quoteNode.get("open").get(index).decimalValue();
        BigDecimal high = quoteNode.get("high").get(index).decimalValue();
        BigDecimal low = quoteNode.get("low").get(index).decimalValue();
        BigDecimal close = quoteNode.get("close").get(index).decimalValue();

        return new HistoricalQuote(timestamp, open, low, high, close, adjClose, volume);
    }

    private JsonNode fetchData() throws IOException, InterruptedException {
        return fetchDataWithRetry(true);
    }

    private JsonNode fetchDataWithRetry(boolean allowRetry) throws IOException, InterruptedException {
        if (this.from.isAfter(this.to)) {
            log.error("Unable to retrieve historical quotes. From-date should not be after to-date. From: {}, to: {}", this.from, this.to);
            return null;
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("period1", String.valueOf(this.from.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()));
        params.put("period2", String.valueOf(this.to.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()));
        params.put("interval", this.interval.getTag());
        params.put("range", this.range.getTag());

        String crumb = YahooCredentials.getCrumb();
        if (crumb != null && !crumb.isEmpty()) {
            params.put("crumb", crumb);
        }

        String url = YahooConstants.HISTQUOTES_QUERY2V8_BASE_URL + URLEncoder.encode(this.symbol, StandardCharsets.UTF_8) + "?" + Utils.buildUrlParameters(params);

        log.info("Sending request: {}", url);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", YahooConstants.USER_AGENT)
                .header("Accept", "application/json");

        String cookie = YahooCredentials.getCookie();
        if (cookie != null && !cookie.isEmpty()) {
            reqBuilder.header("Cookie", cookie);
        }

        HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            log.info("Parsing JSON response: {}", response.body());
            return objectMapper.readTree(response.body());
        } else if ((response.statusCode() == 401 || response.statusCode() == 403) && allowRetry) {
            log.warn("Yahoo historical returned HTTP {}. Refreshing credentials and retrying once...", response.statusCode());
            YahooCredentials.refresh();
            return fetchDataWithRetry(false);
        } else {
            throw new IOException("Failed to fetch data: HTTP status code " + response.statusCode());
        }
    }
}
