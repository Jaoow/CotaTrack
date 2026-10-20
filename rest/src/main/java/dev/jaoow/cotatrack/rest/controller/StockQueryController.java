package dev.jaoow.cotatrack.rest.controller;

import dev.jaoow.cotatrack.rest.dto.Response;
import dev.jaoow.cotatrack.rest.dto.StockResult;
import dev.jaoow.cotatrack.rest.dto.QueryResponse;
import dev.jaoow.cotatrack.api.requests.quotes.model.FxQuote;
import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;
import dev.jaoow.cotatrack.rest.exception.BadRequestException;
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
            @RequestParam(required = false) Boolean dividends,
            @RequestParam(required = false) Boolean includeDividends
    ) {
        boolean withDividends = Boolean.TRUE.equals(dividends) || Boolean.TRUE.equals(includeDividends);
        return stockQueryService.fetchStockQuotes(symbols, from, to, interval, range, withDividends);
    }

    @GetMapping({"/forex/{*symbols}", "/forex"})
    public QueryResponse<FxQuote> getFxQuotes(
            @PathVariable(required = false) String symbols,
            @RequestParam(required = false) String pair
    ) {
        String target = symbols;
        if (target != null && target.startsWith("/")) {
            target = target.substring(1);
        }
        if ((target == null || target.isBlank()) && pair != null) {
            target = pair;
        }
        if (target == null || target.isBlank()) {
            throw new BadRequestException("Forex symbol is required (e.g. /api/forex/USD/BRL)");
        }
        return stockQueryService.fetchFxQuotes(target);
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
