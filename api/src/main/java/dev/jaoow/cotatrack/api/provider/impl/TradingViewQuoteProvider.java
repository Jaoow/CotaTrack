package dev.jaoow.cotatrack.api.provider.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.jaoow.cotatrack.api.provider.enricher.QuoteEnricher;
import dev.jaoow.cotatrack.api.provider.ProviderException;
import dev.jaoow.cotatrack.api.provider.StockQuoteProvider;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.HistoricalQuote;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.Interval;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.Range;
import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;
import dev.jaoow.cotatrack.api.yahoo.YahooConstants;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

/**
 * Primary market data provider using TradingView Scanner API.
 * Supports batch queries up to several dozen symbols in a single POST request.
 */
@Slf4j
public class TradingViewQuoteProvider implements StockQuoteProvider {

    public static final String PROVIDER_NAME = "TRADING_VIEW";
    private static final String SCANNER_URL = "https://scanner.tradingview.com/brazil/scan";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(3000);

    private static final List<String> COLUMNS = List.of(
            "name", "description", "close", "change", "change_abs", "open", "high", "low", "volume",
            "market_cap_basic", "currency", "exchange", "type", "subtype",
            "average_volume_30d_calc", "average_volume_10d_calc", "average_volume_90d_calc",
            "price_52_week_high", "price_52_week_low", "SMA200", "logoid",
            "dividends_yield", "dividends_yield_current", "dps_common_stock_prim_issue_fy",
            "dividend_amount_recent", "time"
    );

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public TradingViewQuoteProvider() {
        this(
                HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(),
                Boolean.parseBoolean(System.getProperty("cotatrack.providers.tradingview.enabled", "true"))
        );
    }

