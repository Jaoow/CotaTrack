package dev.jaoow.cotatrack.rest.service;

import dev.jaoow.cotatrack.api.QuoteQuery;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.Interval;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.Range;
import dev.jaoow.cotatrack.api.requests.quotes.model.FxQuote;
import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;
import dev.jaoow.cotatrack.rest.dto.QueryResponse;
import dev.jaoow.cotatrack.rest.exception.BadRequestException;
import dev.jaoow.cotatrack.rest.exception.FailedFetchException;
import dev.jaoow.cotatrack.rest.exception.NotFoundException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class StockQueryService {

    public QueryResponse<Quote> fetchStockQuotes(String symbols,
                                                 LocalDate from,
                                                 LocalDate to,
                                                 String interval,
                                                 String range,
                                                 boolean includeDividends) {
        LocalDateTime startTime = LocalDateTime.now();

        QuoteQuery quoteQuery = buildStockQuoteQuery(symbols, from, to, interval, range, includeDividends);
        Map<String, Quote> quotesMap = fetchQuotes(quoteQuery);

        List<Quote> quotes = List.copyOf(quotesMap.values());
        long elapsedTime = calculateElapsedTime(startTime);

        return new QueryResponse<>(quotes, elapsedTime);
    }

    public QueryResponse<FxQuote> fetchFxQuotes(String symbols) {
        LocalDateTime startTime = LocalDateTime.now();

        QuoteQuery quoteQuery = buildFxQuoteQuery(symbols);
        Map<String, FxQuote> quotesMap = fetchFxQuotes(quoteQuery);

        List<FxQuote> quotes = List.copyOf(quotesMap.values());
        long elapsedTime = calculateElapsedTime(startTime);

        return new QueryResponse<>(quotes, elapsedTime);
    }

    private QuoteQuery buildStockQuoteQuery(String symbols,
                                            LocalDate from,
                                            LocalDate to,
                                            String interval,
                                            String range,
                                            boolean includeDividends) {
        QuoteQuery.QuoteQueryBuilder queryBuilder = QuoteQuery.builder()
                .symbols(symbols.split(","))
                .from(from)
                .to(to)
                .includeDividends(includeDividends);

        setInterval(queryBuilder, interval);
        setRange(queryBuilder, range);

        return queryBuilder.build();
    }

    private QuoteQuery buildFxQuoteQuery(String symbols) {
        return QuoteQuery.builder()
                .symbols(symbols.split(","))
                .build();
    }

    private void setInterval(QuoteQuery.QuoteQueryBuilder queryBuilder, String interval) {
        if (interval != null) {
            try {
                queryBuilder.interval(Interval.fromTag(interval));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Invalid interval: " + interval + ". Valid values are: " + Interval.getValidTags());
            }
        }
    }

    private void setRange(QuoteQuery.QuoteQueryBuilder queryBuilder, String range) {
        if (range != null) {
            try {
                queryBuilder.range(Range.fromTag(range));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Invalid range: " + range + ". Valid values are: " + Range.getValidTags());
            }
        }
    }

    private Map<String, Quote> fetchQuotes(QuoteQuery quoteQuery) {
        try {
            Map<String, Quote> quotesMap = quoteQuery.fetchMultiple();
            if (quotesMap.isEmpty()) {
                throw new NotFoundException("No quotes found for the given symbols.");
            }
            return quotesMap;
        } catch (IOException e) {
            throw new FailedFetchException("Failed to fetch quotes. Please try again later.");
        }
    }

    private Map<String, FxQuote> fetchFxQuotes(QuoteQuery quoteQuery) {
        try {
            Map<String, FxQuote> quotesMap = quoteQuery.fetchFxMultiple();
            if (quotesMap.isEmpty()) {
                throw new NotFoundException("No FX quotes found for the given symbols.");
            }
            return quotesMap;
        } catch (IOException e) {
            throw new FailedFetchException("Failed to fetch FX quotes. Please try again later.");
        }
    }

    private long calculateElapsedTime(LocalDateTime startTime) {
        return Duration.between(startTime, LocalDateTime.now()).toMillis();
    }
}
