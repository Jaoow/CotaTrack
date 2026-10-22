package dev.jaoow.cotatrack.api.cache;

import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;
import dev.jaoow.cotatrack.api.provider.ProviderResult;
import dev.jaoow.cotatrack.api.provider.ProviderException;
import dev.jaoow.cotatrack.api.classification.AssetMarket;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * SingleFlight coalescer for batch stock quote requests.
 * Prevents duplicate in-flight requests to market data providers when multiple
 * concurrent threads query the same symbols simultaneously.
 */
@Slf4j
public class BatchQuoteCoalescer {

    private static final long DEFAULT_TIMEOUT_SECONDS = 10;
    private final ConcurrentMap<String, CompletableFuture<Quote>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<ProviderResult<Quote>>> marketInFlight = new ConcurrentHashMap<>();

    public ProviderResult<Quote> coalesce(AssetMarket market, String symbol,
                                          Supplier<ProviderResult<Quote>> fetcher) {
        String key = market.cacheKey(symbol);
        CompletableFuture<ProviderResult<Quote>> created = new CompletableFuture<>();
        CompletableFuture<ProviderResult<Quote>> existing = marketInFlight.putIfAbsent(key, created);
        if (existing == null) {
            try {
                ProviderResult<Quote> result = fetcher.get();
                created.complete(result);
                return result;
            } catch (RuntimeException e) {
                created.completeExceptionally(e);
                throw e;
            } finally {
                marketInFlight.remove(key, created);
            }
        }
        try {
            return existing.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("US", "Interrupted while awaiting quote", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new ProviderException("US", "Failed while awaiting quote", e);
        }
    }

    /**
     * Coalesces concurrent requests for symbols.
     * Symbols already being fetched by another thread are awaited; symbols not yet
     * in-flight are passed to {@code batchFetcher} in a single batch call.
     *
     * @param uncachedSymbols the list of symbols not present in cache
     * @param batchFetcher    function to fetch a batch of symbols from the provider
     * @return map of symbol to Quote for all requested symbols
     */
    public Map<String, Quote> coalesceAndFetch(List<String> uncachedSymbols,
                                              Function<List<String>, Map<String, Quote>> batchFetcher) {
        if (uncachedSymbols == null || uncachedSymbols.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, CompletableFuture<Quote>> myCreatedFutures = new LinkedHashMap<>();
        Map<String, CompletableFuture<Quote>> allFuturesToAwait = new LinkedHashMap<>();

        for (String rawSymbol : uncachedSymbols) {
            String norm = normalize(rawSymbol);
            CompletableFuture<Quote> newFuture = new CompletableFuture<>();
            CompletableFuture<Quote> existing = inFlight.putIfAbsent(norm, newFuture);

            if (existing != null) {
                log.debug("SingleFlight: Symbol '{}' already in flight, joining existing request", norm);
                allFuturesToAwait.put(rawSymbol, existing);
            } else {
                myCreatedFutures.put(norm, newFuture);
                allFuturesToAwait.put(rawSymbol, newFuture);
            }
        }

        // Execute batch fetch for all symbols this thread is responsible for
        if (!myCreatedFutures.isEmpty()) {
            List<String> toFetch = new ArrayList<>(myCreatedFutures.keySet());
            try {
                log.debug("SingleFlight: Executing batch fetch for {} symbols: {}", toFetch.size(), toFetch);
                Map<String, Quote> fetched = batchFetcher.apply(toFetch);

                for (Map.Entry<String, CompletableFuture<Quote>> entry : myCreatedFutures.entrySet()) {
                    String normSym = entry.getKey();
                    Quote q = findQuote(fetched, normSym);
                    entry.getValue().complete(q);
                }
            } catch (Throwable t) {
                log.error("SingleFlight: Batch fetcher failed for symbols {}", toFetch, t);
                for (CompletableFuture<Quote> f : myCreatedFutures.values()) {
                    f.completeExceptionally(t);
                }
            } finally {
                // Remove my futures from in-flight map
                for (Map.Entry<String, CompletableFuture<Quote>> entry : myCreatedFutures.entrySet()) {
                    inFlight.remove(entry.getKey(), entry.getValue());
                }
            }
        }

        // Collect results for all requested symbols
        Map<String, Quote> result = new LinkedHashMap<>();
        for (Map.Entry<String, CompletableFuture<Quote>> entry : allFuturesToAwait.entrySet()) {
            String reqSym = entry.getKey();
            try {
                Quote q = entry.getValue().get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (q != null) {
                    result.put(reqSym, q);
                }
            } catch (Exception e) {
                log.warn("SingleFlight: Failed or timed out awaiting quote for symbol '{}': {}", reqSym, e.getMessage());
            }
        }

        return result;
    }

    public int getInFlightCount() {
        return inFlight.size() + marketInFlight.size();
    }

    private Quote findQuote(Map<String, Quote> map, String normSymbol) {
        if (map == null || map.isEmpty()) return null;
        if (map.containsKey(normSymbol)) return map.get(normSymbol);
        if (map.containsKey(normSymbol + ".SA")) return map.get(normSymbol + ".SA");
        String clean = normSymbol.replace(".SA", "");
        if (map.containsKey(clean)) return map.get(clean);
        return null;
    }

    private String normalize(String symbol) {
        if (symbol == null) return "";
        return symbol.trim().toUpperCase().replace(".SA", "");
    }
}
