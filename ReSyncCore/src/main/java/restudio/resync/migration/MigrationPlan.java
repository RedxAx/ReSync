package restudio.resync.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import restudio.resync.flow.identity.ResourceKey;

public final class MigrationPlan {
    private final String sourceSnapshotId;
    private final String sourceManifestHash;
    private final int sourceFormatVersion;
    private final int targetFormatVersion;
    private final String quarantineReportHash;
    private final List<MigrationOperation> operations;
    private final String invocationHash;
    private final String snapshotAdapterResultHash;
    private final String planHash;

    public MigrationPlan(String sourceSnapshotId, String sourceManifestHash, int sourceFormatVersion, int targetFormatVersion, String quarantineReportHash, List<MigrationOperation> operations) {
        this(sourceSnapshotId, sourceManifestHash, sourceFormatVersion, targetFormatVersion, quarantineReportHash,
            operations, "", "");
    }

    public MigrationPlan(String sourceSnapshotId, String sourceManifestHash, int sourceFormatVersion, int targetFormatVersion,
                         String quarantineReportHash, List<MigrationOperation> operations, String invocationHash) {
        this(sourceSnapshotId, sourceManifestHash, sourceFormatVersion, targetFormatVersion, quarantineReportHash,
            operations, invocationHash, "");
    }

    public MigrationPlan(String sourceSnapshotId, String sourceManifestHash, int sourceFormatVersion, int targetFormatVersion,
                         String quarantineReportHash, List<MigrationOperation> operations, String invocationHash,
                         String snapshotAdapterResultHash) {
        this.sourceSnapshotId = MigrationCanonical.requireText(sourceSnapshotId, "sourceSnapshotId");
        this.sourceManifestHash = MigrationCanonical.requireDigest(sourceManifestHash, "sourceManifestHash");
        if (sourceFormatVersion < 1 || targetFormatVersion < 1) {
            throw new IllegalArgumentException("Migration Format Versions Must Be Positive");
        }
        this.sourceFormatVersion = sourceFormatVersion;
        this.targetFormatVersion = targetFormatVersion;
        this.quarantineReportHash = MigrationCanonical.requireDigest(quarantineReportHash, "quarantineReportHash");
        this.invocationHash = optionalDigest(invocationHash, "invocationHash");
        this.snapshotAdapterResultHash = optionalDigest(snapshotAdapterResultHash, "snapshotAdapterResultHash");
        List<MigrationOperation> sorted = new ArrayList<>(operations == null ? List.of() : operations);
        sorted.sort(Comparator.comparing(MigrationOperation::canonical));
        Set<String> identities = new HashSet<>();
        for (MigrationOperation operation : sorted) {
            if (!identities.add(operation.canonical())) {
                throw new IllegalArgumentException("Duplicate Migration Operation");
            }
        }
        this.operations = List.copyOf(sorted);
        this.planHash = MigrationCanonical.sha256(canonicalText());
    }

    public MigrationPlan withInvocationHash(String value) {
        return new MigrationPlan(sourceSnapshotId, sourceManifestHash, sourceFormatVersion, targetFormatVersion,
            quarantineReportHash, operations, value, snapshotAdapterResultHash);
    }

    public MigrationPlan withSnapshotAdapterResultHash(String value) {
        return new MigrationPlan(sourceSnapshotId, sourceManifestHash, sourceFormatVersion, targetFormatVersion,
            quarantineReportHash, operations, invocationHash, value);
    }

    public String sourceSnapshotId() {
        return sourceSnapshotId;
    }

    public String sourceManifestHash() {
        return sourceManifestHash;
    }

    public int sourceFormatVersion() {
        return sourceFormatVersion;
    }

    public int targetFormatVersion() {
        return targetFormatVersion;
    }

    public String quarantineReportHash() {
        return quarantineReportHash;
    }

    public List<MigrationOperation> operations() {
        return operations;
    }

    public String invocationHash() {
        return invocationHash;
    }

    public String snapshotAdapterResultHash() {
        return snapshotAdapterResultHash;
    }

    public String planHash() {
        return planHash;
    }

    public String planId() {
        return "sha256:" + planHash;
    }

    public String canonicalText() {
        StringBuilder value = new StringBuilder();
        value.append("format=1\n");
        value.append("source-snapshot-id=").append(MigrationCanonical.encode(sourceSnapshotId)).append('\n');
        value.append("source-manifest-hash=").append(sourceManifestHash).append('\n');
        value.append("source-format=").append(sourceFormatVersion).append('\n');
        value.append("target-format=").append(targetFormatVersion).append('\n');
        value.append("quarantine-report-hash=").append(quarantineReportHash).append('\n');
        if (!invocationHash.isEmpty()) {
            value.append("invocation-hash=").append(invocationHash).append('\n');
        }
        if (!snapshotAdapterResultHash.isEmpty()) {
            value.append("snapshot-adapter-result-hash=").append(snapshotAdapterResultHash).append('\n');
        }
        value.append("operations=").append(operations.size()).append('\n');
        operations.forEach(operation -> value.append(operation.canonical()));
        return value.toString();
    }

