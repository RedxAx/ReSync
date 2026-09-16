package restudio.resync.flow.validation;

import java.util.List;
import java.util.Objects;

import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticSet;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;

public record ValidationStep(ContractRef<CapabilityId> validatorId, DiagnosticPhase phase, int order, boolean executed, List<Diagnostic> diagnostics) {
    public ValidationStep {
        validatorId = Objects.requireNonNull(validatorId, "validator ID");
        phase = Objects.requireNonNull(phase, "phase");
        if (order < 0) {
            throw new IllegalArgumentException("Validator order cannot be negative");
        }
        diagnostics = List.copyOf(new DiagnosticSet(diagnostics == null ? List.of() : diagnostics).diagnostics());
    }

    public boolean hasBlockingDiagnostic(ValidationPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        return diagnostics.stream().anyMatch(diagnostic -> policy.blocks(diagnostic.severity()));
    }
}
