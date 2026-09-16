package restudio.resync.flow.runtime;

public enum RuntimeProviderState {
    STAGED,
    ACTIVE,
    DRAINING,
    REVOKED,
    REMOVED,
    FAILED;

    public String wireValue() {
        return name().toLowerCase();
    }

    public boolean canTransitionTo(RuntimeProviderState next) {
        if (next == null || this == next) {
            return next != null;
        }
        return switch (this) {
            case STAGED -> next == ACTIVE || next == FAILED || next == REMOVED;
            case ACTIVE -> next == DRAINING || next == FAILED || next == REMOVED;
            case DRAINING -> next == ACTIVE || next == REVOKED || next == FAILED || next == REMOVED;
            case REVOKED -> next == REMOVED;
            case FAILED -> next == REMOVED;
            case REMOVED -> false;
        };
    }
}
