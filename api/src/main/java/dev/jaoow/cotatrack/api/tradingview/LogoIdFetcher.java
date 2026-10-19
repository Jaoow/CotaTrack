package dev.jaoow.cotatrack.api.tradingview;

import dev.jaoow.cotatrack.api.cache.CotaTrackCacheManager;
import dev.jaoow.cotatrack.api.tradingview.field.FieldFetcher;
import dev.jaoow.cotatrack.api.tradingview.field.StockField;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LogoIdFetcher {

    /**
     * Fetches the logo id for the given exchange and symbol.
     *
     * @param exchangeKey the exchange key
     * @param symbol      the symbol
     * @return the logo id
     */
    public static String fetchLogoId(String exchangeKey, String symbol) {
        if (exchangeKey == null || symbol == null)
            return null;
        String cleanSymbol = symbol.trim().toUpperCase().replace(".SA", "");
        String cacheKey = exchangeKey.trim().toUpperCase() + ":" + cleanSymbol;
        try {
            return CotaTrackCacheManager.getInstance()
                    .getLogoCache()
                    .get(cacheKey, LogoIdFetcher::fetchLogoIdFromAPI);
        } catch (Exception e) {
            log.warn("Failed to fetch logo id for {}", cacheKey, e);
            return null;
        }
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
