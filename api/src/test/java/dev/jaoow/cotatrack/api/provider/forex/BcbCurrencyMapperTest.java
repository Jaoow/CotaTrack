package dev.jaoow.cotatrack.api.provider.forex;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BcbCurrencyMapperTest {

    @ParameterizedTest
    @ValueSource(strings = {"USD", "USDBRL", "USD-BRL", "USDBRL=X", "BRL=X", "usd", "usdbrl"})
    @DisplayName("Should normalize various USD alias formats to USD")
    void testUsdNormalization(String symbol) {
        Optional<BcbCurrencyMapper.CurrencyInfo> info = BcbCurrencyMapper.mapSymbol(symbol);
        assertTrue(info.isPresent());
        assertEquals("USD", info.get().code());
        assertEquals("USDBRL=X", info.get().yahooTicker());
        assertEquals("Dólar dos Estados Unidos", info.get().name());
    }

    @ParameterizedTest
    @ValueSource(strings = {"EUR", "EURBRL", "EUR-BRL", "EURBRL=X", "eur", "eurbrl"})
    @DisplayName("Should normalize various EUR alias formats to EUR")
    void testEurNormalization(String symbol) {
        Optional<BcbCurrencyMapper.CurrencyInfo> info = BcbCurrencyMapper.mapSymbol(symbol);
        assertTrue(info.isPresent());
        assertEquals("EUR", info.get().code());
        assertEquals("EURBRL=X", info.get().yahooTicker());
        assertEquals("Euro", info.get().name());
    }

    @Test
    @DisplayName("Should map all 10 official BCB PTAX supported currencies")
    void testAllTenCurrenciesSupported() {
        String[] supported = {"USD", "EUR", "GBP", "CAD", "AUD", "CHF", "JPY", "DKK", "NOK", "SEK"};
        for (String code : supported) {
            Optional<BcbCurrencyMapper.CurrencyInfo> info = BcbCurrencyMapper.mapSymbol(code);
            assertTrue(info.isPresent(), "Currency " + code + " must be mapped");
            assertEquals(code, info.get().code());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"BTC", "ETH", "CNY", "INVALID", "XYZ", "123"})
    @DisplayName("Should reject unsupported symbols with clear error message")
    void testUnsupportedCurrenciesRejected(String unsupportedSymbol) {
        Optional<BcbCurrencyMapper.CurrencyInfo> opt = BcbCurrencyMapper.mapSymbol(unsupportedSymbol);
        assertTrue(opt.isEmpty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                BcbCurrencyMapper.validateAndMap(unsupportedSymbol)
        );

        assertTrue(ex.getMessage().contains("Moeda ou par não suportado: '" + unsupportedSymbol + "'"));
        assertTrue(ex.getMessage().contains("USD, EUR, GBP, CAD, AUD, CHF, JPY, DKK, NOK, SEK"));
    }
}
