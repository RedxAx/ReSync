package restudio.resync.migration;

import java.util.List;

public record QuarantineRecord(String recordId, String code, String sourceLocation, String reason, List<String> affectedReferences, String suggestedAction, String sourceHash) {
    public QuarantineRecord {
        recordId = MigrationCanonical.requireText(recordId, "recordId");
        code = MigrationCanonical.requireText(code, "code");
        sourceLocation = MigrationCanonical.requireText(sourceLocation, "sourceLocation");
        reason = MigrationCanonical.requireText(reason, "reason");
        suggestedAction = MigrationCanonical.requireText(suggestedAction, "suggestedAction");
        affectedReferences = affectedReferences == null ? List.of() : affectedReferences.stream().map(value -> MigrationCanonical.requireText(value, "affected reference")).distinct().sorted().toList();
        sourceHash = sourceHash == null || sourceHash.isBlank() ? "" : MigrationCanonical.requireDigest(sourceHash, "sourceHash");
    }

    String canonical() {
        return "record=" + MigrationCanonical.encode(recordId) + '|' + MigrationCanonical.encode(code) + '|' + MigrationCanonical.encode(sourceLocation) + '|' + MigrationCanonical.encode(reason) + '|' + MigrationCanonical.encode(String.join("\u0001", affectedReferences)) + '|' + MigrationCanonical.encode(suggestedAction) + '|' + sourceHash + '\n';
    }
}