    public byte[] canonicalBytes() {
        return canonicalText().getBytes(StandardCharsets.UTF_8);
    }

    public void write(Path path) throws IOException {
        AtomicFiles.write(path, (canonicalText() + "plan-hash=" + planHash + "\n").getBytes(StandardCharsets.UTF_8));
    }

    public static MigrationPlan read(Path path) throws IOException {
        List<String> lines = Files.readAllLines(MigrationPaths.requirePath(path, "planPath"), StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.getLast().startsWith("plan-hash=")) {
            throw new MigrationException("Plan Hash Is Missing");
        }
        String storedHash = MigrationCanonical.requireDigest(lines.removeLast().substring("plan-hash=".length()), "planHash");
        int cursor = 0;
        require(lines, cursor++, "format=1");
        String sourceSnapshotId = MigrationCanonical.decode(raw(lines, cursor++, "source-snapshot-id="));
        String sourceManifestHash = raw(lines, cursor++, "source-manifest-hash=");
        int sourceFormat = integer(lines, cursor++, "source-format=");
        int targetFormat = integer(lines, cursor++, "target-format=");
        String quarantineHash = raw(lines, cursor++, "quarantine-report-hash=");
        String invocationHash = "";
        if (cursor < lines.size() && lines.get(cursor).startsWith("invocation-hash=")) {
            invocationHash = lines.get(cursor++).substring("invocation-hash=".length());
        }
        String snapshotAdapterResultHash = "";
        if (cursor < lines.size() && lines.get(cursor).startsWith("snapshot-adapter-result-hash=")) {
            snapshotAdapterResultHash = lines.get(cursor++).substring("snapshot-adapter-result-hash=".length());
        }
        int count = integer(lines, cursor++, "operations=");
        List<MigrationOperation> operations = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String[] fields = raw(lines, cursor++, "operation=").split("\\|", -1);
            if (fields.length != 6 && fields.length != 7) {
                throw new MigrationException("Invalid Migration Operation");
            }
            if (fields.length == 6) {
                operations.add(new MigrationOperation(MigrationCanonical.decode(fields[0]), MigrationCanonical.decode(fields[1]), MigrationCanonical.decode(fields[2]), MigrationCanonical.decode(fields[3]), fields[4], fields[5]));
            } else {
                String identity = MigrationCanonical.decode(fields[6]);
                operations.add(identity.isBlank()
                    ? new MigrationOperation(MigrationCanonical.decode(fields[0]), MigrationCanonical.decode(fields[1]), MigrationCanonical.decode(fields[2]), MigrationCanonical.decode(fields[3]), fields[4], fields[5])
                    : new MigrationOperation(MigrationCanonical.decode(fields[0]), MigrationCanonical.decode(fields[1]), MigrationCanonical.decode(fields[2]), MigrationCanonical.decode(fields[3]), fields[4], fields[5], Optional.of(ResourceKey.parseCanonicalText(identity))));
            }
        }
        if (cursor != lines.size()) {
            throw new MigrationException("Unexpected Migration Plan Content");
        }
        MigrationPlan plan = new MigrationPlan(sourceSnapshotId, sourceManifestHash, sourceFormat, targetFormat,
            quarantineHash, operations, invocationHash, snapshotAdapterResultHash);
        if (!plan.planHash.equals(storedHash)) {
            throw new MigrationException("Migration Plan Hash Does Not Match Content");
        }
        return plan;
    }

    private static String raw(List<String> lines, int index, String prefix) throws MigrationException {
        if (index >= lines.size() || !lines.get(index).startsWith(prefix)) {
            throw new MigrationException("Missing Migration Plan Line: " + prefix);
        }
        return lines.get(index).substring(prefix.length());
    }

    private static void require(List<String> lines, int index, String expected) throws MigrationException {
        if (index >= lines.size() || !lines.get(index).equals(expected)) {
            throw new MigrationException("Invalid Migration Plan Header");
        }
    }

    private static int integer(List<String> lines, int index, String prefix) throws MigrationException {
        try {
            return Integer.parseInt(raw(lines, index, prefix));
        } catch (NumberFormatException exception) {
            throw new MigrationException("Invalid Migration Plan Number", exception);
        }
    }

    private static String optionalDigest(String value, String field) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return MigrationCanonical.requireDigest(value, field);
    }
}
