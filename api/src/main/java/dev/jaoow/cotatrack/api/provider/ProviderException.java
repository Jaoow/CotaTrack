package dev.jaoow.cotatrack.api.provider;

/**
 * Exception thrown when a market data provider fails to respond or returns an error.
 */
public class ProviderException extends RuntimeException {

    private final String providerName;
    private final int statusCode;

    public ProviderException(String providerName, String message) {
        super("[" + providerName + "] " + message);
        this.providerName = providerName;
        this.statusCode = 0;
    }

    public ProviderException(String providerName, int statusCode, String message) {
        super("[" + providerName + "] HTTP " + statusCode + ": " + message);
        this.providerName = providerName;
        this.statusCode = statusCode;
    }

    public ProviderException(String providerName, String message, Throwable cause) {
        super("[" + providerName + "] " + message, cause);
        this.providerName = providerName;
        this.statusCode = 0;
    }

    public String getProviderName() {
        return providerName;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
