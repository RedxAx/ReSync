package restudio.resync.restore;

import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticProvenance;
import restudio.resync.flow.diagnostic.DiagnosticRedaction;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.diagnostic.DiagnosticSourceKind;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.migration.Snapshot;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

final class RestoreDiagnostics {
    private static final OwnerId OWNER = OwnerId.of("resync");
    private static final ContractRef<OperationId> MESSAGE_KEY = ContractRef.of(OWNER, OperationId.of("restore"));

    private RestoreDiagnostics() {
    }

    static Diagnostic error(Snapshot snapshot, String code, String stage, String message, String remediation, Map<String, ?> evidence) {
        String manifestHash = snapshot == null ? "0".repeat(64) : snapshot.manifest().manifestHash();
        String snapshotId = snapshot == null ? "unknown" : snapshot.metadata().snapshotId();
        String build = snapshot == null ? "unknown" : snapshot.metadata().build();
        String normalizedMessage = message == null || message.isBlank() ? "Restore Failed" : message.length() > 512 ? message.substring(0, 509) + "..." : message;
        UUID correlation = UUID.nameUUIDFromBytes((code + "|" + stage + "|" + manifestHash + "|" + normalizedMessage).getBytes(StandardCharsets.UTF_8));
        return Diagnostic.builder(code, DiagnosticSeverity.ERROR, DiagnosticPhase.ENVIRONMENT, stage)
            .messageKey(MESSAGE_KEY)
            .message(normalizedMessage)
            .remediation(remediation)
            .evidence(evidence)
            .correlationId(correlation)
            .provenance(new DiagnosticProvenance(OWNER, DiagnosticSourceKind.LOCAL, "snapshot:" + snapshotId, manifestHash, "restore", build, null))
            .durable(true)
            .redaction(DiagnosticRedaction.PUBLIC)
            .build();
    }
}
