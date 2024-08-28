package dev.jaoow.cotatrack.rest.service;

import dev.jaoow.cotatrack.api.tradingview.field.FieldKey;
import dev.jaoow.cotatrack.api.tradingview.field.StockField;
import dev.jaoow.cotatrack.api.tradingview.field.enums.Market;
import dev.jaoow.cotatrack.api.tradingview.field.enums.Type;
import dev.jaoow.cotatrack.api.tradingview.screener.StockScreener;
import dev.jaoow.cotatrack.rest.dto.PagedResponse;
import dev.jaoow.cotatrack.rest.dto.QueryResponse;
import dev.jaoow.cotatrack.rest.dto.Response;
import dev.jaoow.cotatrack.rest.dto.StockResult;
import dev.jaoow.cotatrack.rest.exception.BadRequestException;
import dev.jaoow.cotatrack.rest.exception.FailedFetchException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class StockSearchService {

    private static final StockField[] DEFAULT_STOCK_FIELDS = {
            StockField.NAME,
            StockField.OPEN,
            StockField.CLOSE,
            StockField.CHANGE_PERCENT,
            StockField.CHANGE,
            StockField.VOLUME,
    };

    private static final Map<String, StockField> STOCK_FIELD_MAP = new HashMap<>();
    private final StockScreener stockScreener;

    static {
        for (StockField field : DEFAULT_STOCK_FIELDS) {
            STOCK_FIELD_MAP.put(field.name(), field);
        }
    }

    StockSearchService() {
        this.stockScreener = initializeStockScreener();
    }

    private StockScreener initializeStockScreener() {
        return StockScreener.create()
                .withPriceConversion("BRL")
                .withStockFields(DEFAULT_STOCK_FIELDS)
                .withTypes(Type.values())
                .withMarkets(Market.BRAZIL)
                .withSortBy(StockField.VOLUME, false);
    }

    public Response<StockResult> generateStockSearch(String search,
                                                     String type,
                                                     String sortBy,
                                                     String sortOrder,
                                                     Integer limit,
                                                     Integer page) {
        StockScreener stockScreener = this.stockScreener.startIsolatedQuery();

        configureSortBy(stockScreener, sortBy, sortOrder);
        configureSearchType(stockScreener, type);
        configureSearchQuery(stockScreener, search);

        long elapsedTime = calculateElapsedTime(() -> fetchStockResults(stockScreener));
        validatePaginationParameters(page, limit);

        return paginateResults(fetchStockResults(stockScreener), page, limit, elapsedTime);
    }

    private void configureSortBy(StockScreener stockScreener, String sortBy, String sortOrder) {
        if (sortBy != null) {
            FieldKey sortField = STOCK_FIELD_MAP.get(sortBy.toUpperCase());
            if (sortField == null) {
                throw new BadRequestException("Invalid sort field: " + sortBy);
            }

            boolean isAscending = "asc".equalsIgnoreCase(sortOrder);
            stockScreener.withSortBy(sortField, isAscending);
        }
    }

    private void configureSearchType(StockScreener stockScreener, String type) {
        if (type != null) {
            Type[] searchTypes = parseSearchTypes(type);
            stockScreener.removeFilter(StockField.TYPE); // Remove default filter
            stockScreener.withTypes(searchTypes);
        }
    }

    private Type[] parseSearchTypes(String type) {
        String[] types = type.split(",");
        Type[] searchTypes = new Type[types.length];

        for (int i = 0; i < types.length; i++) {
            Type searchType = Type.fromTag(types[i]);
            if (searchType == null) {
                throw new BadRequestException("Invalid search type: " + types[i]);
            }
            searchTypes[i] = searchType;
        }

        return searchTypes;
    }

    private void configureSearchQuery(StockScreener stockScreener, String search) {
        if (search != null) {
            stockScreener.withSearch(search);
        }
    }

    private List<StockResult> fetchStockResults(StockScreener stockScreener) {
        try {
            return stockScreener.fetchAs(StockResult.class);
        } catch (Exception e) {
            throw new FailedFetchException("Failed to fetch stocks. Please try again later.");
        }
    }

    private void validatePaginationParameters(Integer page, Integer limit) {
        if (page != null && limit == null) {
            throw new BadRequestException("Limit parameter is required when using the page parameter.");
        }
    }

    private long calculateElapsedTime(Runnable fetchOperation) {
        LocalDateTime startTime = LocalDateTime.now();
        fetchOperation.run();
        return Duration.between(startTime, LocalDateTime.now()).toMillis();
    }

    private Response<StockResult> paginateResults(List<StockResult> results, Integer page, Integer limit, long elapsedTime) {
        if (limit != null) {
            return PagedResponse.paginate(results, page == null ? 0 : page, limit);
        }
        return new QueryResponse<>(results, elapsedTime);
    }
}
