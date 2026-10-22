package dev.jaoow.cotatrack.api.provider;

import dev.jaoow.cotatrack.api.requests.historicalquotes.model.HistoricalQuote;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.Interval;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.Range;
import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import dev.jaoow.cotatrack.api.classification.AssetMarket;
import dev.jaoow.cotatrack.api.requests.dividends.model.DividendsData;
import dev.jaoow.cotatrack.api.requests.quotes.model.StockInstrument;

/**
 * Interface representing a market data provider for stock quotes and historical candles.
 */
public interface StockQuoteProvider {

    default AssetMarket getMarket() {
        return AssetMarket.B3;
    }

    default boolean supportsDividends() {
        return false;
    }

    default DividendsData fetchDividends(String symbol) throws Exception {
        throw new UnsupportedOperationException("Dividends are not supported by " + getName());
    }

    default boolean supportsCatalog() {
        return false;
    }

    default List<StockInstrument> fetchCatalog() throws Exception {
        throw new UnsupportedOperationException("Catalog is not supported by " + getName());
    }

    default void validateHistorical(LocalDate from, LocalDate to, Interval interval, Range range) {
    }

    /**
     * Unique provider identifier (e.g., "TRADING_VIEW", "YAHOO_CHART", "BRAPI").
     */
    String getName();

    /**
     * Checks if this provider is currently enabled in configuration.
     */
    boolean isEnabled();

    /**
     * Fetches quote for a single symbol.
     *
     * @param symbol ticker code (e.g. "PETR4")
     * @return Optional containing Quote if found, or empty
     * @throws Exception on provider failure
     */
    Optional<Quote> fetchQuote(String symbol) throws Exception;

    /**
     * Fetches quotes for multiple symbols in batch.
     *
     * @param symbols list of ticker codes
     * @return map of symbol to Quote
     * @throws Exception on provider failure
     */
    Map<String, Quote> fetchQuotes(List<String> symbols) throws Exception;

    /**
     * Fetches historical quotes for a given symbol and interval/range.
     */
    List<HistoricalQuote> fetchHistorical(String symbol, LocalDate from, LocalDate to, Interval interval, Range range) throws Exception;

    /**
     * Whether this provider supports historical candles retrieval.
     */
    default boolean supportsHistorical() {
        return false;
    }
}
