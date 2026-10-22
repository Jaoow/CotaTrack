package dev.jaoow.cotatrack.api;

import dev.jaoow.cotatrack.api.classification.AssetMarket;
import dev.jaoow.cotatrack.api.provider.ProviderException;
import dev.jaoow.cotatrack.api.provider.ProviderResult;
import dev.jaoow.cotatrack.api.provider.QuoteMetadata;
import dev.jaoow.cotatrack.api.provider.enricher.QuoteEnricher;
import dev.jaoow.cotatrack.api.provider.orchestrator.ProviderOrchestrator;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.HistoricalQuote;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.Interval;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.Range;
import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;
import dev.jaoow.cotatrack.api.requests.quotes.model.StockQueryError;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.time.*;
import java.util.*;

@Builder
@Slf4j
public class StocksQuery {
    private final ProviderOrchestrator orchestrator;
    private final List<String> symbols;
    private final LocalDate from;
    private final LocalDate to;
    private final Interval interval;
    private final Range range;
    private final boolean includeDividends;

    public record Result(List<Quote> results, Map<String, QuoteMetadata> metadata, List<StockQueryError> errors) {
    }

    public Result fetch() {
        if (orchestrator == null || orchestrator.getMarket() != AssetMarket.US) {
            throw new IllegalArgumentException("STOCKS requires a US orchestrator.");
        }
        if (symbols == null || symbols.isEmpty() || symbols.size() > 50) {
            throw new IllegalArgumentException("Provide between 1 and 50 US stock symbols.");
        }
        boolean historyRequested = from != null || to != null || interval != null || range != null;
        if (historyRequested) orchestrator.validateHistorical(from, to, interval, range);
        ProviderResult<Map<String, Quote>> batch = orchestrator.fetchQuotes(symbols);
        List<Quote> results = new ArrayList<>();
        Map<String, QuoteMetadata> metadata = new LinkedHashMap<>();
        List<StockQueryError> errors = new ArrayList<>();
        for (Map.Entry<String, ProviderResult<Quote>> entry : batch.getItems().entrySet()) {
            String symbol = entry.getKey();
            ProviderResult<Quote> item = entry.getValue();
            if (item.getError() != null) {
                errors.add(error(symbol, "quote", item.getError()));
                continue;
            }
            Quote quote = item.getData().toBuilder().build();
            if (historyRequested) {
                try {
                    List<HistoricalQuote> history = orchestrator.fetchHistorical(symbol, from, to, interval, range).getData();
                    quote.setHistoricalQuotes(history);
                    if (quote.getMetadata() != null) {
                        quote.setMetadata(quote.getMetadata().toBuilder()
                                .historicalFeed("TWELVE_DATA_US_HISTORY_RAW").build());
                    }
                    enrichDailyHistory(quote, history);
                } catch (ProviderException e) {
                    errors.add(error(symbol, "history", e));
                }
            }
            if (includeDividends) {
                try {
                    quote.setDividends(orchestrator.fetchDividends(symbol).getData());
                } catch (ProviderException e) {
                    errors.add(error(symbol, "dividends", e));
                }
            }
            QuoteEnricher.enrich(quote, AssetMarket.US);
            results.add(quote);
            if (quote.getMetadata() != null) metadata.put(symbol, quote.getMetadata());
        }
        return new Result(List.copyOf(results), Collections.unmodifiableMap(metadata), List.copyOf(errors));
    }

    private void enrichDailyHistory(Quote quote, List<HistoricalQuote> history) {
        if (interval != null && interval != Interval.ONE_DAY) return;
        ZoneId zone = ZoneId.of("America/New_York");
        LocalDate today = LocalDate.now(zone);
        // Only a recent, completed daily window can describe the current quote.
        List<HistoricalQuote> daily = history.stream()
                .filter(c -> Instant.ofEpochSecond(c.getDate()).atZone(zone).toLocalDate().isBefore(today))
                .toList();
        if (daily.isEmpty()) return;
        LocalDate last = Instant.ofEpochSecond(daily.get(daily.size() - 1).getDate()).atZone(zone).toLocalDate();
        if (!last.isBefore(today.minusDays(7))) {
            QuoteEnricher.enrichFromHistory(quote, daily, AssetMarket.US);
        }
    }

    private static StockQueryError error(String symbol, String operation, ProviderException e) {
        int status = e.getStatusCode() == 404 ? 404 : 503;
        log.warn("STOCKS {} unavailable for {}: {}", operation, symbol, e.getMessage());
        return new StockQueryError(symbol, operation, status,
                status == 404 ? "No US " + operation + " data found." : "US " + operation + " provider unavailable.");
    }
}
