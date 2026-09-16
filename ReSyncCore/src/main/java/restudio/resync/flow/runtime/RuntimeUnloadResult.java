package restudio.resync.flow.runtime;

import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ProviderId;

import java.util.Objects;

public record RuntimeUnloadResult(
    ContractRef<ProviderId> provider,
    Status status,
    int remainingLeases,
    boolean cancellationRequested,
    long elapsedMillis
) {
    public enum Status {
        REMOVED,
        BLOCKED,
        REVOKED,
        NOT_FOUND,
        ALREADY_UNLOADING
    }

    public RuntimeUnloadResult {
        provider = Objects.requireNonNull(provider, "Provider Is Required");
        status = Objects.requireNonNull(status, "Unload Status Is Required");
        if (remainingLeases < 0 || elapsedMillis < 0) {
            throw new IllegalArgumentException("Unload Measurements Cannot Be Negative");
        }
    }
}
