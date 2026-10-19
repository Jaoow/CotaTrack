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
import dev.jaoow.cotatrack.api.yahoo.YahooConstants;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Fallback market data provider using brapi.dev REST API.
 * Follows free tier constraints:
 * - 1 ticker per request
 * - Max 1 concurrent request (guaranteed via Semaphore)
 * - Token supplied via env BRAPI_TOKEN or property cotatrack.providers.brapi.token
 */
@Slf4j
public class BrapiQuoteProvider implements StockQuoteProvider {

    public static final String PROVIDER_NAME = "BRAPI";
    private static final String BASE_URL = "https://brapi.dev/api/quote/";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(3000);

    // Concurrency limiter: brapi free tier enforces max 1 concurrent request
    private final Semaphore concurrencySemaphore = new Semaphore(1);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiToken;
    private final boolean enabled;

    public BrapiQuoteProvider() {
        this(
                HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(),
                resolveToken(),
                Boolean.parseBoolean(System.getProperty("cotatrack.providers.brapi.enabled", "true"))
        );
    }

    public BrapiQuoteProvider(HttpClient httpClient, String apiToken, boolean enabled) {
        this.httpClient = httpClient;
        this.apiToken = apiToken != null ? apiToken.trim() : "";
        this.enabled = enabled;
        this.objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private static String resolveToken() {
        String token = System.getProperty("cotatrack.providers.brapi.token");
        if (token != null && !token.isBlank()) {
            return token;
        }
        String envToken = System.getenv("BRAPI_TOKEN");
        return envToken != null ? envToken : "";
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
        return fetchSingleQuote(symbol, null, null);
    }

    @Override
    public Map<String, Quote> fetchQuotes(List<String> symbols) throws Exception {
        if (symbols == null || symbols.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Quote> result = new HashMap<>();

        // Brapi free tier requires 1 ticker per request and 1 concurrent request
        for (String symbol : symbols) {
            try {
                fetchQuote(symbol).ifPresent(q -> {
                    result.put(symbol, q);
                    String clean = cleanSymbol(symbol);
                    result.put(clean, q);
                    result.put(clean + ".SA", q);
                });
            } catch (Exception e) {
                log.warn("Brapi failed for symbol {}: {}", symbol, sanitizeMessage(e.getMessage()));
            }
        }

        return result;
    }

    @Override
    public List<HistoricalQuote> fetchHistorical(String symbol, LocalDate from, LocalDate to, Interval interval, Range range) throws Exception {
        String brapiRange = (range != null) ? mapRange(range) : "1mo";
        String brapiInterval = (interval != null) ? mapInterval(interval) : "1d";

        Optional<Quote> quoteOpt = fetchSingleQuote(symbol, brapiRange, brapiInterval);
        return quoteOpt.map(Quote::getHistoricalQuotes).orElse(Collections.emptyList());
    }

    @Override
    public boolean supportsHistorical() {
        return true;
    }

    private Optional<Quote> fetchSingleQuote(String rawSymbol, String range, String interval) throws Exception {
        String clean = cleanSymbol(rawSymbol);
        StringBuilder urlBuilder = new StringBuilder(BASE_URL).append(clean);

        boolean hasParam = false;
        if (apiToken != null && !apiToken.isBlank()) {
            urlBuilder.append("?token=").append(apiToken);
            hasParam = true;
        }
        if (range != null) {
            urlBuilder.append(hasParam ? "&" : "?").append("range=").append(range);
            hasParam = true;
        }
        if (interval != null) {
            urlBuilder.append(hasParam ? "&" : "?").append("interval=").append(interval);
        }

        boolean acquired = concurrencySemaphore.tryAcquire(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!acquired) {
            throw new ProviderException(getName(), "Concurrency limit exceeded (waiting on semaphore timeout)");
        }

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(urlBuilder.toString()))
                    .header("User-Agent", YahooConstants.USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(DEFAULT_TIMEOUT);

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();

            if (statusCode == 404) {
                return Optional.empty();
            }
            if (statusCode != 200) {
                throw new ProviderException(getName(), statusCode, sanitizeMessage(response.body()));
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode resultsNode = root.path("results");
            if (!resultsNode.isArray() || resultsNode.isEmpty()) {
                return Optional.empty();
            }

            JsonNode item = resultsNode.get(0);
            Quote quote = parseQuoteFromBrapi(clean, item);
            return Optional.ofNullable(quote);

        } finally {
            concurrencySemaphore.release();
        }
    }

    private Quote parseQuoteFromBrapi(String cleanSymbol, JsonNode item) {
        Quote q = new Quote();
        String symbol = item.path("symbol").asText(cleanSymbol);
        q.setSymbol(symbol.endsWith(".SA") ? symbol : symbol + ".SA");
        q.setCurrency(item.path("currency").asText("BRL"));
        q.setExchange("SAO");
        q.setFullExchangeName("São Paulo");

        q.setShortName(item.path("shortName").asText(symbol));
        q.setLongName(item.path("longName").asText(q.getShortName()));

        String logoUrl = item.path("logourl").asText(null);
        if (logoUrl != null && !logoUrl.isBlank()) {
            q.setLogoUrl(logoUrl);
        }

        double price = item.path("regularMarketPrice").asDouble(0.0);
        double change = item.path("regularMarketChange").asDouble(0.0);
        double changePct = item.path("regularMarketChangePercent").asDouble(0.0);
        double open = item.path("regularMarketOpen").asDouble(price);
        double high = item.path("regularMarketDayHigh").asDouble(price);
        double low = item.path("regularMarketDayLow").asDouble(price);
        double prevClose = item.path("regularMarketPreviousClose").asDouble(price - change);
        long volume = item.path("regularMarketVolume").asLong(0L);

        q.setRegularMarketPrice(price);
        q.setRegularMarketChange(change);
        q.setRegularMarketChangePercent(changePct);
        q.setRegularMarketOpen(open);
        q.setRegularMarketDayHigh(high);
        q.setRegularMarketDayLow(low);
        q.setRegularMarketVolume(volume);
        q.setRegularMarketPreviousClose(prevClose);
        q.setRegularMarketDayRange(item.path("regularMarketDayRange").asText(low + " - " + high));

        String timeStr = item.path("regularMarketTime").asText(null);
        if (timeStr != null) {
            try {
                q.setRegularMarketTime(Date.from(Instant.parse(timeStr)));
            } catch (DateTimeParseException ignored) {
                q.setRegularMarketTime(new Date());
            }
        } else {
            q.setRegularMarketTime(new Date());
        }

        double high52 = item.path("fiftyTwoWeekHigh").asDouble(high);
        double low52 = item.path("fiftyTwoWeekLow").asDouble(low);
        q.setFiftyTwoWeekHigh(high52);
        q.setFiftyTwoWeekLow(low52);
        q.setFiftyTwoWeekRange(item.path("fiftyTwoWeekRange").asText(low52 + " - " + high52));
        q.setFiftyTwoWeekHighChange(high52 > 0 ? price - high52 : 0.0);
        q.setFiftyTwoWeekHighChangePercent(high52 > 0 ? (price - high52) / high52 : 0.0);
        q.setFiftyTwoWeekLowChange(low52 > 0 ? price - low52 : 0.0);

        q.setMarketCap(item.path("marketCap").asLong(0L));

        // Parse historical data if present in response
        JsonNode histNode = item.path("historicalDataPrice");
        if (histNode.isArray() && !histNode.isEmpty()) {
            List<HistoricalQuote> histQuotes = new ArrayList<>();
            for (JsonNode h : histNode) {
                long date = h.path("date").asLong(0L);
                BigDecimal o = BigDecimal.valueOf(h.path("open").asDouble(0.0));
                BigDecimal l = BigDecimal.valueOf(h.path("low").asDouble(0.0));
                BigDecimal hi = BigDecimal.valueOf(h.path("high").asDouble(0.0));
                BigDecimal c = BigDecimal.valueOf(h.path("close").asDouble(0.0));
                BigDecimal adj = BigDecimal.valueOf(h.path("adjustedClose").asDouble(c.doubleValue()));
                long v = h.path("volume").asLong(0L);
                histQuotes.add(new HistoricalQuote(date, o, l, hi, c, adj, v));
            }
            q.setHistoricalQuotes(histQuotes);
        }

        return q;
    }

    private String cleanSymbol(String symbol) {
        if (symbol == null) return "";
        String s = symbol.trim().toUpperCase();
        if (s.startsWith("BMFBOVESPA:")) {
            s = s.substring("BMFBOVESPA:".length());
        }
        if (s.endsWith(".SA")) {
            s = s.substring(0, s.length() - 3);
        }
        return s;
    }

    private String mapRange(Range range) {
        if (range == null) return "1mo";
        return range.getTag();
    }

    private String mapInterval(Interval interval) {
        if (interval == null) return "1d";
        return interval.getTag();
    }

    private String sanitizeMessage(String message) {
        if (message == null) return "Unknown error";
        if (apiToken != null && !apiToken.isBlank()) {
            return message.replace(apiToken, "***");
        }
        return message;
    }
}
