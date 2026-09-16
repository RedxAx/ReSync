package restudio.resync.migration;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

public final class ManagedFlowFileMigrationContract {
    public static final int MANIFEST_FORMAT_VERSION = 1;
    public static final int COMPLETION_FORMAT_VERSION = 1;
    public static final String MANIFEST_SCHEMA = "resync-managed-flow-files-legacy-ownership";
    public static final String COMPLETION_SCHEMA = "resync-managed-flow-files-migration-completion";
    public static final String MIGRATION_DIRECTORY = ".migration";
    public static final String MANIFEST_FILE = "legacy-managed-flow-files.manifest.json";
    public static final String COMPLETION_FILE = "managed-flow-files.completion.json";
    public static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:.*");
    private static final Set<String> RESERVED_SEGMENTS = Set.of(
        "managed-files.db",
        "managed-files.db-wal",
        "managed-files.db-shm",
        "managed-files.db-journal",
        MIGRATION_DIRECTORY,
        "managed_flow_file_store_metadata"
    );
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
        "con", "prn", "aux", "nul", "clock$",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
    );

    private ManagedFlowFileMigrationContract() {
    }

    public enum Origin {
        FRESH_INSTALL("fresh-install"),
        OFFLINE_MIGRATION_PENDING("offline-migration-pending"),
        OFFLINE_MIGRATION_ADMITTED("offline-migration-admitted");

        private final String wireValue;

        Origin(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }

        public static Origin parse(String value) {
            for (Origin origin : values()) {
                if (origin.wireValue.equals(value)) {
                    return origin;
                }
            }
            throw new IllegalArgumentException("Unsupported managed flow-file origin: " + value);
        }
    }

    public enum EntryKind {
        FILE("file"),
        DIRECTORY("directory");

        private final String wireValue;

        EntryKind(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }

        public static EntryKind parse(String value) {
            for (EntryKind kind : values()) {
                if (kind.wireValue.equals(value)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Unsupported managed flow-file entry kind: " + value);
        }
    }

    public record SourceIdentity(String snapshotId, String installId) {
        public SourceIdentity {
            snapshotId = requireIdentity(snapshotId, "sourceSnapshotId");
            installId = requireIdentity(installId, "sourceInstallId");
        }
    }

    public record DatabaseIdentity(String schemaId, int formatVersion, String writerId, int writerVersion,
                                   int contractVersion) {
        public DatabaseIdentity {
            schemaId = requireIdentity(schemaId, "schemaId");
            if (formatVersion < 1) {
                throw new IllegalArgumentException("Database format version must be positive");
            }
            writerId = requireIdentity(writerId, "writerId");
            if (writerVersion < 1) {
                throw new IllegalArgumentException("Database writer version must be positive");
            }
            if (contractVersion < 1) {
                throw new IllegalArgumentException("Database contract version must be positive");
            }
        }

        public static DatabaseIdentity current() {
            return new DatabaseIdentity(
                ManagedFlowFileStoreContract.SCHEMA,
                ManagedFlowFileStoreContract.FORMAT_VERSION,
                ManagedFlowFileStoreContract.OWNER,
                ManagedFlowFileStoreContract.WRITER_VERSION,
                ManagedFlowFileStoreContract.CONTRACT_VERSION);
        }
    }

    public record LegacyEntry(String logicalPath, EntryKind kind, long size, String sha256) {
        public LegacyEntry {
            logicalPath = requireLogicalPath(logicalPath);
            kind = Objects.requireNonNull(kind, "kind");
            if (size < 0) {
                throw new IllegalArgumentException("Managed flow-file entry size must not be negative");
            }
            sha256 = requireDigest(sha256, "sha256");
            if (kind == EntryKind.DIRECTORY && (size != 0 || !EMPTY_SHA256.equals(sha256))) {
                throw new IllegalArgumentException("Managed flow-file directories must have an empty content digest");
            }
        }
    }

    public record LogicalEntry(String logicalPath, EntryKind kind, byte[] content) {
        public LogicalEntry {
            logicalPath = requireLogicalPath(logicalPath);
            kind = Objects.requireNonNull(kind, "kind");
            content = content == null ? new byte[0] : content.clone();
            if (kind == EntryKind.DIRECTORY && content.length != 0) {
                throw new IllegalArgumentException("Managed flow-file directories cannot contain content");
            }
        }

        public long size() {
            return content.length;
        }

        public String sha256() {
            return ManagedFlowFileMigrationContract.sha256(content);
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    public record StoreMetadata(String schemaId, int formatVersion, String writerId, int writerVersion,
                                Origin origin, int contractVersion, String installIdentity,
                                String sourceSnapshotId, String sourceInstallId, String sourceArchiveIdentity,
                                String migrationId, String completionAuthorityHash, String completionHash) {
        public StoreMetadata {
            schemaId = requireIdentity(schemaId, "schemaId");
            if (formatVersion < 1) {
                throw new IllegalArgumentException("Store format version must be positive");
            }
            writerId = requireIdentity(writerId, "writerId");
            if (writerVersion < 1) {
                throw new IllegalArgumentException("Store writer version must be positive");
            }
            origin = Objects.requireNonNull(origin, "origin");
            if (contractVersion < 1) {
                throw new IllegalArgumentException("Store contract version must be positive");
            }
            installIdentity = requireIdentity(installIdentity, "installIdentity");
            sourceSnapshotId = optionalIdentity(sourceSnapshotId, "sourceSnapshotId");
            sourceInstallId = optionalIdentity(sourceInstallId, "sourceInstallId");
            sourceArchiveIdentity = optionalIdentity(sourceArchiveIdentity, "sourceArchiveIdentity");
            migrationId = optionalIdentity(migrationId, "migrationId");
            completionAuthorityHash = optionalDigest(completionAuthorityHash, "completionAuthorityHash");
            completionHash = optionalDigest(completionHash, "completionHash");
            if (origin == Origin.FRESH_INSTALL) {
                if (sourceSnapshotId != null || sourceInstallId != null || sourceArchiveIdentity != null
                    || migrationId != null || completionAuthorityHash != null || completionHash != null) {
                    throw new IllegalArgumentException("Fresh managed flow-file stores cannot carry migration provenance");
                }
            } else if (sourceSnapshotId == null || sourceInstallId == null || sourceArchiveIdentity == null || migrationId == null) {
                throw new IllegalArgumentException("Migrated managed flow-file stores require complete migration provenance");
            } else if (origin == Origin.OFFLINE_MIGRATION_PENDING
                && (completionAuthorityHash != null || completionHash != null)) {
                throw new IllegalArgumentException("Pending managed flow-file stores cannot carry completion hashes");
            } else if (origin == Origin.OFFLINE_MIGRATION_ADMITTED
                && (completionAuthorityHash == null || completionHash == null)) {
                throw new IllegalArgumentException("Admitted managed flow-file stores require completion hashes");
            }
        }

        public static StoreMetadata fresh(String installIdentity) {
            return new StoreMetadata(
                ManagedFlowFileStoreContract.SCHEMA,
                ManagedFlowFileStoreContract.FORMAT_VERSION,
                ManagedFlowFileStoreContract.OWNER,
                ManagedFlowFileStoreContract.WRITER_VERSION,
                Origin.FRESH_INSTALL,
                ManagedFlowFileStoreContract.CONTRACT_VERSION,
                installIdentity,
                null,
                null,
                null,
                null,
                null,
                null);
        }

        public static StoreMetadata migratedPending(String installIdentity, SourceIdentity source, String sourceArchiveIdentity,
                                                    String migrationId) {
            Objects.requireNonNull(source, "source");
            return new StoreMetadata(
                ManagedFlowFileStoreContract.SCHEMA,
                ManagedFlowFileStoreContract.FORMAT_VERSION,
                ManagedFlowFileStoreContract.OWNER,
                ManagedFlowFileStoreContract.WRITER_VERSION,
                Origin.OFFLINE_MIGRATION_PENDING,
                ManagedFlowFileStoreContract.CONTRACT_VERSION,
                installIdentity,
                source.snapshotId(),
                source.installId(),
                sourceArchiveIdentity,
                migrationId,
                null,
                null);
        }

        public static StoreMetadata migratedAdmitted(String installIdentity, SourceIdentity source, String sourceArchiveIdentity,
                                                     String migrationId, String completionAuthorityHash, String completionHash) {
            Objects.requireNonNull(source, "source");
            return new StoreMetadata(
                ManagedFlowFileStoreContract.SCHEMA,
                ManagedFlowFileStoreContract.FORMAT_VERSION,
                ManagedFlowFileStoreContract.OWNER,
                ManagedFlowFileStoreContract.WRITER_VERSION,
                Origin.OFFLINE_MIGRATION_ADMITTED,
                ManagedFlowFileStoreContract.CONTRACT_VERSION,
                installIdentity,
                source.snapshotId(),
                source.installId(),
                sourceArchiveIdentity,
                migrationId,
                completionAuthorityHash,
                completionHash);
        }

        public SourceIdentity source() {
            return origin == Origin.FRESH_INSTALL ? null : new SourceIdentity(sourceSnapshotId, sourceInstallId);
        }

        public DatabaseIdentity databaseIdentity() {
            return new DatabaseIdentity(schemaId, formatVersion, writerId, writerVersion, contractVersion);
        }
    }

    public static String requireLogicalPath(String value) {
        Objects.requireNonNull(value, "logicalPath");
        if (value.isEmpty() || !value.equals(Normalizer.normalize(value, Normalizer.Form.NFC))) {
            throw new IllegalArgumentException("Managed flow-file logical path is not normalized");
        }
        if (value.indexOf('\u0000') >= 0 || value.indexOf('\\') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Managed flow-file logical path contains an invalid character");
        }
        if (value.startsWith("/") || value.endsWith("/") || WINDOWS_DRIVE.matcher(value).matches()) {
            throw new IllegalArgumentException("Managed flow-file logical path must be relative");
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            requirePathSegment(segment);
        }
        return value;
    }

    public static String caseCollisionKey(String logicalPath) {
        String normalized = requireLogicalPath(logicalPath);
        return Normalizer.normalize(normalized, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    public static void validateEntries(List<LegacyEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        TreeMap<String, LegacyEntry> exact = new TreeMap<>();
        TreeMap<String, LegacyEntry> folded = new TreeMap<>();
        for (LegacyEntry entry : entries) {
            if (exact.put(entry.logicalPath(), entry) != null) {
                throw new IllegalArgumentException("Duplicate managed flow-file logical path: " + entry.logicalPath());
            }
            String foldedPath = caseCollisionKey(entry.logicalPath());
            if (folded.put(foldedPath, entry) != null) {
                throw new IllegalArgumentException("Case-colliding managed flow-file logical path: " + entry.logicalPath());
            }
        }
        for (String path : exact.keySet()) {
            int separator = path.indexOf('/');
            while (separator >= 0) {
                String ancestor = path.substring(0, separator);
                LegacyEntry ancestorEntry = exact.get(ancestor);
                if (ancestorEntry == null) {
                    throw new IllegalArgumentException("Managed flow-file path is missing its directory ancestor: " + ancestor);
                }
                if (ancestorEntry.kind() != EntryKind.DIRECTORY) {
                    throw new IllegalArgumentException("Managed flow-file path has a file/directory collision: " + ancestor);
                }
                separator = path.indexOf('/', separator + 1);
            }
        }
    }

    public static void validateLogicalEntries(List<LogicalEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        List<LegacyEntry> metadata = new ArrayList<>(entries.size());
        for (LogicalEntry entry : entries) {
            metadata.add(new LegacyEntry(entry.logicalPath(), entry.kind(), entry.size(), entry.sha256()));
        }
        validateEntries(metadata);
    }

    public static String canonicalLogicalContentHash(List<LogicalEntry> entries) {
        validateLogicalEntries(entries);
        List<LogicalEntry> sorted = new ArrayList<>(entries);
        sorted.sort(logicalEntryComparator());
        StringBuilder canonical = new StringBuilder();
        canonical.append("format=1\n");
        canonical.append("entries=").append(sorted.size()).append('\n');
        for (LogicalEntry entry : sorted) {
            canonical.append("entry=").append(encode(entry.logicalPath())).append('|')
                .append(entry.kind().wireValue()).append('|')
                .append(entry.size()).append('|')
                .append(entry.sha256()).append('\n');
        }
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(content == null ? new byte[0] : content));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static Comparator<LegacyEntry> legacyEntryComparator() {
        return Comparator.comparing(LegacyEntry::logicalPath, ManagedFlowFileMigrationContract::compareLogicalPath);
    }

    public static Comparator<LogicalEntry> logicalEntryComparator() {
        return Comparator.comparing(LogicalEntry::logicalPath, ManagedFlowFileMigrationContract::compareLogicalPath);
    }

    private static int compareLogicalPath(String left, String right) {
        return restudio.resync.flow.canonical.CanonicalJson.compareCodePoints(left, right);
    }

    public static String requireDigest(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(field + " must be a SHA-256 digest");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    public static String requireIdentity(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || !value.equals(value.strip()) || value.indexOf('\u0000') >= 0
            || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\\') >= 0
            || value.indexOf('/') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    public static String optionalIdentity(String value, String field) {
        return value == null ? null : requireIdentity(value, field);
    }

    public static String optionalDigest(String value, String field) {
        return value == null ? null : requireDigest(value, field);
    }

    public static String encode(String value) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void requirePathSegment(String segment) {
        if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || !segment.equals(segment.strip())
            || segment.endsWith(".") || segment.endsWith(" ")) {
            throw new IllegalArgumentException("Managed flow-file logical path contains an unsafe segment");
        }
        String folded = segment.toLowerCase(Locale.ROOT);
        if (RESERVED_SEGMENTS.contains(folded) || WINDOWS_RESERVED_NAMES.contains(stripWindowsSuffix(folded))) {
            throw new IllegalArgumentException("Managed flow-file logical path uses a reserved segment: " + segment);
        }
        for (int index = 0; index < segment.length(); index++) {
            char current = segment.charAt(index);
            if (Character.isISOControl(current) || current == ':' || current == '*' || current == '?'
                || current == '"' || current == '<' || current == '>' || current == '|') {
                throw new IllegalArgumentException("Managed flow-file logical path contains an unsafe segment");
            }
        }
    }

    private static String stripWindowsSuffix(String segment) {
        int dot = segment.indexOf('.');
        return dot < 0 ? segment : segment.substring(0, dot);
    }
}
