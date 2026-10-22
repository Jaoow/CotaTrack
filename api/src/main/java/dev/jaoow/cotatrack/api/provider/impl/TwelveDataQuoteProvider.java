package dev.jaoow.cotatrack.api.provider.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jaoow.cotatrack.api.cache.CotaTrackCacheManager;
import dev.jaoow.cotatrack.api.classification.AssetMarket;
import dev.jaoow.cotatrack.api.provider.ProviderException;
import dev.jaoow.cotatrack.api.provider.QuoteMetadata;
import dev.jaoow.cotatrack.api.provider.StockQuoteProvider;
import dev.jaoow.cotatrack.api.provider.enricher.QuoteEnricher;
import dev.jaoow.cotatrack.api.requests.dividends.model.CashDividend;
import dev.jaoow.cotatrack.api.requests.dividends.model.DividendsData;
import dev.jaoow.cotatrack.api.requests.dividends.model.StockDividend;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.HistoricalQuote;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.Interval;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.Range;
import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;
import dev.jaoow.cotatrack.api.requests.quotes.model.StockInstrument;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class TwelveDataQuoteProvider implements StockQuoteProvider {
    
    public static final String PROVIDER_NAME = "TWELVE_DATA";
    public static final Map<String, Integer> DEFAULT_CREDITS = Map.of(
            "stocks", 1, "quote", 1, "time_series", 1, "profile", 10,
            "statistics", 50, "logo", 1, "dividends", 20, "splits", 20);
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("America/New_York");
    private static final Map<Interval, String> INTERVALS = Map.ofEntries(
            Map.entry(Interval.ONE_MINUTE, "1min"), Map.entry(Interval.FIVE_MINUTES, "5min"),
            Map.entry(Interval.FIFTEEN_MINUTES, "15min"), Map.entry(Interval.THIRTY_MINUTES, "30min"),
            Map.entry(Interval.SIXTY_MINUTES, "1h"), Map.entry(Interval.ONE_HOUR, "1h"),
            Map.entry(Interval.ONE_DAY, "1day"), Map.entry(Interval.ONE_WEEK, "1week"),
            Map.entry(Interval.ONE_MONTH, "1month"));

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final boolean enabled;
    private final URI baseUri;
    private final Duration timeout;
    private final boolean fundamentalsEnabled;
    private final Map<String, Integer> creditCosts;
    private final RateLimiter credits;
    private final int dailyLimit;
    private LocalDate budgetDate = LocalDate.now(ZoneOffset.UTC);
    private int dailyUsed;
    private final CotaTrackCacheManager cacheManager;

    public TwelveDataQuoteProvider(HttpClient client, String apiKey, boolean enabled,
            CotaTrackCacheManager cacheManager) {
        this(client, apiKey, enabled, cacheManager, URI.create("https://api.twelvedata.com/"),
                Duration.ofSeconds(3), 8, 800, false, DEFAULT_CREDITS);
    }

    public TwelveDataQuoteProvider(HttpClient client, String apiKey, boolean enabled,
            CotaTrackCacheManager cacheManager, URI baseUri, Duration timeout,
            int creditsPerMinute, int creditsPerDay, boolean fundamentalsEnabled, Map<String, Integer> creditCosts) {
        if (enabled && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalArgumentException("TWELVE_DATA_API_KEY is required when STOCKS is enabled.");
        }
        if (creditsPerMinute < 1 || creditsPerDay < 1 || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Twelve Data quotas and timeout must be positive.");
        }
        if (!creditCosts.keySet().containsAll(DEFAULT_CREDITS.keySet())
                || creditCosts.values().stream().anyMatch(cost -> cost == null || cost < 1)) {
            throw new IllegalArgumentException("A positive credit cost is required for each Twelve Data endpoint.");
        }
        this.httpClient = Objects.requireNonNull(client);
        this.apiKey = apiKey;
        this.enabled = enabled;
        this.cacheManager = Objects.requireNonNull(cacheManager);
        this.baseUri = Objects.requireNonNull(baseUri);
        this.timeout = timeout;
        this.fundamentalsEnabled = fundamentalsEnabled;
        this.creditCosts = Map.copyOf(creditCosts);
        this.dailyLimit = creditsPerDay;
        this.credits = RateLimiter.of(PROVIDER_NAME, RateLimiterConfig.custom()
                .limitForPeriod(creditsPerMinute).limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ZERO).build());
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
    public AssetMarket getMarket() {
        return AssetMarket.US;
    }

    @Override
    public boolean supportsHistorical() {
        return true;
    }

    @Override
    public boolean supportsDividends() {
        return true;
    }

    @Override
    public boolean supportsCatalog() {
        return true;
    }

    private synchronized void reserveCredits(String endpoint) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!today.equals(budgetDate)) {
            budgetDate = today;
            dailyUsed = 0;
        }
        int weight = creditCosts.get(endpoint);
        if (weight > dailyLimit - dailyUsed || !credits.acquirePermission(weight)) {
            throw new ProviderException(getName(), 429, "Local Twelve Data credit budget exhausted for " + endpoint);
        }
        dailyUsed += weight;
    }

    private JsonNode request(String endpoint, Map<String, String> parameters) throws Exception {
        if (!enabled)
            throw new ProviderException(getName(), 503, "STOCKS provider is disabled.");
        reserveCredits(endpoint);
        String query = parameters.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(endpoint + "?" + query))
                .header("Authorization", "apikey " + apiKey).header("Accept", "application/json")
                .timeout(timeout).GET().build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new ProviderException(getName(), 503, "Network error fetching " + endpoint);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(getName(), 503, "Interrupted fetching " + endpoint);
        }
        if (response.statusCode() != 200) {
            throw new ProviderException(getName(), response.statusCode(), "Request rejected for " + endpoint);
        }
        JsonNode root;
        try {
            root = mapper.readTree(response.body());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ProviderException(getName(), 502, "Malformed JSON for " + endpoint);
        }
        if (root == null || !root.isObject()) {
            throw new ProviderException(getName(), 502, "Invalid payload for " + endpoint);
        }
        if ("error".equals(root.path("status").asText())) {
            int code = root.path("code").asInt(502);
            String message = root.path("message").asText("").toLowerCase(Locale.ROOT);
            if (code == 400 && message.contains("symbol")
                    && (message.contains("not found") || message.contains("invalid")
                            || message.contains("does not exist"))) {
                code = 404;
            }
            throw new ProviderException(getName(), code, "Provider rejected " + endpoint);
        }
        return root;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public List<StockInstrument> fetchCatalog() throws Exception {
        List<StockInstrument> cached = cacheManager.getStockCatalogCache().getIfPresent("US");
        if (cached != null)
            return cached;
        JsonNode root = request("stocks", Map.of("country", "United States", "show_plan", "false"));
        JsonNode rows = requireArray(root, "data");
        List<StockInstrument> instruments = new ArrayList<>();
        for (JsonNode row : rows) {
            String type = text(row, "type");
            String currency = text(row, "currency");
            if (!"USD".equals(currency) || !isStock(type))
                continue;
            String symbol = text(row, "symbol");
            if (symbol == null)
                throw invalidPayload("catalog symbol");
            // OTC and non-US venues are outside the initial STOCKS universe.
            String exchange = text(row, "exchange");
            if (!isListedVenue(exchange, text(row, "mic_code")))
                continue;
            instruments.add(new StockInstrument(AssetMarket.US.normalize(symbol), text(row, "name"),
                    currency, exchange, text(row, "mic_code"), type));
        }
        if (instruments.isEmpty())
            throw invalidPayload("empty US stock catalog");
        List<StockInstrument> result = instruments.stream()
                .collect(Collectors.toMap(StockInstrument::symbol, i -> i, (a, b) -> a, TreeMap::new))
                .values().stream().toList();
        cacheManager.getStockCatalogCache().put("US", result);
        return result;
    }

    private static boolean isStock(String type) {
        return type != null && Set.of("common stock", "preferred stock", "reit")
                .contains(type.toLowerCase(Locale.ROOT));
    }

    private static boolean isListedVenue(String exchange, String mic) {
        return Set.of("NASDAQ", "NYSE", "NYSE AMERICAN", "NYSE ARCA", "NYSE MKT", "AMEX",
                "CBOE", "BATS", "IEX", "MEMX")
                .contains(exchange == null ? "" : exchange.toUpperCase(Locale.ROOT))
                || Set.of("XNYS", "XNAS", "XNGS", "XNMS", "XNCM", "XASE", "ARCX", "BATS", "IEXG", "MEMX")
                        .contains(mic == null ? "" : mic);
    }

    @Override
    public Optional<Quote> fetchQuote(String rawSymbol) throws Exception {
        String symbol = AssetMarket.US.normalize(rawSymbol);
        Optional<StockInstrument> instrument = fetchCatalog().stream().filter(i -> i.symbol().equals(symbol))
                .findFirst();
        if (instrument.isEmpty())
            return Optional.empty();
        JsonNode row = request("quote", Map.of("symbol", symbol, "exchange", instrument.get().exchange(),
                "prepost", "false"));
        if (!symbol.equals(text(row, "symbol")) || !"USD".equals(text(row, "currency"))) {
            throw invalidPayload("quote identity");
        }
        Double price = number(row, "close");
        if (price == null || price <= 0)
            throw invalidPayload("quote price");
        Long timestamp = integer(row, "last_quote_at");
        if (timestamp == null)
            timestamp = integer(row, "timestamp");
        if (timestamp == null || timestamp <= 0)
            throw invalidPayload("quote timestamp");
        Quote quote = fundamentalsEnabled ? fundamentals(symbol).toBuilder().build() : new Quote();
        quote.setSymbol(symbol);
        quote.setCurrency("USD");
        quote.setExchange(instrument.get().exchange());
        quote.setShortName(instrument.get().name());
        quote.setLongName(instrument.get().name());
        quote.setType("stock");
        quote.setSubType("Preferred Stock".equalsIgnoreCase(instrument.get().type()) ? "preferred" : "common");
        quote.setCategory("EQUITIES");
        quote.setRegularMarketPrice(price);
        quote.setRegularMarketOpen(number(row, "open"));
        quote.setRegularMarketDayHigh(number(row, "high"));
        quote.setRegularMarketDayLow(number(row, "low"));
        quote.setRegularMarketVolume(integer(row, "volume"));
        quote.setRegularMarketPreviousClose(number(row, "previous_close"));
        quote.setRegularMarketChange(number(row, "change"));
        quote.setRegularMarketChangePercent(number(row, "percent_change"));
        quote.setRegularMarketTime(new Date(Math.multiplyExact(timestamp, 1000)));
        quote.setFiftyTwoWeekLow(number(row.path("fifty_two_week"), "low"));
        quote.setFiftyTwoWeekHigh(number(row.path("fifty_two_week"), "high"));
        quote.setMetadata(QuoteMetadata.builder().market("US").provider(getName())
                .feed("TWELVE_DATA_US_DEFAULT").mode("REALTIME_PARTIAL")
                .asOf(quote.getRegularMarketTime()).build());
        return Optional.of(QuoteEnricher.enrich(quote, AssetMarket.US));
    }

    @Override
    public Map<String, Quote> fetchQuotes(List<String> symbols) throws Exception {
        Map<String, Quote> result = new LinkedHashMap<>();
        for (String raw : symbols) {
            String symbol = AssetMarket.US.normalize(raw);
            fetchQuote(symbol).ifPresent(quote -> result.put(symbol, quote));
        }
        return result;
    }

    private Quote fundamentals(String symbol) throws Exception {
        String key = "US:" + getName() + ":" + symbol;
        Quote cached = cacheManager.getStockFundamentalsCache().getIfPresent(key);
        if (cached != null)
            return cached;
        JsonNode profile = request("profile", Map.of("symbol", symbol));
        JsonNode statistics = request("statistics", Map.of("symbol", symbol)).path("statistics");
        if (!statistics.isObject())
            throw invalidPayload("statistics");
        JsonNode logo = request("logo", Map.of("symbol", symbol));
        Quote result = new Quote();
        result.setSector(text(profile, "sector"));
        result.setIndustry(text(profile, "industry"));
        result.setLogoUrl(text(logo, "url"));
        result.setMarketCap(integer(statistics.path("valuations_metrics"), "market_capitalization"));
        result.setTwoHundredDayAverage(number(statistics.path("stock_price_summary"), "day_200_ma"));
        BigDecimal averageVolume = decimal(statistics.path("stock_statistics"), "avg_10_volume");
        if (averageVolume != null) {
            try {
                result.setAverageDailyVolume10Day(
                        averageVolume.setScale(0, java.math.RoundingMode.DOWN).longValueExact());
            } catch (ArithmeticException e) {
                throw invalidPayload("average volume");
            }
        }
        // A vendor's 90-day window is not necessarily CotaTrack's 63 trading sessions.
        JsonNode dividends = statistics.path("dividends_and_splits");
        result.setDividendRate(number(dividends, "forward_annual_dividend_rate"));
        result.setDividendYield(percent(number(dividends, "forward_annual_dividend_yield")));
        result.setTrailingAnnualDividendRate(number(dividends, "trailing_annual_dividend_rate"));
        result.setTrailingAnnualDividendYield(percent(number(dividends, "trailing_annual_dividend_yield")));
        cacheManager.getStockFundamentalsCache().put(key, result);
        return result;
    }

    private static Double percent(Double fraction) {
        return fraction == null ? null : fraction * 100;
    }

    @Override
    public void validateHistorical(LocalDate from, LocalDate to, Interval interval, Range range) {
        if (range != null && (from != null || to != null)) {
            throw new IllegalArgumentException("Use either range or from/to, not both.");
        }
        if (!INTERVALS.containsKey(interval == null ? Interval.ONE_DAY : interval)) {
            throw new IllegalArgumentException("Unsupported US interval. Supported: 1m,5m,15m,30m,60m,1h,1d,1wk,1mo.");
        }
        if (range == Range.MAX) {
            throw new IllegalArgumentException("US range=max is unsupported. Use explicit dates or a bounded range.");
        }
        LocalDate[] dates = dates(from, to, range);
        if (dates[0].isAfter(dates[1]) || dates[1].isAfter(LocalDate.now(EXCHANGE_ZONE))) {
            throw new IllegalArgumentException("Invalid US historical dates.");
        }
        if (dates[0].isBefore(dates[1].minusYears(10))) {
            throw new IllegalArgumentException("US historical requests are limited to 10 years.");
        }
        if (isIntraday(interval) && dates[0].isBefore(dates[1].minusDays(30))) {
            throw new IllegalArgumentException("US intraday requests are limited to 30 calendar days.");
        }
    }

    @Override
    public List<HistoricalQuote> fetchHistorical(String symbol, LocalDate from, LocalDate to,
            Interval interval, Range range) throws Exception {
        validateHistorical(from, to, interval, range);
        StockInstrument instrument = requireInstrument(symbol);
        LocalDate[] dates = dates(from, to, range);
        TreeMap<Long, HistoricalQuote> candles = new TreeMap<>();
        LocalDate cursor = dates[0];
        while (!cursor.isAfter(dates[1])) {
            LocalDate end = isIntraday(interval) ? cursor.plusDays(4) : cursor.plusYears(5).minusDays(1);
            if (end.isAfter(dates[1]))
                end = dates[1];
            JsonNode root = request("time_series", Map.of("symbol", AssetMarket.US.normalize(symbol),
                    "interval", INTERVALS.get(interval == null ? Interval.ONE_DAY : interval),
                    "start_date", cursor + " 00:00:00", "end_date", end + " 23:59:59",
                    "timezone", EXCHANGE_ZONE.getId(), "outputsize", "5000",
                    "order", "ASC", "adjust", "none", "prepost", "false", "exchange", instrument.exchange()));
            JsonNode meta = root.path("meta");
            if (!symbol.equalsIgnoreCase(text(meta, "symbol")) || !"USD".equals(text(meta, "currency"))) {
                throw invalidPayload("historical identity");
            }
            JsonNode rows = requireArray(root, "values");
            if (rows.size() >= 5000)
                throw invalidPayload("truncated historical window");
            for (JsonNode row : rows) {
                String datetime = text(row, "datetime");
                if (datetime == null)
                    throw invalidPayload("historical timestamp");
                ZonedDateTime instant = datetime.length() == 10
                        ? LocalDate.parse(datetime).atStartOfDay(EXCHANGE_ZONE)
                        : LocalDateTime.parse(datetime.replace(' ', 'T')).atZone(EXCHANGE_ZONE);
                LocalDate day = instant.toLocalDate();
                if (day.isBefore(dates[0]) || day.isAfter(dates[1]))
                    continue;
                BigDecimal close = decimal(row, "close");
                if (close == null)
                    throw invalidPayload("historical close");
                long epoch = instant.toEpochSecond();
                candles.put(epoch, new HistoricalQuote(epoch, decimal(row, "open"), decimal(row, "low"),
                        decimal(row, "high"), close, null, integer(row, "volume")));
            }
            cursor = end.plusDays(1);
        }
        return List.copyOf(candles.values());
    }

    private static boolean isIntraday(Interval interval) {
        return interval != null && interval != Interval.ONE_DAY && interval != Interval.ONE_WEEK
                && interval != Interval.ONE_MONTH;
    }

    private static LocalDate[] dates(LocalDate from, LocalDate to, Range range) {
        LocalDate end = to == null ? LocalDate.now(EXCHANGE_ZONE) : to;
        LocalDate start = from;
        if (start == null) {
            start = range == null ? end.minusMonths(1) : switch (range) {
                case ONE_DAY -> end;
                case FIVE_DAYS -> end.minusDays(5);
                case ONE_MONTH -> end.minusMonths(1);
                case THREE_MONTHS -> end.minusMonths(3);
                case SIX_MONTHS -> end.minusMonths(6);
                case ONE_YEAR -> end.minusYears(1);
                case TWO_YEARS -> end.minusYears(2);
                case FIVE_YEARS -> end.minusYears(5);
                case TEN_YEARS -> end.minusYears(10);
                case YEAR_TO_DATE -> end.withDayOfYear(1);
                case MAX -> throw new IllegalArgumentException("US range=max is unsupported.");
            };
        }
        return new LocalDate[] { start, end };
    }

    @Override
    public DividendsData fetchDividends(String symbol) throws Exception {
        StockInstrument instrument = requireInstrument(symbol);
        Map<String, String> parameters = Map.of("symbol", AssetMarket.US.normalize(symbol),
                "start_date", "1900-01-01", "end_date", LocalDate.now(EXCHANGE_ZONE).toString(),
                "exchange", instrument.exchange());
        JsonNode cashRoot = request("dividends", parameters);
        JsonNode splitRoot = request("splits", parameters);
        for (JsonNode root : List.of(cashRoot, splitRoot)) {
            if (!symbol.equalsIgnoreCase(text(root.path("meta"), "symbol"))
                    || !"USD".equals(text(root.path("meta"), "currency"))) {
                throw invalidPayload("corporate action identity");
            }
        }
        List<CashDividend> cash = new ArrayList<>();
        for (JsonNode row : requireArray(cashRoot, "dividends")) {
            CashDividend event = new CashDividend();
            BigDecimal amount = decimal(row, "amount");
            if (amount == null || text(row, "ex_date") == null)
                throw invalidPayload("cash dividend");
            event.setRate(amount.toPlainString());
            event.setExDate(text(row, "ex_date"));
            event.setCurrency(text(cashRoot.path("meta"), "currency"));
            event.setPaymentDate(text(row, "payment_date"));
            event.setApprovedOn(text(row, "declaration_date"));
            event.setLabel("DIVIDEND");
            cash.add(event);
        }
        List<StockDividend> splits = new ArrayList<>();
        for (JsonNode row : requireArray(splitRoot, "splits")) {
            StockDividend event = new StockDividend();
            event.setExDate(text(row, "date"));
            event.setFromFactor(decimal(row, "from_factor"));
            event.setToFactor(decimal(row, "to_factor"));
            if (event.getExDate() == null || event.getFromFactor() == null || event.getToFactor() == null
                    || event.getFromFactor().signum() <= 0 || event.getToFactor().signum() <= 0) {
                throw invalidPayload("split");
            }
            event.setEventType("SPLIT");
            event.setLabel(text(row, "description"));
            // Keep the provider's named factors; B3's percentage factor is not equivalent.
            splits.add(event);
        }
        return new DividendsData(List.copyOf(cash), List.copyOf(splits));
    }

    private StockInstrument requireInstrument(String rawSymbol) throws Exception {
        String symbol = AssetMarket.US.normalize(rawSymbol);
        return fetchCatalog().stream().filter(i -> i.symbol().equals(symbol)).findFirst()
                .orElseThrow(() -> new ProviderException(getName(), 404, "US stock not found: " + symbol));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null)
            return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw invalidPayload("numeric field " + field);
        }
    }

    private static Double number(JsonNode node, String field) {
        BigDecimal value = decimal(node, field);
        if (value == null)
            return null;
        double result = value.doubleValue();
        if (!Double.isFinite(result))
            throw invalidPayload("non-finite " + field);
        return result;
    }

    private static Long integer(JsonNode node, String field) {
        BigDecimal value = decimal(node, field);
        if (value == null)
            return null;
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            throw invalidPayload("integer field " + field);
        }
    }

    private static JsonNode requireArray(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isArray())
            throw invalidPayload(field);
        return value;
    }

    private static ProviderException invalidPayload(String field) {
        return new ProviderException(PROVIDER_NAME, 502, "Invalid Twelve Data payload: " + field);
    }
}
