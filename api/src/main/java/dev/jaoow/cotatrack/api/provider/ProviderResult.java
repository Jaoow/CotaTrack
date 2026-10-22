package dev.jaoow.cotatrack.api.provider;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;
import java.util.Map;

/**
 * Result wrapper containing data and metadata about which provider served the response.
 *
 * @param <T> payload type
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderResult<T> {

    private T data;
    private String providerName;
    private boolean fromCache;
    private boolean stale;
    private long executionTimeMs;

    private ProviderException error;
    private Map<String, ProviderResult<Quote>> items;

    public ProviderResult(T data, String providerName, boolean fromCache, boolean stale, long executionTimeMs) {
        this(data, providerName, fromCache, stale, executionTimeMs, null, null);
    }

    public static <T> ProviderResult<T> of(T data, String providerName, long executionTimeMs) {
        return ProviderResult.<T>builder()
                .data(data)
                .providerName(providerName)
                .fromCache(false)
                .stale(false)
                .executionTimeMs(executionTimeMs)
                .build();
    }

    public static <T> ProviderResult<T> fromCache(T data, String providerName, boolean stale, long executionTimeMs) {
        return ProviderResult.<T>builder()
                .data(data)
                .providerName(providerName)
                .fromCache(true)
                .stale(stale)
                .executionTimeMs(executionTimeMs)
                .build();
    }
}
