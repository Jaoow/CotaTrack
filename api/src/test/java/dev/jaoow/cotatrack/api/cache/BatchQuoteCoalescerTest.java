package dev.jaoow.cotatrack.api.cache;

import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BatchQuoteCoalescerTest {

    @Test
    @DisplayName("Should coalesce 10 concurrent threads requesting the same symbol into a single provider call")
    void testSingleFlightIdenticalSymbol() throws Exception {
        BatchQuoteCoalescer coalescer = new BatchQuoteCoalescer();
        AtomicInteger providerCallCount = new AtomicInteger(0);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<Map<String, Quote>>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await(); // ensure all threads fire at the exact same instant

                return coalescer.coalesceAndFetch(List.of("PETR4"), batch -> {
                    providerCallCount.incrementAndGet();
                    try {
                        // Artificial delay simulating provider HTTP roundtrip
                        Thread.sleep(80);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    Quote q = new Quote();
                    q.setSymbol("PETR4.SA");
                    q.setRegularMarketPrice(38.25);
                    return Map.of("PETR4", q);
                });
            }));
        }

        readyLatch.await();
        startLatch.countDown(); // fire all threads

        for (Future<Map<String, Quote>> f : futures) {
            Map<String, Quote> result = f.get(5, TimeUnit.SECONDS);
            assertNotNull(result);
            assertTrue(result.containsKey("PETR4"));
            assertEquals(38.25, result.get("PETR4").getRegularMarketPrice());
        }

        executor.shutdown();

        // Must have called provider exactly ONCE!
        assertEquals(1, providerCallCount.get(), "Provider must be called exactly once despite 10 concurrent requests");
        assertEquals(0, coalescer.getInFlightCount(), "In-flight map must be clean after all requests complete");
    }

    @Test
    @DisplayName("Should handle overlapping concurrent batches without duplicate fetches for common symbol")
    void testConcurrentOverlappingBatches() throws Exception {
        BatchQuoteCoalescer coalescer = new BatchQuoteCoalescer();
        List<String> fetchedSymbolsRecord = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        // Thread 1: requests PETR4 and VALE3
        Future<Map<String, Quote>> future1 = executor.submit(() -> {
            startLatch.await();
            return coalescer.coalesceAndFetch(List.of("PETR4", "VALE3"), batch -> {
                fetchedSymbolsRecord.addAll(batch);
                try {
                    Thread.sleep(60);
                } catch (InterruptedException ignored) {}
                Map<String, Quote> res = new HashMap<>();
                for (String sym : batch) {
                    Quote q = new Quote();
                    q.setSymbol(sym);
                    res.put(sym, q);
                }
                return res;
            });
        });

        // Thread 2: requests VALE3 and ITUB4
        Future<Map<String, Quote>> future2 = executor.submit(() -> {
            startLatch.await();
            return coalescer.coalesceAndFetch(List.of("VALE3", "ITUB4"), batch -> {
                fetchedSymbolsRecord.addAll(batch);
                try {
                    Thread.sleep(60);
                } catch (InterruptedException ignored) {}
                Map<String, Quote> res = new HashMap<>();
                for (String sym : batch) {
                    Quote q = new Quote();
                    q.setSymbol(sym);
                    res.put(sym, q);
                }
                return res;
            });
        });

        startLatch.countDown();

        Map<String, Quote> res1 = future1.get(5, TimeUnit.SECONDS);
        Map<String, Quote> res2 = future2.get(5, TimeUnit.SECONDS);

        executor.shutdown();

        // Check that res1 has PETR4 and VALE3
        assertTrue(res1.containsKey("PETR4"));
        assertTrue(res1.containsKey("VALE3"));

        // Check that res2 has VALE3 and ITUB4
        assertTrue(res2.containsKey("VALE3"));
        assertTrue(res2.containsKey("ITUB4"));

        // Count how many times VALE3 was passed to the batch supplier
        long valeCount = fetchedSymbolsRecord.stream().filter(s -> s.equalsIgnoreCase("VALE3")).count();
        assertEquals(1, valeCount, "VALE3 should only be fetched once across overlapping concurrent batches");
    }

    @Test
    @DisplayName("Should handle supplier exception gracefully without deadlocks")
    void testSupplierExceptionHandling() {
        BatchQuoteCoalescer coalescer = new BatchQuoteCoalescer();

        Map<String, Quote> result = coalescer.coalesceAndFetch(List.of("FAIL_SYM"), batch -> {
            throw new RuntimeException("Simulated provider network failure");
        });

        assertNotNull(result);
        assertTrue(result.isEmpty());
        assertEquals(0, coalescer.getInFlightCount(), "In-flight map must be cleaned up even on failure");
    }
}
