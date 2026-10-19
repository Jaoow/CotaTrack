package dev.jaoow.cotatrack.api.provider.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jaoow.cotatrack.api.provider.ProviderException;
import dev.jaoow.cotatrack.api.provider.StockQuoteProvider;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.HistoricalQuote;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.Interval;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.Range;
import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;
import dev.jaoow.cotatrack.api.util.Utils;
import dev.jaoow.cotatrack.api.yahoo.SymbolMapper;
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
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Fallback market data provider using Yahoo Finance v8 Chart endpoint.
 * Does not require cookies or crumbs; provides intraday price, metadata, and historical candles.
 */
@Slf4j
public class YahooChartQuoteProvider implements StockQuoteProvider {

    public static final String PROVIDER_NAME = "YAHOO_CHART";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(3000);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final ExecutorService executorService;

    public YahooChartQuoteProvider() {
        this(
                HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(),
                Boolean.parseBoolean(System.getProperty("cotatrack.providers.yahoochart.enabled", "true"))
        );
    }

    public YahooChartQuoteProvider(HttpClient httpClient, boolean enabled) {
        this.httpClient = httpClient;
        this.enabled = enabled;
        this.objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.executorService = Executors.newFixedThreadPool(8);
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
    public Optional<Quote> fetchQuote(String symbol) throws Exception {
        String mappedSymbol = SymbolMapper.mapSymbol(symbol);
        String url = YahooConstants.HISTQUOTES_QUERY2V8_BASE_URL + URLEncoder.encode(mappedSymbol, StandardCharsets.UTF_8)
                + "?interval=1d&range=1d";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", YahooConstants.USER_AGENT)
                .header("Accept", "application/json")
                .timeout(DEFAULT_TIMEOUT)
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new ProviderException(getName(), response.statusCode(), "Yahoo chart returned error: " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        Quote quote = parseQuoteFromChart(mappedSymbol, root);
        return Optional.ofNullable(quote);
    }

    @Override
    public Map<String, Quote> fetchQuotes(List<String> symbols) throws Exception {
        if (symbols == null || symbols.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Quote> result = new HashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String symbol : symbols) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    fetchQuote(symbol).ifPresent(q -> {
                        synchronized (result) {
                            result.put(symbol, q);
                            String raw = symbol.replace(".SA", "");
                            result.put(raw, q);
                            result.put(raw + ".SA", q);
                        }
                    });
                } catch (Exception e) {
                    log.warn("YahooChart failed for symbol {}: {}", symbol, e.getMessage());
                }
            }, executorService));
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(DEFAULT_TIMEOUT.toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Timeout or error waiting for YahooChart batch quotes: {}", e.getMessage());
        }

        return result;
    }

    @Override
    public List<HistoricalQuote> fetchHistorical(String symbol, LocalDate from, LocalDate to, Interval interval, Range range) throws Exception {
        String mappedSymbol = SymbolMapper.mapSymbol(symbol);

        Map<String, String> params = new LinkedHashMap<>();
        if (from != null) {
            params.put("period1", String.valueOf(from.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()));
        }
        if (to != null) {
            params.put("period2", String.valueOf(to.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()));
        }
        if (interval != null) {
            params.put("interval", interval.getTag());
        } else {
            params.put("interval", "1d");
        }
        if (range != null) {
            params.put("range", range.getTag());
        }

        String url = YahooConstants.HISTQUOTES_QUERY2V8_BASE_URL + URLEncoder.encode(mappedSymbol, StandardCharsets.UTF_8)
                + "?" + Utils.buildUrlParameters(params);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", YahooConstants.USER_AGENT)
                .header("Accept", "application/json")
                .timeout(DEFAULT_TIMEOUT)
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new ProviderException(getName(), response.statusCode(), "Yahoo chart historical failed: " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode resultNode = root.path("chart").path("result").get(0);
        if (resultNode == null) {
            return Collections.emptyList();
        }

        JsonNode timestamps = resultNode.path("timestamp");
        JsonNode indicators = resultNode.path("indicators");
        if (timestamps.isMissingNode() || indicators.isMissingNode()) {
            return Collections.emptyList();
        }

        return IntStream.range(0, timestamps.size())
                .mapToObj(i -> createHistoricalQuote(timestamps, indicators, i))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public boolean supportsHistorical() {
        return true;
    }

    private Quote parseQuoteFromChart(String symbol, JsonNode root) {
        JsonNode resultNode = root.path("chart").path("result").get(0);
        if (resultNode == null || resultNode.isMissingNode()) {
            return null;
        }

        JsonNode meta = resultNode.path("meta");
        if (meta.isMissingNode()) {
            return null;
        }

        Quote q = new Quote();
        q.setSymbol(meta.path("symbol").asText(symbol));
        q.setCurrency(meta.path("currency").asText("BRL"));
        q.setExchange(meta.path("exchangeName").asText("SAO"));
        q.setFullExchangeName(meta.path("fullExchangeName").asText("São Paulo"));
        q.setShortName(meta.path("shortName").asText(symbol));
        q.setLongName(meta.path("longName").asText(q.getShortName()));

        double price = meta.path("regularMarketPrice").asDouble(0.0);
        double changePct = meta.path("regularMarketChangePercent").asDouble(0.0);
        double change = meta.path("fulldayChange").asDouble(0.0);
        double prevClose = meta.path("chartPreviousClose").asDouble(price - change);
        double high = meta.path("regularMarketDayHigh").asDouble(price);
        double low = meta.path("regularMarketDayLow").asDouble(price);
        long volume = meta.path("regularMarketVolume").asLong(0L);

        q.setRegularMarketPrice(price);
        q.setRegularMarketChange(change);
        q.setRegularMarketChangePercent(changePct);
        q.setRegularMarketPreviousClose(prevClose);
        q.setRegularMarketDayHigh(high);
        q.setRegularMarketDayLow(low);
        q.setRegularMarketVolume(volume);
        q.setRegularMarketDayRange(low + " - " + high);

        long timeSeconds = meta.path("regularMarketTime").asLong(System.currentTimeMillis() / 1000);
        q.setRegularMarketTime(new Date(timeSeconds * 1000));

        double high52 = meta.path("fiftyTwoWeekHigh").asDouble(high);
        double low52 = meta.path("fiftyTwoWeekLow").asDouble(low);
        q.setFiftyTwoWeekHigh(high52);
        q.setFiftyTwoWeekLow(low52);
        q.setFiftyTwoWeekRange(low52 + " - " + high52);
        q.setFiftyTwoWeekHighChange(high52 > 0 ? price - high52 : 0.0);
        q.setFiftyTwoWeekHighChangePercent(high52 > 0 ? (price - high52) / high52 : 0.0);
        q.setFiftyTwoWeekLowChange(low52 > 0 ? price - low52 : 0.0);

        // Try extracting open from indicator if available
        JsonNode quoteIndicators = resultNode.path("indicators").path("quote").get(0);
        if (quoteIndicators != null && quoteIndicators.has("open") && quoteIndicators.path("open").size() > 0) {
            q.setRegularMarketOpen(quoteIndicators.path("open").get(0).asDouble(prevClose));
        } else {
            q.setRegularMarketOpen(prevClose);
        }

        return q;
    }

    private HistoricalQuote createHistoricalQuote(JsonNode timestamps, JsonNode indicators, int index) {
        try {
            long timestamp = timestamps.get(index).asLong();
            JsonNode quoteNode = indicators.path("quote").get(0);
            JsonNode adjNode = indicators.path("adjclose").get(0);

            BigDecimal adjClose = (adjNode != null && adjNode.has("adjclose") && adjNode.path("adjclose").size() > index)
                    ? adjNode.path("adjclose").get(index).decimalValue() : BigDecimal.ZERO;

            long volume = (quoteNode != null && quoteNode.has("volume") && quoteNode.path("volume").size() > index)
                    ? quoteNode.path("volume").get(index).asLong(0L) : 0L;

            BigDecimal open = (quoteNode != null && quoteNode.has("open") && quoteNode.path("open").size() > index)
                    ? quoteNode.path("open").get(index).decimalValue() : BigDecimal.ZERO;

            BigDecimal high = (quoteNode != null && quoteNode.has("high") && quoteNode.path("high").size() > index)
                    ? quoteNode.path("high").get(index).decimalValue() : BigDecimal.ZERO;

            BigDecimal low = (quoteNode != null && quoteNode.has("low") && quoteNode.path("low").size() > index)
                    ? quoteNode.path("low").get(index).decimalValue() : BigDecimal.ZERO;

            BigDecimal close = (quoteNode != null && quoteNode.has("close") && quoteNode.path("close").size() > index)
                    ? quoteNode.path("close").get(index).decimalValue() : BigDecimal.ZERO;

            return new HistoricalQuote(timestamp, open, low, high, close, adjClose, volume);
        } catch (Exception e) {
            log.debug("Error parsing historical candle at index {}: {}", index, e.getMessage());
            return null;
        }
    }
}
