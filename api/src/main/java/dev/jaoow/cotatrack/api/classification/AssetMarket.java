package dev.jaoow.cotatrack.api.classification;

import java.util.Locale;

public enum AssetMarket {
    B3, US;

    public String normalize(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Symbol is required.");
        }
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        if (this == B3) {
            return normalized.replace("BMFBOVESPA:", "").replace(".SA", "");
        }
        if (!normalized.matches("[A-Z][A-Z0-9]{0,9}([.-][A-Z0-9]{1,4})?")
                || normalized.endsWith(".SA")) {
            throw new IllegalArgumentException("Invalid US stock symbol: " + normalized);
        }
        return normalized;
    }

    public String cacheKey(String symbol) {
        return name() + ":" + normalize(symbol);
    }
}
