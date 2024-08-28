package dev.jaoow.cotatrack.api.tradingview;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import dev.jaoow.cotatrack.api.tradingview.field.FieldFetcher;
import dev.jaoow.cotatrack.api.tradingview.field.StockField;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LogoIdFetcher {

    private static final LoadingCache<String, String> cache = Caffeine.newBuilder()
            .maximumSize(1000)
            .build(LogoIdFetcher::fetchLogoIdFromAPI);

    /**
     * Fetches the logo id for the given exchange and symbol.
     *
     * @param exchangeKey the exchange key
     * @param symbol      the symbol
     * @return the logo id
     */
    public static String fetchLogoId(String exchangeKey, String symbol) {
        String cacheKey = exchangeKey + ":" + symbol;
        return cache.get(cacheKey);
    }

    private static String fetchLogoIdFromAPI(String cacheKey) {
        String[] parts = cacheKey.split(":");
        String exchangeKey = parts[0];
        String symbol = parts[1];

        StockExchange stockExchange;
        try {
            stockExchange = StockExchange.valueOf(exchangeKey);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid exchange key: {}", exchangeKey);
            return null;
        }

        return FieldFetcher.fetchField(stockExchange.getExchangeCode(), symbol, StockField.LOGOID);
    }
}
