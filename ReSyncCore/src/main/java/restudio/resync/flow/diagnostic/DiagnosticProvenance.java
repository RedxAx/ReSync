package restudio.resync.flow.diagnostic;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import restudio.resync.flow.identity.OwnerId;

public record DiagnosticProvenance(
    OwnerId ownerId,
    DiagnosticSourceKind sourceKind,
    String sourceUri,
    String sourceHash,
    String sourceVersion,
    String buildId,
    Instant loadedAt,
    Map<String, Object> unknown
) {
    public DiagnosticProvenance {
        sourceKind = Objects.requireNonNull(sourceKind, "provenance.sourceKind");
        sourceUri = DiagnosticValidation.text(sourceUri, "provenance.sourceUri", 1024);
        sourceHash = DiagnosticValidation.sourceHash(sourceHash);
        sourceVersion = DiagnosticValidation.text(sourceVersion, "provenance.sourceVersion", 128);
        buildId = DiagnosticValidation.text(buildId, "provenance.buildId", 128);
        unknown = DiagnosticValidation.immutableMap(unknown, "provenance.unknown");
        DiagnosticValidation.rejectUnknownCollisions(unknown, "diagnostic provenance", Set.of(
            "ownerId", "sourceKind", "sourceUri", "sourceHash", "sourceVersion", "buildId", "loadedAt"
        ));
    }

    public DiagnosticProvenance(OwnerId ownerId, DiagnosticSourceKind sourceKind, String sourceUri, String sourceHash, String sourceVersion, String buildId, Instant loadedAt) {
        this(ownerId, sourceKind, sourceUri, sourceHash, sourceVersion, buildId, loadedAt, Map.of());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        if (ownerId != null) {
            value.put("ownerId", ownerId.canonicalText());
        }
        value.put("sourceKind", sourceKind.wireName());
        value.put("sourceUri", sourceUri);
        value.put("sourceHash", sourceHash);
        value.put("sourceVersion", sourceVersion);
        value.put("buildId", buildId);
        if (loadedAt != null) {
            value.put("loadedAt", loadedAt.toString());
        }
        value.putAll(unknown);
        return Collections.unmodifiableMap(value);
    }

    public Map<String, Object> toMap(DiagnosticRedaction redaction) {
        return toRedactedMap(redaction, false);
    }

    Map<String, Object> toRedactedMap(DiagnosticRedaction redaction, boolean hideDetails) {
        Map<String, Object> value = new LinkedHashMap<>();
        if (ownerId != null) {
            value.put("ownerId", ownerId.canonicalText());
        }
        value.put("sourceKind", sourceKind.wireName());
        value.put("sourceUri", DiagnosticValidation.redactedUri(sourceUri, redaction));
        value.put("sourceHash", hideDetails || redaction.ordinal() >= DiagnosticRedaction.SECRET.ordinal() ? "[REDACTED]" : sourceHash);
        value.put("sourceVersion", sourceVersion);
        value.put("buildId", hideDetails || redaction.ordinal() >= DiagnosticRedaction.SENSITIVE.ordinal() ? "[REDACTED]" : buildId);
        if (loadedAt != null && !hideDetails && redaction.ordinal() < DiagnosticRedaction.SECRET.ordinal()) {
            value.put("loadedAt", loadedAt.toString());
        }
        value.putAll(DiagnosticValidation.redactedMap(unknown, redaction));
        return Collections.unmodifiableMap(value);
    }
}
