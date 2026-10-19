package dev.jaoow.cotatrack.api.provider.forex;

/**
 * Holder for the global {@link FxOrchestrator} instance.
 */
public final class FxOrchestratorHolder {

    private static volatile FxOrchestrator orchestrator;

    private FxOrchestratorHolder() {}

    public static FxOrchestrator getOrchestrator() {
        if (orchestrator == null) {
            synchronized (FxOrchestratorHolder.class) {
                if (orchestrator == null) {
                    orchestrator = new FxOrchestrator();
                }
            }
        }
        return orchestrator;
    }

    public static synchronized void setOrchestrator(FxOrchestrator customOrchestrator) {
        orchestrator = customOrchestrator;
    }
}
