package dev.jaoow.cotatrack.rest.controller;

import dev.jaoow.cotatrack.api.cache.CotaTrackCacheManager;
import dev.jaoow.cotatrack.api.provider.orchestrator.ProviderOrchestrator;
import dev.jaoow.cotatrack.rest.dto.DiagnosticsResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticsControllerTest {

    @Test
    @DisplayName("Should return diagnostics report with all providers, circuit breakers, and cache sizes without secrets")
    void testDiagnosticsReport() {
        ProviderOrchestrator orchestrator = new ProviderOrchestrator();
        CotaTrackCacheManager cacheManager = CotaTrackCacheManager.getInstance();

        DiagnosticsController controller = new DiagnosticsController(orchestrator, cacheManager);

        DiagnosticsResponse response = controller.getDiagnostics();

        assertNotNull(response);
        assertEquals("UP", response.getStatus());
        assertNotNull(response.getTimestamp());
        assertNotNull(response.getProviders());
        assertFalse(response.getProviders().isEmpty());

        // Check providers list contains stock and forex providers
        List<String> names = response.getProviders().stream().map(DiagnosticsResponse.ProviderHealthDto::getName).toList();
        assertTrue(names.contains("TRADING_VIEW"));
        assertTrue(names.contains("YAHOO_CHART"));
        assertTrue(names.contains("BRAPI"));
        assertTrue(names.contains("BCB_PTAX"));
        assertTrue(names.contains("YAHOO"));

        // Check cache stats
        assertNotNull(response.getCacheStats());
        assertTrue(response.getCacheStats().containsKey("intradayQuotes"));
        assertTrue(response.getCacheStats().containsKey("forexQuotes"));
        assertTrue(response.getCacheStats().containsKey("staleDisasterQuotes"));

        // Verify zero secrets leaked in failure reasons
        for (DiagnosticsResponse.ProviderHealthDto p : response.getProviders()) {
            if (p.getLastFailureReason() != null) {
                assertFalse(p.getLastFailureReason().contains("token="));
                assertFalse(p.getLastFailureReason().contains("cookie="));
                assertFalse(p.getLastFailureReason().contains("crumb="));
            }
        }
    }
}
