package dev.jaoow.cotatrack.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Diagnostics response DTO reporting per-provider health status, last success/failure,
 * resilience circuit breaker states, and cache statistics without exposing credentials or tokens.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DiagnosticsResponse {

    private String status;
    private Instant timestamp;
    private List<ProviderHealthDto> providers;
    private Map<String, Long> cacheStats;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ProviderHealthDto {
        private String name;
        private String market;
        private String status;
        private boolean enabled;
        private String circuitBreakerState;
        private Instant lastSuccess;
        private Instant lastFailure;
        private String lastFailureReason;
        private long totalRequests;
        private long successfulRequests;
        private long failedRequests;
    }
}
