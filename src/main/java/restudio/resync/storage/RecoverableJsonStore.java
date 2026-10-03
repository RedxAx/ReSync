package restudio.resync.storage;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.resync.migration.MigrationPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class RecoverableJsonStore {
    private static final int SCHEMA_VERSION = 1;
    private static final String QUARANTINE_DIRECTORY = ".quarantine";
    private static final String QUARANTINE_CATEGORY = "journals";
    private static final String ATOMIC_WRITE_CATEGORY = "atomic-writes";
    private static final String ATOMIC_TEMP_PREFIX = ".resync-";
    private static final String ATOMIC_TEMP_SUFFIX = ".tmp";
    private static final String ATOMIC_COPY_TEMP_SUFFIX = ".backup.tmp";
    private static final int MAX_PRESERVATION_ATTEMPTS = 128;
    private final Path file;
    private final Path previous;
    private final Path quarantine;
    private final Gson gson;

    public RecoverableJsonStore(Path file, Gson gson) {
        this.file = MigrationPaths.requirePath(Objects.requireNonNull(file, "file"), "journalFile");
        this.previous = this.file.resolveSibling(this.file.getFileName() + ".previous");
        this.quarantine = this.file.getParent().resolve(QUARANTINE_DIRECTORY).resolve(QUARANTINE_CATEGORY);
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    public synchronized JsonElement load() throws IOException {
        Path parent = existingParent();
        if (parent == null) {
            return null;
        }
        inventoryCanonicalArtifacts(parent, file.getFileName().toString(), previous.getFileName().toString(), quarantine);
        requireOptionalRegular(file, "Journal file");
        requireOptionalRegular(previous, "Previous journal file");
        if (!existsNoFollow(file)) {
            Journal recovered;
            try {
                recovered = readOptional(previous);
            } catch (IOException previousFailure) {
                preserveIfPresent(previous, previousFailure, "Failed to preserve corrupt previous journal");
                throw previousFailure;
            }
            if (recovered == null) {
                return null;
            }
            publishRecovery(recovered.bytes());
            return recovered.payload();
        }
        Journal current;
        try {
            current = readRequired(file);
        } catch (IOException currentFailure) {
            preserve(file, currentFailure, "Failed to preserve corrupt journal");
            if (hasCause(currentFailure, MalformedRevisionException.class)) {
                throw currentFailure;
            }
            Journal recovered;
            try {
                recovered = readRequired(previous);
            } catch (IOException previousFailure) {
                preserveIfPresent(previous, previousFailure, "Failed to preserve corrupt previous journal");
                IOException failure = new IOException("Current and previous journal versions are corrupt: " + file, previousFailure);
                failure.addSuppressed(currentFailure);
                throw failure;
            }
            publishRecovery(recovered.bytes(), currentFailure);
            return recovered.payload();
        }
        try {
            readOptional(previous);
        } catch (IOException previousFailure) {
            preserve(previous, previousFailure, "Failed to preserve corrupt previous journal");
            if (hasCause(previousFailure, MalformedRevisionException.class)) {
                throw previousFailure;
            }
        }
        return current.payload();
    }

    public synchronized void save(JsonElement payload) throws IOException {
        Path parent = ensureParent();
        inventoryCanonicalArtifacts(parent, file.getFileName().toString(), previous.getFileName().toString(), quarantine);
        requireOptionalRegular(file, "Journal file");
        requireOptionalRegular(previous, "Previous journal file");

        Journal current = null;
        if (existsNoFollow(file)) {
            try {
                current = readRequired(file);
            } catch (IOException failure) {
                preserve(file, failure, "Failed to preserve corrupt journal");
                throw failure;
            }
        }

        Journal prior = null;
        if (existsNoFollow(previous)) {
            try {
                prior = readRequired(previous);
            } catch (IOException failure) {
                preserve(previous, failure, "Failed to preserve corrupt previous journal");
                throw failure;
            }
        }

        long currentRevision = current == null ? 0L : current.revision();
        long previousRevision = prior == null ? 0L : prior.revision();
        long highest = Math.max(currentRevision, previousRevision);
        if (highest == Long.MAX_VALUE) {
            throw new IOException("Journal revision cannot be incremented: " + file);
        }
        long revision = highest + 1L;

        if (current != null) {
            StorageSafety.writeBytesAtomicStrict(previous, current.bytes());
        }

        JsonElement safePayload = payload != null ? payload.deepCopy() : new JsonObject();
        JsonObject envelope = new JsonObject();
        JsonObject metadata = current != null && current.metadata() != null
            ? current.metadata() : prior != null ? prior.metadata() : null;
        if (metadata != null) {
            metadata.entrySet().forEach(entry -> envelope.add(entry.getKey(), entry.getValue().deepCopy()));
        }
        envelope.addProperty("schemaVersion", SCHEMA_VERSION);
        envelope.addProperty("revision", revision);
        envelope.addProperty("hash", StorageSafety.sha256(gson.toJson(safePayload)));
        envelope.add("payload", safePayload);
        StorageSafety.writeUtf8AtomicStrict(file, gson.toJson(envelope));
    }

    static int recoverCanonicalArtifacts(Path root, Path quarantine) throws IOException {
        Path normalizedRoot = MigrationPaths.requireDirectory(root, "journalRoot");
        Path normalizedQuarantine = MigrationPaths.requirePath(quarantine, "journalQuarantine");
        if (!normalizedQuarantine.startsWith(normalizedRoot) || normalizedQuarantine.equals(normalizedRoot)) {
            throw new IOException("Journal quarantine escapes its root: " + normalizedQuarantine);
        }
        return inventoryCanonicalArtifacts(normalizedRoot, null, null, normalizedQuarantine);
    }

    static int recoverAtomicWrites(Path root) throws IOException {
        validateAtomicWriteRecovery(root);
        return recoverCanonicalArtifacts(root, root.resolve(QUARANTINE_DIRECTORY).resolve(ATOMIC_WRITE_CATEGORY));
    }

    static void validateAtomicWriteRecovery(Path root) throws IOException {
        Path directory = MigrationPaths.requireDirectory(root, "atomicWriteRoot");
        Path container = directory.resolve(QUARANTINE_DIRECTORY);
        if (!existsNoFollow(container)) {
            return;
        }
        requireDirectory(container, "Atomic write recovery container");
        Path quarantine = container.resolve(ATOMIC_WRITE_CATEGORY);
        try (var entries = Files.list(container)) {
            for (Path entry : entries.toList()) {
                if (!entry.equals(quarantine)) {
                    throw new IOException("Atomic write recovery contains an unexpected directory: " + entry);
                }
                requireDirectory(entry, "Atomic write recovery directory");
            }
        }
        if (!existsNoFollow(quarantine)) {
            return;
        }
        try (var entries = Files.list(quarantine)) {
            for (Path entry : entries.toList()) {
                requireRegular(entry, "Atomic write recovery evidence");
                if (!isPreservedTemporaryName(entry.getFileName().toString())) {
                    throw new IOException("Atomic write recovery evidence name is invalid: " + entry);
                }
            }
        }
    }

    private static boolean isPreservedTemporaryName(String name) {
        if (isCanonicalTemporaryName(name)) {
            return true;
        }
        int separator = name.lastIndexOf('.');
        if (separator < 0 || !isCanonicalTemporaryName(name.substring(0, separator))) {
            return false;
        }
        String suffix = name.substring(separator + 1);
        try {
            int attempt = Integer.parseInt(suffix);
            return attempt > 0 && attempt < MAX_PRESERVATION_ATTEMPTS && Integer.toString(attempt).equals(suffix);
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private JsonElement decode(JsonElement parsed) {
        if (!parsed.isJsonObject()) {
            return parsed;
        }
        JsonObject object = parsed.getAsJsonObject();
        if (!object.has("schemaVersion") || !object.has("payload")) {
            return parsed;
        }
        JsonElement schemaVersion = object.get("schemaVersion");
        if (schemaVersion == null || !schemaVersion.isJsonPrimitive()
            || schemaVersion.getAsInt() != SCHEMA_VERSION) {
            throw new IllegalStateException("Unsupported journal schema version");
        }
        readRevision(parsed);
        JsonElement payload = object.get("payload");
        JsonElement hash = object.get("hash");
        if (hash == null || !hash.isJsonPrimitive()) {
            throw new IllegalStateException("Journal integrity check failed");
        }
        String expected = hash.getAsString();
        String actual = StorageSafety.sha256(gson.toJson(payload));
        if (expected.isBlank() || !expected.equals(actual)) {
            throw new IllegalStateException("Journal integrity check failed");
        }
        return payload;
    }

    private Journal readOptional(Path candidate) throws IOException {
        if (!existsNoFollow(candidate)) {
            return null;
        }
        return readRequired(candidate);
    }

    private Journal readRequired(Path candidate) throws IOException {
        requireRegular(candidate, "Journal candidate");
        byte[] bytes = Files.readAllBytes(candidate);
        try {
            String json = new String(bytes, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(json);
            long revision = readRevision(parsed);
            JsonElement payload = decode(parsed);
            return new Journal(bytes, payload, revision, extractEnvelopeMetadata(parsed));
        } catch (RuntimeException failure) {
            throw new IOException("Journal document is invalid: " + candidate, failure);
        }
    }

    private long readRevision(JsonElement parsed) {
        if (!parsed.isJsonObject()) {
            return 0L;
        }
        JsonObject object = parsed.getAsJsonObject();
        if (!object.has("schemaVersion") || !object.has("payload")) {
            return 0L;
        }
        if (!object.has("revision")) {
            throw new MalformedRevisionException("Journal revision is missing");
        }
        JsonElement revision = object.get("revision");
        if (revision == null || !revision.isJsonPrimitive() || !revision.getAsJsonPrimitive().isNumber()) {
            throw new MalformedRevisionException("Journal revision is not an integer");
        }
        String value = revision.getAsString();
        if (value.isBlank()) {
            throw new MalformedRevisionException("Journal revision is blank");
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) < '0' || value.charAt(index) > '9') {
                throw new MalformedRevisionException("Journal revision is not a non-negative integer");
            }
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new MalformedRevisionException("Journal revision is out of range", failure);
        }
    }

    private static JsonObject extractEnvelopeMetadata(JsonElement parsed) {
        if (!parsed.isJsonObject()) {
            return null;
        }
        JsonObject object = parsed.getAsJsonObject();
        if (!object.has("schemaVersion") || !object.has("payload")) {
            return null;
        }
        JsonObject metadata = new JsonObject();
        object.entrySet().forEach(entry -> {
            if (!isKnownEnvelopeField(entry.getKey())) {
                metadata.add(entry.getKey(), entry.getValue().deepCopy());
            }
        });
        return metadata;
    }

    private static boolean isKnownEnvelopeField(String name) {
        return name.equals("schemaVersion") || name.equals("revision") || name.equals("hash") || name.equals("payload");
    }

    private void publishRecovery(byte[] recovered) throws IOException {
        publishRecovery(recovered, null);
    }

    private void publishRecovery(byte[] recovered, IOException originalFailure) throws IOException {
        try {
            StorageSafety.writeBytesAtomicStrict(file, recovered);
        } catch (IOException repairFailure) {
            if (originalFailure != null) {
                repairFailure.addSuppressed(originalFailure);
            }
            throw new IOException("Failed to repair recovered journal: " + file, repairFailure);
        }
    }

    private void preserve(Path candidate, IOException originalFailure, String message) throws IOException {
        try {
            quarantine(candidate);
        } catch (IOException preservationFailure) {
            preservationFailure.addSuppressed(originalFailure);
            throw new IOException(message + ": " + candidate, preservationFailure);
        }
    }

    private void preserveIfPresent(Path candidate, IOException originalFailure, String message) throws IOException {
        if (existsNoFollow(candidate)) {
            preserve(candidate, originalFailure, message);
        }
    }

    private void quarantine(Path candidate) throws IOException {
        requireRegular(candidate, "Journal quarantine candidate");
        Path parent = ensureParent();
        inventoryQuarantineDirectory(parent, quarantine, true);
        byte[] source = Files.readAllBytes(candidate);
        preserveBytes(candidate, source, quarantine, candidate.getFileName().toString() + ".corrupt");
        StorageSafety.forceDirectory(quarantine);
        StorageSafety.forceDirectory(quarantine.getParent());
    }

    private static int inventoryCanonicalArtifacts(Path root, String reservedName, String previousName,
                                                    Path quarantine) throws IOException {
        Path normalizedRoot = MigrationPaths.requireDirectory(root, "journalRoot");
        Path normalizedQuarantine = MigrationPaths.requirePath(quarantine, "journalQuarantine");
        if (!normalizedQuarantine.startsWith(normalizedRoot) || normalizedQuarantine.equals(normalizedRoot)) {
            throw new IOException("Journal quarantine escapes its root: " + normalizedQuarantine);
        }
        Path quarantineContainer = normalizedRoot.resolve(QUARANTINE_DIRECTORY).normalize();
        if (!normalizedQuarantine.startsWith(quarantineContainer) || normalizedQuarantine.getParent() == null
            || !normalizedQuarantine.getParent().equals(quarantineContainer)) {
            throw new IOException("Journal quarantine path is invalid: " + normalizedQuarantine);
        }
        if (existsNoFollow(quarantineContainer)) {
            requireDirectory(quarantineContainer, "Journal quarantine container");
        }
        if (existsNoFollow(normalizedQuarantine)) {
            requireDirectory(normalizedQuarantine, "Journal quarantine directory");
            validateQuarantineEntries(normalizedQuarantine);
        }
        List<Path> artifacts = new ArrayList<>();
        try (var stream = Files.list(normalizedRoot)) {
            for (Path entry : stream.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                String name = entry.getFileName().toString();
                if (name.equals(QUARANTINE_DIRECTORY) || name.equals(reservedName) || name.equals(previousName)) {
                    continue;
                }
                if (isCanonicalTemporaryName(name)) {
                    requireRegular(entry, "Journal atomic temporary file");
                    artifacts.add(entry);
                } else if (isPotentialTemporaryName(name)) {
                    throw new IOException("Journal atomic temporary file name is invalid: " + entry);
                }
            }
        }
        if (artifacts.isEmpty()) {
            return 0;
        }
        inventoryQuarantineDirectory(normalizedRoot, normalizedQuarantine, true);
        for (Path artifact : artifacts) {
            requireRegular(artifact, "Journal atomic temporary file");
            byte[] source = Files.readAllBytes(artifact);
            preserveBytes(artifact, source, normalizedQuarantine, artifact.getFileName().toString());
            StorageSafety.deleteIfExists(artifact);
        }
        StorageSafety.forceDirectory(normalizedQuarantine);
        StorageSafety.forceDirectory(normalizedQuarantine.getParent());
        StorageSafety.forceDirectory(normalizedRoot);
        return artifacts.size();
    }

    private static void inventoryQuarantineDirectory(Path root, Path quarantine, boolean create) throws IOException {
        Path normalizedRoot = MigrationPaths.requireDirectory(root, "journalRoot");
        Path normalizedQuarantine = MigrationPaths.requirePath(quarantine, "journalQuarantine");
        Path container = normalizedRoot.resolve(QUARANTINE_DIRECTORY).normalize();
        if (!normalizedQuarantine.startsWith(container) || normalizedQuarantine.getParent() == null
            || !normalizedQuarantine.getParent().equals(container)) {
            throw new IOException("Journal quarantine path is invalid: " + normalizedQuarantine);
        }
        if (!existsNoFollow(container)) {
            if (!create) {
                return;
            }
            StorageSafety.createDirectoriesNoSymlinks(normalizedRoot, container);
        }
        requireDirectory(container, "Journal quarantine container");
        if (!existsNoFollow(normalizedQuarantine)) {
            if (!create) {
                return;
            }
            StorageSafety.createDirectoriesNoSymlinks(normalizedRoot, normalizedQuarantine);
        }
        requireDirectory(normalizedQuarantine, "Journal quarantine directory");
        validateQuarantineEntries(normalizedQuarantine);
    }

    private static void validateQuarantineEntries(Path quarantine) throws IOException {
        try (var stream = Files.list(quarantine)) {
            for (Path entry : stream.toList()) {
                requireRegular(entry, "Journal quarantine entry");
            }
        }
    }

    private static void preserveBytes(Path source, byte[] bytes, Path destinationDirectory, String baseName) throws IOException {
        for (int attempt = 0; attempt < MAX_PRESERVATION_ATTEMPTS; attempt++) {
            String suffix = attempt == 0 ? "" : "." + attempt;
            Path target = destinationDirectory.resolve(baseName + suffix).normalize();
            if (!target.startsWith(destinationDirectory) || target.getParent() == null
                || !target.getParent().equals(destinationDirectory)) {
                throw new IOException("Journal evidence path is invalid: " + target);
            }
            if (existsNoFollow(target)) {
                requireRegular(target, "Journal evidence target");
                if (Arrays.equals(bytes, Files.readAllBytes(target))) {
                    return;
                }
                continue;
            }
            StorageSafety.copyIfAbsentAtomic(source, target);
            requireRegular(target, "Journal evidence target");
            if (!Arrays.equals(bytes, Files.readAllBytes(target))) {
                throw new IOException("Journal evidence verification failed: " + target);
            }
            StorageSafety.forceDirectory(destinationDirectory);
            return;
        }
        throw new IOException("Unable to reserve collision-safe journal evidence path: " + baseName);
    }

    private Path ensureParent() throws IOException {
        Path parent = file.getParent();
        if (parent == null) {
            throw new IOException("Journal file has no parent: " + file);
        }
        Path existing = parent;
        while (!existsNoFollow(existing)) {
            existing = existing.getParent();
            if (existing == null) {
                throw new IOException("Journal file has no existing parent: " + file);
            }
        }
        requireDirectory(existing, "Journal existing parent");
        StorageSafety.createDirectoriesNoSymlinks(existing, parent);
        requireDirectory(parent, "Journal parent");
        return parent;
    }

    private Path existingParent() throws IOException {
        Path parent = file.getParent();
        if (parent == null || !existsNoFollow(parent)) {
            return null;
        }
        requireDirectory(parent, "Journal parent");
        return parent;
    }

    private static void requireOptionalRegular(Path candidate, String description) throws IOException {
        if (existsNoFollow(candidate)) {
            requireRegular(candidate, description);
        }
    }

    private static void requireRegular(Path candidate, String description) throws IOException {
        if (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " must be a regular non-symbolic-link file: " + candidate);
        }
    }

    private static void requireDirectory(Path candidate, String description) throws IOException {
        if (Files.isSymbolicLink(candidate) || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " must be a non-symbolic-link directory: " + candidate);
        }
    }

    private static boolean existsNoFollow(Path candidate) throws IOException {
        try {
            Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch (NoSuchFileException missing) {
            return false;
        }
    }

    private static boolean isCanonicalTemporaryName(String name) {
        if (name == null) {
            return false;
        }
        if (name.startsWith(ATOMIC_TEMP_PREFIX) && name.endsWith(ATOMIC_COPY_TEMP_SUFFIX)) {
            return isCanonicalUuid(name.substring(ATOMIC_TEMP_PREFIX.length(),
                name.length() - ATOMIC_COPY_TEMP_SUFFIX.length()));
        }
        if (name.startsWith(ATOMIC_TEMP_PREFIX) && name.endsWith(ATOMIC_TEMP_SUFFIX)) {
            return isCanonicalUuid(name.substring(ATOMIC_TEMP_PREFIX.length(),
                name.length() - ATOMIC_TEMP_SUFFIX.length()));
        }
        return false;
    }

    private static boolean isPotentialTemporaryName(String name) {
        return name != null && name.startsWith(ATOMIC_TEMP_PREFIX)
            && (name.endsWith(ATOMIC_TEMP_SUFFIX) || name.endsWith(ATOMIC_COPY_TEMP_SUFFIX));
    }

    private static boolean isCanonicalUuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (index == 8 || index == 13 || index == 18 || index == 23) {
                if (character != '-') {
                    return false;
                }
            } else if (!isLowercaseHex(character)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLowercaseHex(char character) {
        return character >= '0' && character <= '9' || character >= 'a' && character <= 'f';
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record Journal(byte[] bytes, JsonElement payload, long revision, JsonObject metadata) {
    }

    private static final class MalformedRevisionException extends IllegalStateException {
        private MalformedRevisionException(String message) {
            super(message);
        }

        private MalformedRevisionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
