package restudio.resync.migration;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class QuarantineReport {
    private final List<QuarantineRecord> records;
    private final String reportHash;

    public QuarantineReport(List<QuarantineRecord> records) {
        List<QuarantineRecord> sorted = new ArrayList<>(records == null ? List.of() : records);
        sorted.sort(java.util.Comparator.comparing(QuarantineRecord::recordId));
        Set<String> ids = new HashSet<>();
        for (QuarantineRecord record : sorted) {
            if (!ids.add(record.recordId())) {
                throw new IllegalArgumentException("Duplicate Quarantine Record: " + record.recordId());
            }
        }
        this.records = List.copyOf(sorted);
        this.reportHash = MigrationCanonical.sha256(canonicalText());
    }

    public static QuarantineReport empty() {
        return new QuarantineReport(List.of());
    }

    public List<QuarantineRecord> records() {
        return records;
    }

    public String reportHash() {
        return reportHash;
    }

    public String canonicalText() {
        StringBuilder value = new StringBuilder("format=1\nrecords=").append(records.size()).append('\n');
        records.forEach(record -> value.append(record.canonical()));
        return value.toString();
    }

    public QuarantineAcceptance accept(String acceptedBy, Instant acceptedAt) {
        List<String> ids = records.stream().map(QuarantineRecord::recordId).toList();
        return acceptance(ids, acceptedBy, acceptedAt);
    }

    public QuarantineAcceptance acceptance(List<String> acceptedRecordIds, String acceptedBy, Instant acceptedAt) {
        List<String> ids = acceptedRecordIds == null ? List.of() : acceptedRecordIds.stream().distinct().sorted().toList();
        Set<String> known = records.stream().map(QuarantineRecord::recordId).collect(java.util.stream.Collectors.toSet());
        if (!known.containsAll(ids)) {
            throw new IllegalArgumentException("Acceptance Contains An Unknown Quarantine Record");
        }
        String normalizedBy = MigrationCanonical.requireText(acceptedBy, "acceptedBy");
        String acceptanceHash = MigrationCanonical.sha256(reportHash + "\n" + normalizedBy + "\n" + String.join("\n", ids));
        return new QuarantineAcceptance(reportHash, ids, normalizedBy, acceptedAt, acceptanceHash);
    }

    public void requireAccepted(QuarantineAcceptance acceptance) throws IOException {
        if (acceptance == null || !reportHash.equals(acceptance.reportHash())) {
            throw new MigrationException("Quarantine Acceptance Does Not Match Report");
        }
        List<String> expected = records.stream().map(QuarantineRecord::recordId).toList();
        if (!expected.equals(acceptance.acceptedRecordIds())) {
            throw new MigrationException("Every Quarantine Record Requires Explicit Acceptance");
        }
        String expectedHash = MigrationCanonical.sha256(reportHash + "\n" + acceptance.acceptedBy() + "\n" + String.join("\n", acceptance.acceptedRecordIds()));
        if (!expectedHash.equals(acceptance.acceptanceHash())) {
            throw new MigrationException("Quarantine Acceptance Hash Is Invalid");
        }
    }
}
