package dev.jaoow.cotatrack.api.provider.forex;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jaoow.cotatrack.api.provider.ProviderException;
import dev.jaoow.cotatrack.api.requests.quotes.model.FxQuote;
import dev.jaoow.cotatrack.api.yahoo.YahooConstants;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Primary foreign exchange provider using Banco Central do Brasil (BCB PTAX via Olinda OData API).
 * Provides official closing exchange rates against BRL.
 */
@Slf4j
public class BcbPtaxFxProvider implements FxQuoteProvider {

    public static final String PROVIDER_NAME = "BCB_PTAX";

    private static final String OLINDA_BASE_URL = "https://olinda.bcb.gov.br/olinda/servico/PTAX/versao/v1/odata/CotacaoMoedaPeriodo";
    private static final String BCB_SGS_USD_URL = "https://api.bcb.gov.br/dados/serie/bcdata.sgs.1/dados/ultimos/1?formato=json";

    private static final DateTimeFormatter OLINDA_DATE_FORMAT = DateTimeFormatter.ofPattern("MM-dd-yyyy");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(4);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "bcb-fx-fetcher");
        t.setDaemon(true);
        return t;
    });

    public BcbPtaxFxProvider() {
        this(
                HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(),
                Boolean.parseBoolean(System.getProperty("cotatrack.providers.bcb.enabled", "true"))
        );
    }

    public BcbPtaxFxProvider(HttpClient httpClient, boolean enabled) {
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
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String rawSym : symbols) {
            Optional<BcbCurrencyMapper.CurrencyInfo> infoOpt = BcbCurrencyMapper.mapSymbol(rawSym);
            if (infoOpt.isEmpty()) {
                log.warn("Symbol '{}' is not supported by BCB PTAX, skipping", rawSym);
                continue;
            }

            BcbCurrencyMapper.CurrencyInfo info = infoOpt.get();
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    FxQuote quote = queryBcbForCurrency(rawSym, info);
                    if (quote != null) {
                        synchronized (results) {
                            results.put(rawSym, quote);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to fetch BCB PTAX quote for currency '{}': {}", info.code(), e.getMessage());
                }
            }, executor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return results;
    }

    private FxQuote queryBcbForCurrency(String requestedSymbol, BcbCurrencyMapper.CurrencyInfo info) {
        try {
            return queryOlinda(requestedSymbol, info);
        } catch (Exception e) {
            log.warn("BCB Olinda failed for currency {}: {}. Attempting fallback...", info.code(), e.getMessage());
            if ("USD".equalsIgnoreCase(info.code())) {
                return querySgsUsd(requestedSymbol, info);
            }
            throw new ProviderException(PROVIDER_NAME, 500, "Olinda PTAX failed: " + e.getMessage());
        }
    }

    private FxQuote queryOlinda(String requestedSymbol, BcbCurrencyMapper.CurrencyInfo info) throws IOException, InterruptedException {
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(7); // 7-day lookback window ensures coverage over weekends/holidays

        String url = String.format("%s(moeda='%s',dataInicial='%s',dataFinalCotacao='%s')?$top=1&$orderby=dataHoraCotacao%%20desc&$format=json",
                OLINDA_BASE_URL,
                info.code(),
                startDate.format(OLINDA_DATE_FORMAT),
                today.format(OLINDA_DATE_FORMAT)
        );

        log.debug("Sending BCB Olinda request: {}", url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", YahooConstants.USER_AGENT)
                .header("Accept", "application/json")
                .timeout(DEFAULT_TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new ProviderException(PROVIDER_NAME, response.statusCode(), "BCB Olinda returned HTTP " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode valueNode = root.get("value");
        if (valueNode == null || !valueNode.isArray() || valueNode.isEmpty()) {
            throw new ProviderException(PROVIDER_NAME, 404, "No PTAX data found for currency " + info.code());
        }

        JsonNode latest = valueNode.get(0);
        BigDecimal cotacaoVenda = latest.has("cotacaoVenda") ? latest.get("cotacaoVenda").decimalValue() : BigDecimal.ZERO;
        BigDecimal cotacaoCompra = latest.has("cotacaoCompra") ? latest.get("cotacaoCompra").decimalValue() : cotacaoVenda;
        String dataHora = latest.has("dataHoraCotacao") ? latest.get("dataHoraCotacao").asText() : "";

        return FxQuote.builder()
                .symbol(requestedSymbol)
                .name(info.name())
                .currency("BRL")
                .price(cotacaoVenda)
                .buyPrice(cotacaoCompra)
                .sellPrice(cotacaoVenda)
                .quoteDate(dataHora)
                .provider(PROVIDER_NAME)
                .build();
    }

    private FxQuote querySgsUsd(String requestedSymbol, BcbCurrencyMapper.CurrencyInfo info) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BCB_SGS_USD_URL))
                    .header("User-Agent", YahooConstants.USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(DEFAULT_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode array = objectMapper.readTree(response.body());
                if (array.isArray() && !array.isEmpty()) {
                    JsonNode node = array.get(0);
                    String date = node.get("data").asText();
                    BigDecimal valor = new BigDecimal(node.get("valor").asText());

                    return FxQuote.builder()
                            .symbol(requestedSymbol)
                            .name(info.name())
                            .currency("BRL")
                            .price(valor)
                            .sellPrice(valor)
                            .buyPrice(valor)
                            .quoteDate(date)
                            .provider(PROVIDER_NAME + "_SGS")
                            .build();
                }
            }
        } catch (Exception e) {
            log.warn("BCB SGS fallback failed: {}", e.getMessage());
        }
        return null;
    }
}
