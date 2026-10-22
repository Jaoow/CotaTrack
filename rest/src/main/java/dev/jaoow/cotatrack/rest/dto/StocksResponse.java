package dev.jaoow.cotatrack.rest.dto;

import dev.jaoow.cotatrack.api.StocksQuery;
import dev.jaoow.cotatrack.api.provider.QuoteMetadata;
import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;
import dev.jaoow.cotatrack.api.requests.quotes.model.StockQueryError;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
public class StocksResponse extends QueryResponse<Quote> {
    private final Map<String, QuoteMetadata> metadata;
    private final List<StockQueryError> errors;

    public StocksResponse(StocksQuery.Result result, long elapsedTime) {
        super(result.results(), elapsedTime);
        metadata = result.metadata();
        errors = result.errors();
    }
}
