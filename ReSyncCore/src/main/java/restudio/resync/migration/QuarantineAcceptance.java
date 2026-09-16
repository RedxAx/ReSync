package restudio.resync.migration;

import java.time.Instant;
import java.util.List;

public record QuarantineAcceptance(String reportHash, List<String> acceptedRecordIds, String acceptedBy, Instant acceptedAt, String acceptanceHash) {
    public QuarantineAcceptance {
        reportHash = MigrationCanonical.requireDigest(reportHash, "reportHash");
        acceptedRecordIds = acceptedRecordIds == null ? List.of() : acceptedRecordIds.stream().map(value -> MigrationCanonical.requireText(value, "accepted record id")).distinct().sorted().toList();
        acceptedBy = MigrationCanonical.requireText(acceptedBy, "acceptedBy");
        acceptedAt = java.util.Objects.requireNonNull(acceptedAt, "acceptedAt");
        acceptanceHash = MigrationCanonical.requireDigest(acceptanceHash, "acceptanceHash");
    }
}
