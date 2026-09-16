package restudio.resync.flow.runtime;

import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.diagnostic.DiagnosticCodeCatalog;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticProvenance;
import restudio.resync.flow.diagnostic.DiagnosticRedaction;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.diagnostic.DiagnosticSourceKind;
import restudio.resync.flow.diagnostics.StructuredFlowDiagnosticReporter;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerId;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class StructuredRuntimeAuditBoundary implements RuntimeAuditBoundary {
    private static final String CODE = "RUNTIME.INVOCATION_AUDIT";
    private final StructuredFlowDiagnosticReporter reporter;
    private final ServerId serverId;

    public StructuredRuntimeAuditBoundary(StructuredFlowDiagnosticReporter reporter, ServerId serverId) {
        this.reporter = Objects.requireNonNull(reporter, "Structured Runtime Audit Reporter Is Required");
        this.serverId = Objects.requireNonNull(serverId, "Runtime Audit Server Identity Is Required");
    }

    public boolean available() {
        return available(RuntimeSemantics.Audit.METADATA);
    }

    public record PreparedAvailability(StructuredRuntimeAuditBoundary owner,
                                       StructuredFlowDiagnosticReporter.Observation observation, boolean available) {
    }

    public PreparedAvailability prepareAvailability() {
        StructuredFlowDiagnosticReporter.Observation observation = reporter.validatedHealthObservation();
        return new PreparedAvailability(this, observation, observation != null);
    }

    public boolean isCurrent(PreparedAvailability prepared) {
        return prepared != null && prepared.owner() == this && reporter.isHealthCurrent(prepared.observation());
    }

    @Override
    public boolean available(RuntimeSemantics.Audit audit) {
        if (audit == null || audit == RuntimeSemantics.Audit.NONE) {
            return true;
        }
        return reporter.validatedHealthObservation() != null;
    }

    @Override
    public synchronized void record(RuntimeLeaseInput.AuditEvent event) {
        Objects.requireNonNull(event, "Runtime Audit Event Is Required");
        Map<String, Object> evidence = Map.of(
            "leaseId", event.leaseId().toString(),
            "deliveryId", event.deliveryId().toString(),
            "authorityReference", event.authorityReference(),
            "bindingReference", event.bindingReference(),
            "idempotencyReference", event.idempotencyReference(),
            "status", event.status().name().toLowerCase(Locale.ROOT),
            "sensitive", event.sensitive(),
            "phase", event.phase(),
            "provenance", event.provenance() == null ? Map.of() : event.provenance().canonicalValue());
        String sourceHash = CanonicalHash.sha256(JsonValue.fromJava(evidence));
        Diagnostic diagnostic = Diagnostic.builder(CODE, DiagnosticSeverity.INFO, DiagnosticPhase.ENVIRONMENT, "audit")
            .catalog(DiagnosticCodeCatalog.defaultCatalog())
            .messageKey(ContractRef.of(OwnerId.of("resync"), CapabilityId.of("runtime-invocation-audit")))
            .serverId(serverId)
            .evidence(evidence)
            .provenance(new DiagnosticProvenance(
                OwnerId.of("resync"), DiagnosticSourceKind.LOCAL, "runtime-invocation", sourceHash,
                "runtime-v1", "resync", null))
            .correlationId(event.deliveryId())
            .durable(true)
            .redaction(DiagnosticRedaction.PUBLIC)
            .build();
        var existing = reporter.store().load(event.deliveryId());
        existing.ifPresent(stored -> {
            if (stored.diagnostics().diagnostics().size() != 1
                || !stored.diagnostics().diagnostics().getFirst().toMap().equals(diagnostic.toMap())) {
                throw new IllegalStateException("Runtime audit delivery ID was reused with different content");
            }
        });
        if (existing.isEmpty()) {
            reporter.reportDiagnostics(event.deliveryId(), List.of(diagnostic));
        }
    }
}
