package dev.jaoow.cotatrack.api.requests.quotes.model;

public record StockQueryError(String symbol, String operation, int status, String message) {
}
