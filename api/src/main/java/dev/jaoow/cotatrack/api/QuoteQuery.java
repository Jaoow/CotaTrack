package dev.jaoow.cotatrack.api;

import dev.jaoow.cotatrack.api.provider.ProviderResult;
import dev.jaoow.cotatrack.api.provider.orchestrator.ProviderOrchestrator;
import dev.jaoow.cotatrack.api.provider.orchestrator.ProviderOrchestratorHolder;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.HistoricalQuote;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.Interval;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.Range;
import dev.jaoow.cotatrack.api.requests.historicalquotes.HistoricalQuotesRequest;
import dev.jaoow.cotatrack.api.requests.quotes.model.FxQuote;
import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;
import dev.jaoow.cotatrack.api.requests.quotes.FxQuoteRequest;
import dev.jaoow.cotatrack.api.requests.quotes.StockQuoteRequest;
import dev.jaoow.cotatrack.api.yahoo.SymbolMapper;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

/**
 * QuoteQuery class provides methods to fetch stock quotes based on various parameters.
 */
@Slf4j
@Data
@Builder
public class QuoteQuery {

    private String symbol;
    private String[] symbols;
    private LocalDate from;
    private LocalDate to;
    private Interval interval;
    private Range range;

    @Builder.Default
    private boolean includeDividends = false;

    @Builder.Default
    private ProviderOrchestrator orchestrator = ProviderOrchestratorHolder.getOrchestrator();

    /**
     * Fetches a stock quote based on the built query parameters.
     *
     * @return the stock quote
     * @throws IOException if an I/O error occurs
     */
    public Quote fetch() throws IOException {
        // Map the symbol to the correct format
        this.symbol = SymbolMapper.mapSymbol(this.symbol);
        Map<String, Quote> result = fetchQuotes(this.symbol);
        return result.get(this.symbol.toUpperCase());
    }

    /**
     * Fetches stock quotes for multiple symbols based on the built query parameters.
     *
     * @return a map of stock quotes
     * @throws IOException if an I/O error occurs
     */
    public Map<String, Quote> fetchMultiple() throws IOException {
        // Map the symbols to the correct format
        this.symbols = SymbolMapper.mapSymbols(this.symbols);
        return fetchQuotes(String.join(",", symbols));
    }

    /**
     * Fetches stock quotes based on the built query parameters.
     *
     * @param query the stock query
     * @return a map of stock quotes
     * @throws IOException if an I/O error occurs
     */
    private Map<String, Quote> fetchQuotes(String query) throws IOException {
        List<String> symbolList = Arrays.asList(query.split(","));
        ProviderOrchestrator orch = (orchestrator != null) ? orchestrator : ProviderOrchestratorHolder.getOrchestrator();

        ProviderResult<Map<String, Quote>> providerResult = orch.fetchQuotes(symbolList);
        Map<String, Quote> result = new HashMap<>(providerResult.getData());

        // If orchestrator returned empty for all symbols, attempt legacy StockQuoteRequest as emergency fallback
        if (result.isEmpty()) {
            log.warn("Orchestrator returned empty for {}. Attempting legacy StockQuoteRequest...", query);
            StockQuoteRequest request = new StockQuoteRequest(query);
            List<Quote> quotes = request.getResult();
            for (Quote quote : quotes) {
                result.put(quote.getSymbol(), quote);
            }
        }

        boolean includeHistorical = from != null || to != null || interval != null || range != null;

        if (includeHistorical) {
            log.info("Fetching historical data for {} symbols", result.size());
            for (Quote quote : result.values()) {
                ProviderResult<List<HistoricalQuote>> histResult = orch.fetchHistorical(
                        quote.getSymbol(), from, to, interval, range
                );
                if (histResult.getData() != null && !histResult.getData().isEmpty()) {
                    quote.setHistoricalQuotes(histResult.getData());
                } else {
                    HistoricalQuotesRequest.Builder requestBuilder
                            = HistoricalQuotesRequest.builder().symbol(quote.getSymbol());
                    Optional.ofNullable(from).ifPresent(requestBuilder::from);
                    Optional.ofNullable(to).ifPresent(requestBuilder::to);
                    Optional.ofNullable(interval).ifPresent(requestBuilder::interval);
                    Optional.ofNullable(range).ifPresent(requestBuilder::range);
                    quote.fetchHistoricalQuotes(requestBuilder.build());
                }
            }
        }

        if (includeDividends) {
            log.info("Fetching dividends data for {} symbols", result.size());
            for (Quote quote : result.values()) {
                quote.fetchDividendsData();
            }
        }

        return result;
    }

    /**
     * Fetches a forex quote based on the built query parameters.
     *
     * @return the forex quote
     * @throws IOException if an I/O error occurs
     */
    public FxQuote fetchFx() throws IOException {
        FxQuoteRequest request = new FxQuoteRequest(symbol);
        return request.getSingleResult();
    }

    /**
     * Fetches forex quotes for multiple symbols based on the built query parameters.
     *
     * @return a map of forex quotes
     * @throws IOException if an I/O error occurs
     */
    public Map<String, FxQuote> fetchFxMultiple() throws IOException {
        FxQuoteRequest request = new FxQuoteRequest(String.join(",", symbols));
        List<FxQuote> quotes = request.getResult();
        Map<String, FxQuote> result = new HashMap<>();

        for (FxQuote quote : quotes) {
            result.put(quote.getSymbol(), quote);
        }

        return result;
    }
}
