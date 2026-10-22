package dev.jaoow.cotatrack.api.requests.quotes.model;

public record StockInstrument(String symbol, String name, String currency, String exchange,
                              String micCode, String type) {
}