    public TradingViewQuoteProvider(HttpClient httpClient, boolean enabled) {
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
    public Optional<Quote> fetchQuote(String symbol) throws Exception {
        Map<String, Quote> map = fetchQuotes(Collections.singletonList(symbol));
        if (map == null || map.isEmpty()) {
            return Optional.empty();
        }
        Quote q = map.get(normalizeSymbol(symbol));
        if (q == null && !map.isEmpty()) {
            q = map.values().iterator().next();
        }
        return Optional.ofNullable(q);
    }

    @Override
    public Map<String, Quote> fetchQuotes(List<String> symbols) throws Exception {
        if (symbols == null || symbols.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> tvToOriginalMap = new HashMap<>();
        List<String> tvTickers = new ArrayList<>();

        for (String rawSymbol : symbols) {
            String clean = normalizeSymbol(rawSymbol);
            String tvTicker = "BMFBOVESPA:" + clean;
            tvTickers.add(tvTicker);
            tvToOriginalMap.put(tvTicker, rawSymbol);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("symbols", Map.of("tickers", tvTickers));
        payload.put("columns", COLUMNS);

        String jsonPayload = objectMapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SCANNER_URL))
                .header("User-Agent", YahooConstants.USER_AGENT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(DEFAULT_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();

        if (statusCode != 200) {
            throw new ProviderException(getName(), statusCode, "Failed response from TradingView scanner: " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        ArrayNode dataArray = (ArrayNode) root.get("data");
        if (dataArray == null || dataArray.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Quote> result = new HashMap<>();

        for (JsonNode rowNode : dataArray) {
            String tickerKey = rowNode.get("s").asText();
            ArrayNode dArray = (ArrayNode) rowNode.get("d");
            if (dArray == null) continue;

            Map<String, JsonNode> colMap = new HashMap<>();
            for (int i = 0; i < COLUMNS.size() && i < dArray.size(); i++) {
                colMap.put(COLUMNS.get(i), dArray.get(i));
            }

            String originalSymbol = tvToOriginalMap.getOrDefault(tickerKey, tickerKey.replace("BMFBOVESPA:", ""));
            Quote quote = buildQuote(originalSymbol, colMap);

            result.put(originalSymbol, quote);
            // Also store with .SA and without suffix for friendly lookup
            String clean = normalizeSymbol(originalSymbol);
            result.put(clean, quote);
            result.put(clean + ".SA", quote);
        }

        return result;
    }

    @Override
    public List<HistoricalQuote> fetchHistorical(String symbol, LocalDate from, LocalDate to, Interval interval, Range range) {
        // TradingView Scanner API does not provide historical candle series
        return Collections.emptyList();
    }

    @Override
    public boolean supportsHistorical() {
        return false;
    }

    private Quote buildQuote(String symbol, Map<String, JsonNode> cols) {
        Quote q = new Quote();
        q.setSymbol(symbol.toUpperCase().endsWith(".SA") ? symbol.toUpperCase() : symbol.toUpperCase() + ".SA");
        q.setCurrency(getText(cols, "currency", "BRL"));
        q.setExchange("SAO");
        q.setFullExchangeName("São Paulo");

        String name = getText(cols, "name", symbol);
        String desc = getText(cols, "description", name);
        q.setShortName(name);
        q.setLongName(desc);

        String logoId = getText(cols, "logoid", null);
        if (logoId != null && !logoId.isBlank()) {
            q.setLogoUrl("https://s3-symbol-logo.tradingview.com/" + logoId + ".svg");
        }

        Double close = getDouble(cols, "close");
        Double changeAbs = getDouble(cols, "change_abs");
        Double changePct = getDouble(cols, "change");
        Double open = getDouble(cols, "open");
        Double high = getDouble(cols, "high");
        Double low = getDouble(cols, "low");
        Long volume = getLong(cols, "volume");

        q.setRegularMarketPrice(close);
        q.setRegularMarketChange(changeAbs);
        q.setRegularMarketChangePercent(changePct);
        q.setRegularMarketOpen(open);
        q.setRegularMarketDayHigh(high != null ? high : close);
        q.setRegularMarketDayLow(low != null ? low : close);
        q.setRegularMarketVolume(volume);

        Long timeSeconds = getLong(cols, "time");
        if (timeSeconds != null) {
            q.setRegularMarketTime(new Date(timeSeconds * 1000));
        } else {
            q.setRegularMarketTime(new Date());
        }

        q.setAverageDailyVolume10Day(getLong(cols, "average_volume_10d_calc"));
        q.setAverageDailyVolume3Month(getLong(cols, "average_volume_90d_calc"));

        q.setTwoHundredDayAverage(getDouble(cols, "SMA200"));
        q.setFiftyTwoWeekHigh(getDouble(cols, "price_52_week_high"));
        q.setFiftyTwoWeekLow(getDouble(cols, "price_52_week_low"));
        q.setMarketCap(getLong(cols, "market_cap_basic"));

        Double divYield = getDouble(cols, "dividends_yield_current");
        if (divYield == null || divYield == 0.0) {
            divYield = getDouble(cols, "dividends_yield");
        }
        Double divRate = getDouble(cols, "dps_common_stock_prim_issue_fy");

        q.setDividendYield(divYield);
        q.setTrailingAnnualDividendYield(divYield);
        q.setDividendRate(divRate);
        q.setTrailingAnnualDividendRate(divRate);

        return QuoteEnricher.enrich(q);
    }

    private String normalizeSymbol(String symbol) {
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

    private String getText(Map<String, JsonNode> cols, String key, String defaultVal) {
        JsonNode node = cols.get(key);
        return (node != null && !node.isNull()) ? node.asText() : defaultVal;
    }

    private Double getDouble(Map<String, JsonNode> cols, String key) {
        JsonNode node = cols.get(key);
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) return node.asDouble();
        try {
            return Double.parseDouble(node.asText());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long getLong(Map<String, JsonNode> cols, String key) {
        JsonNode node = cols.get(key);
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) return node.asLong();
        try {
            return Long.parseLong(node.asText());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
