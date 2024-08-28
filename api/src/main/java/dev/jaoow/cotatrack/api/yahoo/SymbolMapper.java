package dev.jaoow.cotatrack.api.yahoo;

import lombok.experimental.UtilityClass;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * This is a workaround for the Yahoo Finance API, which requires some symbols to have the market suffix.
 * <p>
 * Actually, this will work just for Brazilian stocks (since only them ends with a number).
 */
@UtilityClass
public class SymbolMapper {

    private static final Pattern ENDS_WITH_NUMBER = Pattern.compile(".*\\d$");

    public static String[] mapSymbols(String[] symbols) {
        return Arrays.stream(symbols)
                .map(SymbolMapper::mapSymbol)
                .toArray(String[]::new);
    }

    public static String mapSymbol(String symbol) {
        return ENDS_WITH_NUMBER.matcher(symbol).matches() ? symbol + ".SA" : symbol;
    }
}
