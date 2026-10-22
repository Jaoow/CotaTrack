package dev.jaoow.cotatrack.rest.service;

import dev.jaoow.cotatrack.api.StocksQuery;
import dev.jaoow.cotatrack.api.classification.AssetMarket;
import dev.jaoow.cotatrack.api.provider.ProviderException;
import dev.jaoow.cotatrack.api.provider.orchestrator.ProviderOrchestrator;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.Interval;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.Range;
import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;
import dev.jaoow.cotatrack.api.requests.quotes.model.StockInstrument;
import dev.jaoow.cotatrack.rest.dto.StocksListResponse;
import dev.jaoow.cotatrack.rest.dto.StocksResponse;
import dev.jaoow.cotatrack.rest.dto.UsStockResult;
import dev.jaoow.cotatrack.rest.exception.NotFoundException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class UsStocksService {
    private final ProviderOrchestrator orchestrator;

    public UsStocksService(@Qualifier("usProviderOrchestrator") ProviderOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    public StocksResponse fetch(String symbols, LocalDate from, LocalDate to,
                                String interval, String range, boolean dividends) {
        long start = System.nanoTime();
        StocksQuery.Result result = StocksQuery.builder().orchestrator(orchestrator).symbols(parseSymbols(symbols))
                .from(from).to(to).interval(interval == null ? null : Interval.fromTag(interval))
                .range(range == null ? null : Range.fromTag(range)).includeDividends(dividends).build().fetch();
        if (result.results().isEmpty()) {
            if (!result.errors().isEmpty() && result.errors().stream().allMatch(e -> e.status() == 404)) {
                throw new NotFoundException("No US stocks found for the given symbols.");
            }
            throw new ProviderException("US", 503, "US quote providers unavailable.");
        }
        return new StocksResponse(result, (System.nanoTime() - start) / 1_000_000);
    }

    public StocksListResponse list(String search, String type, String sortBy, String sortOrder, int limit, int page) {
        if (limit < 1 || limit > 50 || page < 0 || page == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("US pagination requires limit=1..50 and a non-negative page.");
        }
        if (!"asc".equalsIgnoreCase(sortOrder) && !"desc".equalsIgnoreCase(sortOrder)) {
            throw new IllegalArgumentException("US sortOrder must be asc or desc.");
        }
        String sort = sortBy == null ? "name" : sortBy.toLowerCase(Locale.ROOT);
        Function<StockInstrument, String> sortField = switch (sort) {
            case "name" -> StockInstrument::symbol;
            case "longname" -> StockInstrument::name;
            case "exchange" -> StockInstrument::exchange;
            default -> throw new IllegalArgumentException(
                    "Unsupported US sort field. Supported: name,longName,exchange. Global price/volume sorting is unavailable.");
        };
        Set<String> types = type == null ? Set.of("stock") : Arrays.stream(type.split(",", -1))
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        if (types.isEmpty() || !Set.of("stock", "common", "preferred").containsAll(types)) {
            throw new IllegalArgumentException("Unsupported US type. Supported: stock,common,preferred.");
        }
        String needle = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        Comparator<StockInstrument> comparator = Comparator.comparing(sortField,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)).thenComparing(StockInstrument::symbol);
        if ("desc".equalsIgnoreCase(sortOrder)) comparator = comparator.reversed();
        List<StockInstrument> catalog = orchestrator.fetchCatalog().getData().stream()
                .filter(i -> types.contains("stock") || types.contains(
                        "Preferred Stock".equalsIgnoreCase(i.type()) ? "preferred" : "common"))
                .filter(i -> i.symbol().toLowerCase(Locale.ROOT).contains(needle)
                        || (i.name() != null && i.name().toLowerCase(Locale.ROOT).contains(needle)))
                .sorted(comparator).toList();
        int total = catalog.size();
        int start = (int) Math.min((long) page * limit, total);
        int end = Math.min(start + limit, total);
        List<StockInstrument> selected = catalog.subList(start, end);
        StocksQuery.Result quotes = selected.isEmpty() ? new StocksQuery.Result(List.of(), Map.of(), List.of())
                : StocksQuery.builder().orchestrator(orchestrator)
                .symbols(selected.stream().map(StockInstrument::symbol).toList()).build().fetch();
        Map<String, Quote> bySymbol = quotes.results().stream().collect(Collectors.toMap(Quote::getSymbol, q -> q));
        List<UsStockResult> results = selected.stream().map(i -> {
            Quote quote = bySymbol.get(i.symbol());
            return new UsStockResult(i.symbol(), i.name(), i.currency(), i.exchange(),
                    "Preferred Stock".equalsIgnoreCase(i.type()) ? "preferred" : "common",
                    quote == null ? null : quote.getRegularMarketVolume(),
                    quote == null ? null : decimal(quote.getRegularMarketOpen()),
                    quote == null ? null : decimal(quote.getRegularMarketPrice()),
                    quote == null ? null : decimal(quote.getRegularMarketChange()),
                    quotes.metadata().get(i.symbol()));
        }).toList();
        return new StocksListResponse(results, page + 1, (total + limit - 1) / limit,
                limit, total, end < total, quotes.errors());
    }

    private static List<String> parseSymbols(String symbols) {
        if (symbols == null) throw new IllegalArgumentException("US symbols are required.");
        String[] values = symbols.split(",", -1);
        if (values.length > 50) throw new IllegalArgumentException("At most 50 US symbols are allowed.");
        return Arrays.stream(values).map(AssetMarket.US::normalize).distinct().toList();
    }

    private static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }
}
