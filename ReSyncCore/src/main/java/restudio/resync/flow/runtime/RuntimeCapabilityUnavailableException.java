package restudio.resync.flow.runtime;

import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ProviderId;

import java.util.Objects;
import java.util.UUID;

public final class RuntimeCapabilityUnavailableException extends IllegalStateException {
    private final Diagnostic diagnostic;

    public RuntimeCapabilityUnavailableException(RuntimeBindingKey key, String reason) {
        this(key, code(reason), reason);
    }

    public RuntimeCapabilityUnavailableException(RuntimeBindingKey key, String code, String reason) {
        super("Runtime Capability Unavailable: " + key.canonical() + " (" + reason + ")");
        diagnostic = diagnostic(code, reason);
    }

    public RuntimeCapabilityUnavailableException(ContractRef<ProviderId> provider, String reason) {
        super("Runtime Provider Unavailable: " + provider.canonicalText() + " (" + reason + ")");
        diagnostic = diagnostic(code(reason), reason);
    }

    public Diagnostic diagnostic() {
        return diagnostic;
    }

    private static String code(String reason) {
        String normalized = Objects.requireNonNull(reason, "Unavailable Reason Is Required").toLowerCase();
        if (normalized.contains("revoked")) {
            return "RUNTIME.PROVIDER_REVOKED";
        }
        if (normalized.contains("drain") || normalized.contains("unload")) {
            return "RUNTIME.PROVIDER_DRAINING";
        }
        if (normalized.contains("authorization") || normalized.contains("confirmation")) {
            return "RUNTIME.AUTHORIZATION_DENIED";
        }
        return "RUNTIME.INVALID_INVOCATION";
    }

    private static Diagnostic diagnostic(String code, String reason) {
        return Diagnostic.builder(code, DiagnosticSeverity.ERROR, DiagnosticPhase.ENVIRONMENT, "execution")
            .messageKey(new ContractRef<>(new OwnerId("restudio.resync"), new NodeId(code.toLowerCase().replace('.', '-'))))
            .message(reason)
            .remediation("Refresh the runtime plan and retry the operation.")
            .correlationId(UUID.randomUUID())
            .build();
    }
}
