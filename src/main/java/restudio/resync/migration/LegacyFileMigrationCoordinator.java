package restudio.resync.migration;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class LegacyFileMigrationCoordinator {
    public static final String DIRECTORY = ".migrations";
    public static final String WORLD_AUDIT_MIGRATION_ID = "resync.world-audit-v2";
    public static final String CUSTOM_BLOCKS_MIGRATION_ID = "resync.custom-blocks-v1";
    private static final int FORMAT_VERSION = 1;
    private static final String KIND = "resync.file-migration";
    private static final String PREPARED = "PREPARED";
    private static final String COMMITTED = "COMMITTED";
    private static final List<String> REPORT_FIELDS = List.of(
        "backupFile",
        "backupHash",
        "contentHash",
        "formatVersion",
        "kind",
        "migrationId",
        "normalized",
        "owner",
        "sourceFile",
        "sourceHash",
        "sourceVersion",
        "state",
        "targetHash",
        "targetVersion");
    private static final Set<String> REPORT_FIELD_SET = Set.copyOf(REPORT_FIELDS);
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private LegacyFileMigrationCoordinator() {
    }

    public static boolean hasArtifacts(Path source, String migrationId) {
        Path normalized = MigrationPaths.requirePath(source, "source");
        String safeId = requireId(migrationId, "migrationId");
        Path root = normalized.getParent();
        if (root == null) {
            return false;
        }
        Path migrationRoot = root.resolve(DIRECTORY).toAbsolutePath().normalize();
        return Files.exists(migrationRoot.resolve(safeId + ".json"), LinkOption.NOFOLLOW_LINKS)
            || Files.exists(migrationRoot.resolve(safeId + ".backup"), LinkOption.NOFOLLOW_LINKS);
    }

    public static boolean isArtifactName(String name) {
        if (name == null) {
            return false;
        }
        return name.equals(WORLD_AUDIT_MIGRATION_ID + ".json")
            || name.equals(WORLD_AUDIT_MIGRATION_ID + ".backup")
            || name.equals(CUSTOM_BLOCKS_MIGRATION_ID + ".json")
            || name.equals(CUSTOM_BLOCKS_MIGRATION_ID + ".backup");
    }

    public static void validateArtifact(Path artifact) throws IOException {
        Path normalized = MigrationPaths.requirePath(artifact, "migration artifact");
        String name = normalized.getFileName().toString();
        if (!isArtifactName(name)) {
            throw new IOException("Unknown migration artifact: " + name);
        }
        if (Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Migration artifact must be a regular non-symbolic-link file: " + normalized);
        }
        if (name.endsWith(".json")) {
            Report report = readReport(normalized);
            validateReportIdentity(report, name.substring(0, name.length() - ".json".length()));
        }
    }

    public static void validateArtifactSet(Path root, String migrationId) throws IOException {
        Path normalizedRoot = MigrationPaths.requireDirectory(root, "migration report root");
        String safeId = requireId(migrationId, "migrationId");
        Path reportPath = normalizedRoot.resolve(safeId + ".json").toAbsolutePath().normalize();
        Path backupPath = normalizedRoot.resolve(safeId + ".backup").toAbsolutePath().normalize();
        boolean reportExists = Files.exists(reportPath, LinkOption.NOFOLLOW_LINKS);
        boolean backupExists = Files.exists(backupPath, LinkOption.NOFOLLOW_LINKS);
        if (!reportExists && !backupExists) {
            return;
        }
        if (reportExists) {
            Report report = readReport(reportPath);
            validateReportIdentity(report, safeId);
            if (!backupExists) {
                throw new IOException("Migration backup is missing: " + backupPath);
            }
            requireRegularFile(backupPath, "migration backup");
            String backupHash = StorageSafety.sha256(Files.readAllBytes(backupPath));
            if (!report.backupFile().equals(backupPath.getFileName().toString())
                || !report.backupHash().equals(backupHash) || !report.sourceHash().equals(backupHash)) {
                throw new IOException("Migration backup does not match its canonical report");
            }
        } else {
            requireRegularFile(backupPath, "migration backup");
        }
    }

    public static void migrate(Path source, byte[] targetBytes, String owner, String migrationId,
                               long sourceVersion, long targetVersion) throws IOException {
        Path normalizedSource = MigrationPaths.requirePath(source, "source");
        String safeOwner = requireId(owner, "owner");
        String safeMigrationId = requireId(migrationId, "migrationId");
        if (targetBytes == null) {
            throw new IOException("Migration target bytes are required");
        }
        try {
            CanonicalCodec.decode(targetBytes);
        } catch (RuntimeException exception) {
            throw new IOException("Migration target bytes are not canonical JSON", exception);
        }
        Path dataRoot = normalizedSource.getParent();
        if (dataRoot == null) {
            throw new IOException("Migration source has no parent");
        }
        requireKnownMigrationId(safeMigrationId);
        Path migrationRoot = dataRoot.resolve(DIRECTORY).toAbsolutePath().normalize();
        if (!migrationRoot.startsWith(dataRoot) || migrationRoot.equals(dataRoot)) {
            throw new IOException("Migration report root escaped source root");
        }
        Files.createDirectories(migrationRoot);
        MigrationPaths.requireDirectory(migrationRoot, "migration report root");
        Path reportPath = migrationRoot.resolve(safeMigrationId + ".json").toAbsolutePath().normalize();
        Path backupPath = migrationRoot.resolve(safeMigrationId + ".backup").toAbsolutePath().normalize();
        if (Files.notExists(normalizedSource, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(normalizedSource)
            || !Files.isRegularFile(normalizedSource, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Migration source is not a regular file: " + normalizedSource);
        }
        byte[] currentBytes = Files.readAllBytes(normalizedSource);
        String targetHash = StorageSafety.sha256(targetBytes);
        Report report = Files.exists(reportPath, LinkOption.NOFOLLOW_LINKS) ? readReport(reportPath) : null;
        if (report != null) {
            requireMetadata(report, safeOwner, safeMigrationId, normalizedSource, targetHash, targetVersion);
        }
        byte[] backupBytes = null;
        if (Files.exists(backupPath, LinkOption.NOFOLLOW_LINKS)) {
            requireRegularFile(backupPath, "migration backup");
            backupBytes = Files.readAllBytes(backupPath);
            String backupHash = StorageSafety.sha256(backupBytes);
            if (report != null && (!report.backupFile().equals(backupPath.getFileName().toString())
                || !report.backupHash().equals(backupHash) || !report.sourceHash().equals(backupHash))) {
                throw new IOException("Migration backup does not match its canonical report");
            }
        }
        if (report != null) {
            if (backupBytes == null) {
                throw new IOException("Migration backup is missing: " + backupPath);
            }
            if (report.state().equals(COMMITTED)) {
                verifyTarget(currentBytes, targetBytes, report.targetHash(), normalizedSource);
                return;
            }
            if (!report.state().equals(PREPARED)) {
                throw new IOException("Migration report has an unsupported state");
            }
            String currentHash = StorageSafety.sha256(currentBytes);
            if (currentHash.equals(report.targetHash())) {
                verifyTarget(currentBytes, targetBytes, report.targetHash(), normalizedSource);
                writeReport(report.committed());
                return;
            }
            if (!currentHash.equals(report.sourceHash()) || !Arrays.equals(currentBytes, backupBytes)) {
                throw new IOException("Migration source changed after the prepared report was published");
            }
            replaceAndCommit(normalizedSource, currentBytes, targetBytes, report);
            return;
        }
        String sourceHash;
        String currentHash = StorageSafety.sha256(currentBytes);
        if (backupBytes != null && currentHash.equals(targetHash)) {
            throw new IOException("Migration target was published without its canonical report");
        }
        if (sourceVersion < 0L || sourceVersion >= targetVersion) {
            throw new IOException("Migration source version is not older than its target version");
        }
        if (backupBytes == null) {
            backupBytes = currentBytes.clone();
            try {
                AtomicFiles.writeNew(backupPath, backupBytes);
            } catch (FileAlreadyExistsException race) {
                requireRegularFile(backupPath, "migration backup");
                backupBytes = Files.readAllBytes(backupPath);
            }
            AtomicFiles.force(backupPath);
            StorageSafety.forceDirectory(migrationRoot);
        }
        sourceHash = StorageSafety.sha256(backupBytes);
        if (!currentHash.equals(sourceHash) && !currentHash.equals(targetHash)) {
            throw new IOException("Migration source does not match its recoverable backup");
        }
        Report prepared = new Report(FORMAT_VERSION, KIND, safeMigrationId, true, safeOwner,
            normalizedSource.getFileName().toString(), sourceHash, sourceVersion, PREPARED,
            targetHash, targetVersion, backupPath.getFileName().toString(), sourceHash, null, reportPath);
        writeReport(prepared);
        if (currentHash.equals(targetHash)) {
            verifyTarget(currentBytes, targetBytes, targetHash, normalizedSource);
            writeReport(prepared.committed());
            return;
        }
        replaceAndCommit(normalizedSource, currentBytes, targetBytes, prepared);
    }

    private static void replaceAndCommit(Path source, byte[] currentBytes, byte[] targetBytes, Report prepared) throws IOException {
        byte[] latestBytes = Files.readAllBytes(source);
        if (!Arrays.equals(latestBytes, targetBytes)
            && (!Arrays.equals(latestBytes, currentBytes)
                || !StorageSafety.sha256(latestBytes).equals(prepared.sourceHash()))) {
            throw new IOException("Migration source changed after the recoverable backup was published");
        }
        if (!Arrays.equals(latestBytes, targetBytes)) {
            AtomicFiles.write(source, targetBytes);
        }
        byte[] published = Files.readAllBytes(source);
        verifyTarget(published, targetBytes, prepared.targetHash(), source);
        writeReport(prepared.committed());
    }

    private static void verifyTarget(byte[] published, byte[] targetBytes, String expectedHash, Path source) throws IOException {
        if (!expectedHash.equals(StorageSafety.sha256(published)) || !Arrays.equals(published, targetBytes)) {
            throw new IOException("Migrated file bytes do not match the canonical target: " + source);
        }
    }

    private static void requireMetadata(Report report, String owner, String migrationId, Path source,
                                        String targetHash, long targetVersion) throws IOException {
        validateReportIdentity(report, migrationId);
        if (!report.owner().equals(owner)
            || !report.sourceFile().equals(source.getFileName().toString())
            || !report.targetHash().equals(targetHash) || report.targetVersion() != targetVersion) {
            throw new IOException("Migration report does not describe this source and target");
        }
    }

    private static void validateReportIdentity(Report report, String migrationId) throws IOException {
        String expectedOwner;
        String expectedSourceFile;
        long expectedTargetVersion;
        switch (migrationId) {
            case WORLD_AUDIT_MIGRATION_ID -> {
                expectedOwner = "resync.world-audit";
                expectedSourceFile = "world-audit.json";
                expectedTargetVersion = 2L;
            }
            case CUSTOM_BLOCKS_MIGRATION_ID -> {
                expectedOwner = "resync.custom-blocks";
                expectedSourceFile = "custom-blocks.json";
                expectedTargetVersion = 1L;
            }
            default -> throw new IOException("Unknown migration report identity: " + migrationId);
        }
        if (!report.migrationId().equals(migrationId) || !report.owner().equals(expectedOwner)
            || !report.sourceFile().equals(expectedSourceFile)
            || !report.backupFile().equals(migrationId + ".backup")
            || report.sourceVersion() < 0L || report.sourceVersion() >= expectedTargetVersion
            || report.targetVersion() != expectedTargetVersion || !report.normalized()) {
            throw new IOException("Migration report identity is invalid: " + migrationId);
        }
    }

    private static void requireKnownMigrationId(String migrationId) throws IOException {
        if (!WORLD_AUDIT_MIGRATION_ID.equals(migrationId) && !CUSTOM_BLOCKS_MIGRATION_ID.equals(migrationId)) {
            throw new IOException("Unknown migration report identity: " + migrationId);
        }
    }

    private static void writeReport(Report report) throws IOException {
        Path reportPath = report.path;
        AtomicFiles.write(reportPath, report.bytes());
        AtomicFiles.force(reportPath);
        StorageSafety.forceDirectory(reportPath.getParent());
    }

    private static Report readReport(Path path) throws IOException {
        requireRegularFile(path, "migration report");
        try {
            JsonValue value = CanonicalCodec.decode(Files.readAllBytes(path));
            if (!(value instanceof JsonValue.JsonObject object) || !object.fields().keySet().equals(REPORT_FIELD_SET)) {
                throw new IOException("Migration report fields are not canonical");
            }
            Report report = new Report(
                Math.toIntExact(exactLong(object, "formatVersion")),
                requiredString(object, "kind"),
                requiredString(object, "migrationId"),
                requiredBoolean(object, "normalized"),
                requiredString(object, "owner"),
                requiredString(object, "sourceFile"),
                requiredHash(object, "sourceHash"),
                exactLong(object, "sourceVersion"),
                requiredString(object, "state"),
                requiredHash(object, "targetHash"),
                exactLong(object, "targetVersion"),
                requiredString(object, "backupFile"),
                requiredHash(object, "backupHash"),
                requiredHash(object, "contentHash"),
                null);
            String expected = StorageSafety.sha256(report.withoutContentHashBytes());
            if (!expected.equals(report.contentHash())) {
                throw new IOException("Migration report content hash does not match");
            }
            return report.withPath(path);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Migration report is malformed: " + path, exception);
        }
    }

    private static long exactLong(JsonValue.JsonObject object, String name) throws IOException {
        JsonValue value = object.value(name);
        if (!(value instanceof JsonValue.JsonNumber number) || number.value().scale() > 0) {
            throw new IOException("Migration report field must be an integer: " + name);
        }
        try {
            return number.value().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IOException("Migration report integer is out of range: " + name, exception);
        }
    }

    private static boolean requiredBoolean(JsonValue.JsonObject object, String name) throws IOException {
        JsonValue value = object.value(name);
        if (!(value instanceof JsonValue.JsonBoolean booleanValue)) {
            throw new IOException("Migration report field must be a boolean: " + name);
        }
        return booleanValue.value();
    }

    private static String requiredString(JsonValue.JsonObject object, String name) throws IOException {
        JsonValue value = object.value(name);
        if (!(value instanceof JsonValue.JsonString string)) {
            throw new IOException("Migration report field must be a string: " + name);
        }
        return string.value();
    }

    private static String requiredHash(JsonValue.JsonObject object, String name) throws IOException {
        String value = requiredString(object, name);
        if (!HASH.matcher(value).matches()) {
            throw new IOException("Migration report field is not a SHA-256 hash: " + name);
        }
        return value;
    }

    private static void requireRegularFile(Path file, String field) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(field + " must be a regular non-symbolic-link file: " + file);
        }
    }

    private static String requireId(String value, String field) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private record Report(int formatVersion, String kind, String migrationId, boolean normalized, String owner,
                          String sourceFile, String sourceHash, long sourceVersion, String state, String targetHash,
                          long targetVersion, String backupFile, String backupHash, String contentHash, Path path) {

        private Report {
            if (formatVersion != FORMAT_VERSION || !KIND.equals(kind) || !ID.matcher(migrationId).matches()
                || !ID.matcher(owner).matches() || sourceFile == null || !isSafeFileName(sourceFile)
                || !HASH.matcher(sourceHash).matches() || sourceVersion < 0L
                || (!PREPARED.equals(state) && !COMMITTED.equals(state)) || !HASH.matcher(targetHash).matches()
                || targetVersion < 0L || backupFile == null || !isSafeFileName(backupFile)
                || !HASH.matcher(backupHash).matches()
                || (contentHash != null && !HASH.matcher(contentHash).matches())) {
                throw new IllegalArgumentException("Invalid migration report");
            }
            if (!normalized) {
                throw new IllegalArgumentException("Migration report must describe normalized content");
            }
        }

        private Report withPath(Path value) {
            Report result = new Report(formatVersion, kind, migrationId, normalized, owner, sourceFile, sourceHash,
                sourceVersion, state, targetHash, targetVersion, backupFile, backupHash, contentHash, value);
            return result;
        }

        private Report committed() {
            Report result = new Report(formatVersion, kind, migrationId, normalized, owner, sourceFile, sourceHash,
                sourceVersion, COMMITTED, targetHash, targetVersion, backupFile, backupHash, null, path);
            return result;
        }

        private byte[] withoutContentHashBytes() {
            return JsonValue.fromJava(body(null)).canonicalBytes();
        }

        private byte[] bytes() {
            Map<String, Object> body = body(StorageSafety.sha256(withoutContentHashBytes()));
            return JsonValue.fromJava(body).canonicalBytes();
        }

        private Map<String, Object> body(String hash) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("backupFile", backupFile);
            body.put("backupHash", backupHash);
            if (hash != null) {
                body.put("contentHash", hash);
            }
            body.put("formatVersion", formatVersion);
            body.put("kind", kind);
            body.put("migrationId", migrationId);
            body.put("normalized", normalized);
            body.put("owner", owner);
            body.put("sourceFile", sourceFile);
            body.put("sourceHash", sourceHash);
            body.put("sourceVersion", sourceVersion);
            body.put("state", state);
            body.put("targetHash", targetHash);
            body.put("targetVersion", targetVersion);
            return body;
        }
    }

    private static boolean isSafeFileName(String value) {
        return value != null && !value.isBlank() && !value.equals(".") && !value.equals("..")
            && value.indexOf('/') < 0 && value.indexOf('\\') < 0
            && value.codePoints().noneMatch(Character::isISOControl)
            && value.indexOf(':') < 0;
    }
}
