package dev.jaoow.cotatrack.api.cache;

import dev.jaoow.cotatrack.api.requests.dividends.model.DividendsData;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.HistoricalQuote;
import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CacheManagerTest {

    @Test
    @DisplayName("Should correctly parse duration strings across various units")
    void testDurationParsing() {
        assertEquals(Duration.ofMinutes(5), CacheConfig.parseDuration("5m", Duration.ZERO));
        assertEquals(Duration.ofMinutes(3), CacheConfig.parseDuration("3m", Duration.ZERO));
        assertEquals(Duration.ofSeconds(300), CacheConfig.parseDuration("300s", Duration.ZERO));
        assertEquals(Duration.ofHours(12), CacheConfig.parseDuration("12h", Duration.ZERO));
        assertEquals(Duration.ofHours(24), CacheConfig.parseDuration("24h", Duration.ZERO));
        assertEquals(Duration.ofDays(7), CacheConfig.parseDuration("7d", Duration.ZERO));
        assertEquals(Duration.ofMillis(50), CacheConfig.parseDuration("50ms", Duration.ZERO));

        // Invalid and fallback checks
        assertEquals(Duration.ofMinutes(5), CacheConfig.parseDuration("invalid_duration", Duration.ofMinutes(5)));
        assertEquals(Duration.ofMinutes(5), CacheConfig.parseDuration(null, Duration.ofMinutes(5)));
        assertEquals(Duration.ofMinutes(5), CacheConfig.parseDuration("   ", Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("Should put and retrieve intraday quote with and without .SA suffix")
    void testIntradayQuoteCaching() {
        CacheConfig config = CacheConfig.builder()
                .quoteIntradayTtl(Duration.ofMinutes(5))
                .staleTtl(Duration.ofHours(24))
                .build();
        CotaTrackCacheManager manager = new CotaTrackCacheManager(config);

        Quote quote = new Quote();
        quote.setSymbol("PETR4.SA");
        quote.setRegularMarketPrice(38.50);

        manager.putIntradayQuote("PETR4", quote);

        // Retrieve using both formats
        Quote byTicker = manager.getIntradayQuote("PETR4");
        Quote bySuffix = manager.getIntradayQuote("PETR4.SA");
        Quote byLower = manager.getIntradayQuote("petr4");

        assertNotNull(byTicker);
        assertNotNull(bySuffix);
        assertNotNull(byLower);
        assertEquals(38.50, byTicker.getRegularMarketPrice());
        assertEquals(38.50, bySuffix.getRegularMarketPrice());

        // Batch retrieval
        Map<String, Quote> map = manager.getIntradayQuotes(List.of("PETR4", "VALE3"));
        assertEquals(1, map.size());
        assertTrue(map.containsKey("PETR4"));
    }

    @Test
    @DisplayName("Should expire intraday quote after TTL while retaining stale disaster cache")
    void testTtlExpirationAndStaleRetention() throws InterruptedException {
        // Short intraday TTL: 50ms, longer stale TTL: 1000ms
        CacheConfig config = CacheConfig.builder()
                .quoteIntradayTtl(Duration.ofMillis(50))
                .staleTtl(Duration.ofMillis(1000))
                .build();
        CotaTrackCacheManager manager = new CotaTrackCacheManager(config);

        Quote quote = new Quote();
        quote.setSymbol("VALE3.SA");
        quote.setRegularMarketPrice(65.0);

        manager.putIntradayQuote("VALE3", quote);

        // Immediately available
        assertNotNull(manager.getIntradayQuote("VALE3"));
        assertNotNull(manager.getStaleQuote("VALE3"));

        // Wait for intraday cache to expire
        Thread.sleep(80);

        // Intraday must have expired
        assertNull(manager.getIntradayQuote("VALE3"));

        // Stale cache must still be valid for disaster recovery
        Quote stale = manager.getStaleQuote("VALE3");
        assertNotNull(stale);
        assertEquals(65.0, stale.getRegularMarketPrice());
    }

    @Test
    @DisplayName("Should store and retrieve historical quotes, dividends, and logos")
    void testHistoricalDividendsAndLogoCaching() {
        CacheConfig config = CacheConfig.builder()
                .historyDailyTtl(Duration.ofHours(12))
                .dividendsTtl(Duration.ofHours(24))
                .logoTtl(Duration.ofDays(7))
                .build();
        CotaTrackCacheManager manager = new CotaTrackCacheManager(config);

        // Historical
        String histKey = "PETR4:2026-01-01:2026-06-01:1d:null";
        HistoricalQuote hq = new HistoricalQuote(1700000000L, BigDecimal.valueOf(35), BigDecimal.valueOf(34),
                BigDecimal.valueOf(36), BigDecimal.valueOf(35.5), BigDecimal.valueOf(35.5), 10000L);
        manager.putHistorical(histKey, List.of(hq));

        List<HistoricalQuote> cachedHist = manager.getHistorical(histKey);
        assertNotNull(cachedHist);
        assertEquals(1, cachedHist.size());
        assertEquals(1700000000L, cachedHist.get(0).getDate());

        // Dividends
        DividendsData div = new DividendsData();
        manager.putDividends("PETR4", div);
        assertNotNull(manager.getDividends("PETR4"));
        assertNotNull(manager.getDividends("PETR4.SA"));

        // Logo
        manager.putLogo("BMFBOVESPA:PETR4", "petrobras-logo-id");
        assertEquals("petrobras-logo-id", manager.getLogo("BMFBOVESPA:PETR4"));
    }
}
