package restudio.resync.flow;

import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.Objects;

public final class CompiledPlanAdmissionException extends IllegalStateException {
    private final Reason reason;
    private final ServerResourceLocator resource;
    private final boolean deterministic;

    public CompiledPlanAdmissionException(Reason reason, ServerResourceLocator resource, String message) {
        this(reason, resource, message, null, true);
    }

    public CompiledPlanAdmissionException(Reason reason, ServerResourceLocator resource, String message,
                                          Throwable cause, boolean deterministic) {
        super(Objects.requireNonNull(message, "Compiled Plan Admission Message Is Required"), cause);
        this.reason = Objects.requireNonNull(reason, "Compiled Plan Admission Reason Is Required");
        this.resource = resource;
        this.deterministic = deterministic;
    }

    public Reason reason() {
        return reason;
    }

    public ServerResourceLocator resource() {
        return resource;
    }

    public boolean deterministic() {
        return deterministic;
    }

    public enum Reason {
        REPOSITORY_CLOSED,
        AUTHORITY_UNAVAILABLE,
        RESOURCE_MISSING,
        RESOURCE_INACTIVE,
        RESOURCE_IDENTITY_MISMATCH,
        REVISION_MISMATCH,
        CATALOG_BINDING_MISMATCH,
        PAYLOAD_CHECKSUM_MISMATCH,
        ENTRY_NODE_MISSING,
        FUNCTION_SOURCE_REQUIRED,
        FUNCTION_SOURCE_PROVENANCE_COLLISION,
        FUNCTION_DEPENDENCY_CYCLE,
        RESOURCE_MUTATED_DURING_ADMISSION,
        FUNCTION_DEPENDENCY_MISSING,
        FUNCTION_DEPENDENCY_STALE,
        COMPILATION_REJECTED,
        COMPILER_FAILURE
    }
}
