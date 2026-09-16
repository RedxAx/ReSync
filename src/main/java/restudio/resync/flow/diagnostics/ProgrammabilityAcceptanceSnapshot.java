package restudio.resync.flow.diagnostics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ProgrammabilityAcceptanceSnapshot {
    public static final int SCHEMA_VERSION = 1;
    public static final String QUARANTINE_DIRECTORY = ".quarantine/programmability-acceptance-temps";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String QUARANTINE_CONTAINER = ".quarantine";
    private static final String FILE_PREFIX = "programmability-acceptance-";
    private static final String FILE_SUFFIX = ".json";
    private static final String ATOMIC_TEMP_SUFFIX = ".tmp";
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss")
        .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter STRICT_TIMESTAMP = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss")
        .withResolverStyle(ResolverStyle.STRICT);

    private ProgrammabilityAcceptanceSnapshot() {
    }

    public static JsonObject create(Map<String, Object> registryDiagnostics, Map<String, Object> readiness, String serverVersion,
                                    String pluginVersion, Instant capturedAt) {
        Map<String, Object> diagnostics = registryDiagnostics != null ? registryDiagnostics : Map.of();
        Map<String, Object> serverReadiness = readiness != null ? readiness : Map.of();
        JsonObject checks = new JsonObject();
        checks.addProperty("inventoryComplete", Boolean.TRUE.equals(diagnostics.get("inventoryComplete")));
        checks.addProperty("registryParity", Boolean.TRUE.equals(diagnostics.get("parity")));
        checks.addProperty("noRejectedDefinitions", number(diagnostics.get("rejectedDefinitions")) == 0L);
        checks.addProperty("noMissingHandlers", empty(diagnostics.get("missingHandlers")));
        checks.addProperty("noMissingOperations", empty(diagnostics.get("missingOperations")));
        checks.addProperty("noMissingCatalogs", empty(diagnostics.get("missingCatalogs")));
        checks.addProperty("clientConnected", number(serverReadiness.get("connectedClients")) > 0L);

        boolean ready = checks.entrySet().stream().allMatch(entry -> entry.getValue().getAsBoolean());
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("schemaVersion", SCHEMA_VERSION);
        snapshot.addProperty("capturedAt", (capturedAt != null ? capturedAt : Instant.now()).toString());
        snapshot.addProperty("serverVersion", serverVersion != null ? serverVersion : "");
        snapshot.addProperty("pluginVersion", pluginVersion != null ? pluginVersion : "");
        snapshot.addProperty("ready", ready);
        snapshot.add("checks", checks);
        snapshot.add("readiness", GSON.toJsonTree(serverReadiness));
        snapshot.add("registryDiagnostics", GSON.toJsonTree(diagnostics));
        return snapshot;
    }

    public static Path write(Path directory, Map<String, Object> registryDiagnostics, Map<String, Object> readiness, String serverVersion,
                             String pluginVersion, Instant capturedAt) throws IOException {
        Path normalizedDirectory = MigrationPaths.requirePath(directory, "directory");
        ensureDirectory(normalizedDirectory);
        recover(normalizedDirectory);
        Instant timestamp = capturedAt != null ? capturedAt : Instant.now();
        Path target = normalizedDirectory.resolve(FILE_PREFIX + FILE_TIMESTAMP.format(timestamp) + FILE_SUFFIX)
            .toAbsolutePath().normalize();
        byte[] content = GSON.toJson(create(registryDiagnostics, readiness, serverVersion, pluginVersion, timestamp))
            .getBytes(StandardCharsets.UTF_8);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            requireRegularFile(target, "Programmability Acceptance Snapshot");
            byte[] existing = Files.readAllBytes(target);
            if (sameBytes(existing, content)) {
                return target;
            }
            throw new IOException("Programmability Acceptance Snapshot Target Collides: " + target);
        } else if (!Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Programmability Acceptance Snapshot Target Could Not Be Inspected: " + target);
        }
        writeAtomic(target, content);
        return target;
    }

    public static void recover(Path directory) throws IOException {
        Path normalizedDirectory = MigrationPaths.requirePath(directory, "directory");
        ensureDirectory(normalizedDirectory);
        recoverAtomicTemps(normalizedDirectory);
    }

    private static void writeAtomic(Path target, byte[] content) throws IOException {
        Path normalizedTarget = MigrationPaths.requirePath(target, "snapshot");
        Path parent = normalizedTarget.getParent();
        if (parent == null) {
            throw new IOException("Programmability Acceptance Snapshot Has No Parent");
        }
        ensureDirectory(parent);
        if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            requireRegularFile(normalizedTarget, "Programmability Acceptance Snapshot");
            throw new FileAlreadyExistsException(normalizedTarget.toString());
        } else if (!Files.notExists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Programmability Acceptance Snapshot Could Not Be Inspected: " + normalizedTarget);
        }
        Path temporary = createAtomicTemp(parent, normalizedTarget.getFileName().toString(), content);
        try {
            verifyBytes(temporary, content, "Programmability Acceptance Snapshot Atomic Temp");
            Files.move(temporary, normalizedTarget, StandardCopyOption.ATOMIC_MOVE);
            requireRegularFile(normalizedTarget, "Programmability Acceptance Snapshot");
            verifyBytes(normalizedTarget, content, "Programmability Acceptance Snapshot");
            StorageSafety.forceDirectory(parent);
        } catch (IOException | RuntimeException exception) {
            throw new IOException("Programmability Acceptance Snapshot Atomic Publication Failed: "
                + normalizedTarget, exception);
        }
    }

    private static Path createAtomicTemp(Path parent, String targetName, byte[] content) throws IOException {
        Path normalizedParent = requireDirectory(parent, "Programmability Acceptance Snapshot Directory");
        for (int attempt = 0; attempt < 128; attempt++) {
            Path temporary = normalizedParent.resolve(targetName + "-" + UUID.randomUUID() + ATOMIC_TEMP_SUFFIX)
                .toAbsolutePath().normalize();
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    int before = buffer.position();
                    int written = channel.write(buffer);
                    if (written <= 0 || buffer.position() == before) {
                        throw new IOException("Programmability Acceptance Snapshot Atomic Temp Write Made No Progress");
                    }
                }
                channel.force(true);
            } catch (FileAlreadyExistsException collision) {
                continue;
            }
            requireAtomicTempFile(temporary);
            return temporary;
        }
        throw new IOException("Unable To Reserve A Programmability Acceptance Snapshot Atomic Temp");
    }

    private static void recoverAtomicTemps(Path root) throws IOException {
        Path normalizedRoot = requireDirectory(root, "Programmability Acceptance Snapshot Directory");
        List<Path> temporaryFiles = new ArrayList<>();
        try (var entries = Files.list(normalizedRoot)) {
            for (Path child : entries.toList()) {
                String name = child.getFileName().toString();
                if (isSnapshotName(name)) {
                    requireRegularFile(child, "Programmability Acceptance Snapshot");
                } else if (name.equals(QUARANTINE_CONTAINER)) {
                    continue;
                } else if (isAtomicTempName(name)) {
                    requireAtomicTempFile(child);
                    temporaryFiles.add(child);
                } else if (name.startsWith(FILE_PREFIX)) {
                    throw new IOException("Programmability Acceptance Snapshot Directory Contains An Unknown Snapshot Entry: "
                        + child);
                } else {
                    continue;
                }
            }
        }
        validateQuarantine(normalizedRoot);
        if (temporaryFiles.isEmpty()) {
            return;
        }
        Path container = normalizedRoot.resolve(QUARANTINE_CONTAINER).toAbsolutePath().normalize();
        if (Files.notExists(container, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(container);
        }
        requireDirectory(container, "Programmability Acceptance Snapshot Quarantine Container");
        Path quarantine = normalizedRoot.resolve(QUARANTINE_DIRECTORY).toAbsolutePath().normalize();
        if (Files.notExists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(quarantine);
        }
        requireDirectory(quarantine, "Programmability Acceptance Snapshot Quarantine");
        for (Path temporary : temporaryFiles) {
            Path destination = quarantine.resolve(temporary.getFileName().toString()).toAbsolutePath().normalize();
            if (!destination.getParent().equals(quarantine)
                || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Programmability Acceptance Snapshot Quarantine Target Collides: " + destination);
            }
        }
        temporaryFiles.sort(Comparator.comparing(path -> path.getFileName().toString()));
        for (Path temporary : temporaryFiles) {
            requireAtomicTempFile(temporary);
            Path destination = quarantine.resolve(temporary.getFileName().toString()).toAbsolutePath().normalize();
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            requireAtomicTempFile(destination);
        }
        StorageSafety.forceDirectory(quarantine);
        StorageSafety.forceDirectory(container);
        StorageSafety.forceDirectory(normalizedRoot);
        validateQuarantine(normalizedRoot);
    }

    private static void validateQuarantine(Path root) throws IOException {
        Path container = root.resolve(QUARANTINE_CONTAINER).toAbsolutePath().normalize();
        if (Files.notExists(container, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        requireDirectory(container, "Programmability Acceptance Snapshot Quarantine Container");
        Path quarantine = root.resolve(QUARANTINE_DIRECTORY).toAbsolutePath().normalize();
        try (var entries = Files.list(container)) {
            for (Path child : entries.toList()) {
                if (!child.equals(quarantine)) {
                    throw new IOException("Programmability Acceptance Snapshot Quarantine Container Contains An Unknown Entry: "
                        + child);
                }
            }
        }
        if (Files.notExists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Programmability Acceptance Snapshot Quarantine Is Missing: " + quarantine);
        }
        requireDirectory(quarantine, "Programmability Acceptance Snapshot Quarantine");
        try (var entries = Files.list(quarantine)) {
            for (Path child : entries.toList()) {
                if (!isAtomicTempName(child.getFileName().toString())) {
                    throw new IOException("Programmability Acceptance Snapshot Quarantine Contains An Unknown Entry: "
                        + child);
                }
                requireAtomicTempFile(child);
            }
        }
    }

    private static void ensureDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        requireDirectory(directory, "Programmability Acceptance Snapshot Directory");
    }

    private static Path requireDirectory(Path directory, String label) throws IOException {
        Path normalized = MigrationPaths.requirePath(directory, label);
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " Must Be A Regular Non-Symbolic-Link Directory: " + normalized);
        }
        return normalized;
    }

    private static void requireRegularFile(Path file, String label) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " Must Be A Regular Non-Symbolic-Link File: " + file);
        }
    }

    private static void requireAtomicTempFile(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Programmability Acceptance Snapshot Atomic Temp Must Be A Regular Non-Symbolic-Link File: "
                + file);
        }
    }

    private static boolean isSnapshotName(String name) {
        if (name == null || !name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) {
            return false;
        }
        String timestamp = name.substring(FILE_PREFIX.length(), name.length() - FILE_SUFFIX.length());
        if (timestamp.length() != 15 || timestamp.charAt(8) != '-') {
            return false;
        }
        for (int index = 0; index < timestamp.length(); index++) {
            if (index != 8 && (timestamp.charAt(index) < '0' || timestamp.charAt(index) > '9')) {
                return false;
            }
        }
        try {
            LocalDateTime.parse(timestamp, STRICT_TIMESTAMP);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean isAtomicTempName(String name) {
        if (name == null || !name.endsWith(ATOMIC_TEMP_SUFFIX)) {
            return false;
        }
        int targetEnd = name.indexOf(FILE_SUFFIX);
        if (targetEnd <= 0) {
            return false;
        }
        int targetLength = targetEnd + FILE_SUFFIX.length();
        if (!isSnapshotName(name.substring(0, targetLength))) {
            return false;
        }
        return isSafeGeneratedToken(name.substring(targetLength, name.length() - ATOMIC_TEMP_SUFFIX.length()));
    }

    private static boolean isSafeGeneratedToken(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= '0' && character <= '9') && !(character >= 'a' && character <= 'z')
                && !(character >= 'A' && character <= 'Z') && character != '_' && character != '-') {
                return false;
            }
        }
        return true;
    }

    private static void verifyBytes(Path file, byte[] expected, String label) throws IOException {
        byte[] actual = Files.readAllBytes(file);
        if (!sameBytes(actual, expected)) {
            throw new IOException(label + " Content Verification Failed: " + file);
        }
    }

    private static boolean sameBytes(byte[] first, byte[] second) {
        return first.length == second.length && Arrays.equals(sha256(first), sha256(second));
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 Is Unavailable", exception);
        }
    }

    private static boolean empty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        return String.valueOf(value).isBlank();
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value != null ? Long.parseLong(String.valueOf(value)) : 0L;
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }
}
