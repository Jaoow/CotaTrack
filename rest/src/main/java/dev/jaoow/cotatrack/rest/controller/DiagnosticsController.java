package dev.jaoow.cotatrack.rest.controller;

import dev.jaoow.cotatrack.api.cache.CotaTrackCacheManager;
import dev.jaoow.cotatrack.api.provider.StockQuoteProvider;
import dev.jaoow.cotatrack.api.provider.forex.BcbPtaxFxProvider;
import dev.jaoow.cotatrack.api.provider.forex.YahooFxProvider;
import dev.jaoow.cotatrack.api.provider.health.ProviderHealthTracker;
import dev.jaoow.cotatrack.api.provider.orchestrator.ProviderOrchestrator;
import dev.jaoow.cotatrack.rest.dto.DiagnosticsResponse;
import dev.jaoow.cotatrack.rest.dto.DiagnosticsResponse.ProviderHealthDto;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.*;

/**
 * Health check and diagnostics controller.
 * Exposes per-provider health metrics, last success/failure timestamps, circuit breaker state,
 * and cache sizes without exposing credentials, secrets, or API keys.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DiagnosticsController {

    private final ProviderOrchestrator providerOrchestrator;
    private final CotaTrackCacheManager cacheManager;

    @GetMapping({"/health", "/diagnostics"})
    public DiagnosticsResponse getDiagnostics() {
        ProviderHealthTracker tracker = ProviderHealthTracker.getInstance();
        Map<String, CircuitBreaker> cbs = providerOrchestrator.getCircuitBreakers();

        List<ProviderHealthDto> providerDtos = new ArrayList<>();
        boolean anyHealthy = false;

        // 1. Stock Market Providers
        for (StockQuoteProvider provider : providerOrchestrator.getProviders()) {
            String name = provider.getName();
            ProviderHealthTracker.ProviderMetrics m = tracker.getOrCreate(name);

            CircuitBreaker cb = cbs.get(name);
            String cbState = cb != null ? cb.getState().name() : "N/A";

            String status = "HEALTHY";
            if (!provider.isEnabled()) {
                status = "DISABLED";
            } else if ("OPEN".equalsIgnoreCase(cbState)) {
                status = "CIRCUIT_OPEN";
            } else if (m.getFailedRequests().get() > 0 &&
                    (m.getLastSuccess() == null || (m.getLastFailure() != null && m.getLastFailure().isAfter(m.getLastSuccess())))) {
                status = "DEGRADED";
            }

            if ("HEALTHY".equals(status)) {
                anyHealthy = true;
            }

            providerDtos.add(ProviderHealthDto.builder()
                    .name(name)
                    .status(status)
                    .enabled(provider.isEnabled())
                    .circuitBreakerState(cbState)
                    .lastSuccess(m.getLastSuccess())
                    .lastFailure(m.getLastFailure())
                    .lastFailureReason(m.getLastFailureReason())
                    .totalRequests(m.getTotalRequests().get())
                    .successfulRequests(m.getSuccessfulRequests().get())
                    .failedRequests(m.getFailedRequests().get())
                    .build());
        }

        // 2. Forex Providers (BCB PTAX & Yahoo FX)
        List<String> forexProviders = List.of(BcbPtaxFxProvider.PROVIDER_NAME, YahooFxProvider.PROVIDER_NAME);
        for (String fxName : forexProviders) {
            ProviderHealthTracker.ProviderMetrics m = tracker.getOrCreate(fxName);
            String status = "HEALTHY";
            if (m.getFailedRequests().get() > 0 &&
                    (m.getLastSuccess() == null || (m.getLastFailure() != null && m.getLastFailure().isAfter(m.getLastSuccess())))) {
                status = "DEGRADED";
            }

            providerDtos.add(ProviderHealthDto.builder()
                    .name(fxName)
                    .status(status)
                    .enabled(true)
                    .circuitBreakerState("N/A")
                    .lastSuccess(m.getLastSuccess())
                    .lastFailure(m.getLastFailure())
                    .lastFailureReason(m.getLastFailureReason())
                    .totalRequests(m.getTotalRequests().get())
                    .successfulRequests(m.getSuccessfulRequests().get())
                    .failedRequests(m.getFailedRequests().get())
                    .build());
        }

        // 3. Cache Statistics
        Map<String, Long> cacheStats = new LinkedHashMap<>();
        cacheStats.put("intradayQuotes", cacheManager.getIntradayQuoteCache().estimatedSize());
        cacheStats.put("historicalQuotes", cacheManager.getHistoricalCache().estimatedSize());
        cacheStats.put("dividends", cacheManager.getDividendsCache().estimatedSize());
        cacheStats.put("logos", cacheManager.getLogoCache().estimatedSize());
        cacheStats.put("forexQuotes", cacheManager.getFxQuoteCache().estimatedSize());
        cacheStats.put("staleDisasterQuotes", cacheManager.getStaleQuoteCache().estimatedSize());

        String overallStatus = anyHealthy ? "UP" : "DEGRADED";

        return DiagnosticsResponse.builder()
                .status(overallStatus)
                .timestamp(Instant.now())
                .providers(providerDtos)
                .cacheStats(cacheStats)
                .build();
    }
}
