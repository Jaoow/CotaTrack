package dev.jaoow.cotatrack.api.provider.forex;

import dev.jaoow.cotatrack.api.requests.quotes.model.FxQuote;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Interface for foreign exchange quote providers.
 */
public interface FxQuoteProvider {

    String getName();

    boolean isEnabled();

    Optional<FxQuote> fetchFxQuote(String symbol) throws Exception;

    Map<String, FxQuote> fetchFxQuotes(List<String> symbols) throws Exception;
}
