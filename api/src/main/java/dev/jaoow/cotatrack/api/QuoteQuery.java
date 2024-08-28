package dev.jaoow.cotatrack.api;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        Map<String, Quote> result = new HashMap<>();
        StockQuoteRequest request = new StockQuoteRequest(query);

        List<Quote> quotes = request.getResult();
        for (Quote quote : quotes) {
            result.put(quote.getSymbol(), quote);
        }

        boolean includeHistorical = from != null || to != null || interval != null || range != null;

        if (includeHistorical) {
            log.info("Fetching historical data for {} symbols", result.size());
            for (Quote quote : result.values()) {
                HistoricalQuotesRequest.Builder requestBuilder
                        = HistoricalQuotesRequest.builder().symbol(quote.getSymbol());

                Optional.ofNullable(from).ifPresent(requestBuilder::from);
                Optional.ofNullable(to).ifPresent(requestBuilder::to);
                Optional.ofNullable(interval).ifPresent(requestBuilder::interval);
                Optional.ofNullable(range).ifPresent(requestBuilder::range);

                HistoricalQuotesRequest historicalRequest = requestBuilder.build();
                quote.fetchHistoricalQuotes(historicalRequest);
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
