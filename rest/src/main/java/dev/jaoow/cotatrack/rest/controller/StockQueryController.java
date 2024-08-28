package dev.jaoow.cotatrack.rest.controller;

import dev.jaoow.cotatrack.rest.dto.Response;
import dev.jaoow.cotatrack.rest.dto.StockResult;
import dev.jaoow.cotatrack.rest.dto.QueryResponse;
import dev.jaoow.cotatrack.api.requests.quotes.model.FxQuote;
import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;
import dev.jaoow.cotatrack.rest.service.StockQueryService;
import dev.jaoow.cotatrack.rest.service.StockSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class StockQueryController {

    private final StockQueryService stockQueryService;
    private final StockSearchService stockSearchService;

    @GetMapping("/quote/{symbols}")
    public QueryResponse<Quote> getStockQuotes(
            @PathVariable String symbols,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String interval,
            @RequestParam(required = false) String range,
            @RequestParam(defaultValue = "false") boolean dividends
    ) {
        return stockQueryService.fetchStockQuotes(symbols, from, to, interval, range, dividends);
    }

    @GetMapping("/forex/{symbols}")
    public QueryResponse<FxQuote> getFxQuotes(@PathVariable String symbols) {
        return stockQueryService.fetchFxQuotes(symbols);
    }

    @GetMapping("/quote/list")
    public Response<StockResult> listStocks(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "asc") String sortOrder,
            @RequestParam(defaultValue = "20") Integer limit,
            @RequestParam(required = false) Integer page
    ) {
        return stockSearchService.generateStockSearch(search, type, sortBy, sortOrder, limit, page);

    }
}
