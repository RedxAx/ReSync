package restudio.resync.migration;

import java.util.concurrent.CompletionStage;

public interface EphemeralLifecycleParticipant {
    String owner();

    CompletionStage<Void> prepareSnapshot();

    CompletionStage<Void> prepareRestore();

    void resumeAfterSnapshot();

    void resumeAfterRestore();

    Health lifecycleHealth();

    default void healthCheck() {
        Health current = lifecycleHealth();
        if (current == null || !current.available()) {
            throw new IllegalStateException(current == null ? "Ephemeral Lifecycle Health Is Unavailable" : current.reason());
        }
    }

    record Health(boolean available, String state, int physicalTasks, String reason) {
        public Health {
            state = state == null ? "UNKNOWN" : state;
            reason = reason == null ? "" : reason;
        }
    }
}
