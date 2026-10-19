package dev.jaoow.cotatrack.api.provider.forex;

import java.util.*;

/**
 * Maps and validates foreign exchange symbols supported by Banco Central do Brasil (BCB PTAX).
 * Supported currencies are the 10 official PTAX currencies traded against BRL.
 */
public class BcbCurrencyMapper {

    public record CurrencyInfo(String code, String name, String yahooTicker) {}

    private static final Map<String, CurrencyInfo> SUPPORTED_CURRENCIES = new LinkedHashMap<>();

    static {
        SUPPORTED_CURRENCIES.put("USD", new CurrencyInfo("USD", "Dólar dos Estados Unidos", "USDBRL=X"));
        SUPPORTED_CURRENCIES.put("EUR", new CurrencyInfo("EUR", "Euro", "EURBRL=X"));
        SUPPORTED_CURRENCIES.put("GBP", new CurrencyInfo("GBP", "Libra Esterlina", "GBPBRL=X"));
        SUPPORTED_CURRENCIES.put("CAD", new CurrencyInfo("CAD", "Dólar Canadense", "CADBRL=X"));
        SUPPORTED_CURRENCIES.put("AUD", new CurrencyInfo("AUD", "Dólar Australiano", "AUDBRL=X"));
        SUPPORTED_CURRENCIES.put("CHF", new CurrencyInfo("CHF", "Franco Suíço", "CHFBRL=X"));
        SUPPORTED_CURRENCIES.put("JPY", new CurrencyInfo("JPY", "Iene Japonês", "JPYBRL=X"));
        SUPPORTED_CURRENCIES.put("DKK", new CurrencyInfo("DKK", "Coroa Dinamarquesa", "DKKBRL=X"));
        SUPPORTED_CURRENCIES.put("NOK", new CurrencyInfo("NOK", "Coroa Norueguesa", "NOKBRL=X"));
        SUPPORTED_CURRENCIES.put("SEK", new CurrencyInfo("SEK", "Coroa Sueca", "SEKBRL=X"));
    }

    /**
     * Resolves an incoming symbol (e.g., "USD", "USDBRL", "USD-BRL", "USDBRL=X", "BRL=X")
     * to its official BCB currency metadata.
     *
     * @param rawSymbol the input symbol from the client
     * @return Optional containing CurrencyInfo if supported, or empty if unsupported
     */
    public static Optional<CurrencyInfo> mapSymbol(String rawSymbol) {
        if (rawSymbol == null || rawSymbol.isBlank()) {
            return Optional.empty();
        }

        String clean = rawSymbol.trim().toUpperCase()
                .replace("=X", "")
                .replace("-", "")
                .replace("/", "")
                .trim();

        // Shorthand BRL=X in Yahoo denotes USD/BRL
        if ("BRL".equals(clean)) {
            return Optional.of(SUPPORTED_CURRENCIES.get("USD"));
        }

        // e.g. "USDBRL" -> "USD"
        if (clean.endsWith("BRL") && clean.length() > 3) {
            clean = clean.substring(0, clean.length() - 3);
        }

        // Direct 3-letter currency code match
        if (SUPPORTED_CURRENCIES.containsKey(clean)) {
            return Optional.of(SUPPORTED_CURRENCIES.get(clean));
        }

        return Optional.empty();
    }

    /**
     * Validates and maps an incoming symbol, throwing {@link IllegalArgumentException} with a clear error
     * message if the currency is not supported by BCB PTAX.
     */
    public static CurrencyInfo validateAndMap(String rawSymbol) {
        return mapSymbol(rawSymbol).orElseThrow(() -> new IllegalArgumentException(
                "Moeda ou par não suportado: '" + rawSymbol + "'. " +
                "Pares suportados pelo Banco Central (PTAX): " + String.join(", ", SUPPORTED_CURRENCIES.keySet()) +
                " (ex: USD, USDBRL, EUR, EURBRL, GBP, JPY)."
        ));
    }

    public static Set<String> getSupportedCurrencies() {
        return Collections.unmodifiableSet(SUPPORTED_CURRENCIES.keySet());
    }
}
