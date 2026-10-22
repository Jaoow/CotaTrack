package dev.jaoow.cotatrack.rest.dto;

import dev.jaoow.cotatrack.api.requests.quotes.model.StockQueryError;
import lombok.Getter;

import java.util.List;

@Getter
public class StocksListResponse extends PagedResponse<UsStockResult> {
    private final List<StockQueryError> errors;

    public StocksListResponse(List<UsStockResult> results, int page, int totalPages, int limit,
                              long totalCount, boolean hasNextPage, List<StockQueryError> errors) {
        super(results, page, totalPages, limit, totalCount, hasNextPage);
        this.errors = errors;
    }
}
