package dev.jaoow.cotatrack.api.provider.orchestrator;

/**
 * Holder for the default or configured ProviderOrchestrator singleton.
 */
public class ProviderOrchestratorHolder {

    private static volatile ProviderOrchestrator orchestrator = new ProviderOrchestrator();

    public static ProviderOrchestrator getOrchestrator() {
        return orchestrator;
    }

    public static void setOrchestrator(ProviderOrchestrator customOrchestrator) {
        orchestrator = customOrchestrator;
    }
}
