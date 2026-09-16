package restudio.resync.migration;

import java.util.Optional;

public record JournalRecovery(Optional<MigrationJournalState> state, JournalRecoveryAction action) {
    public JournalRecovery {
        state = state == null ? Optional.empty() : state;
        action = java.util.Objects.requireNonNull(action, "action");
    }
}
