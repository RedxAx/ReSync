package restudio.resync.flow.automation;

import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.RebindablePersistenceParticipant;
import restudio.resync.migration.ResolvedPersistenceOwnership;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

public final class AutomationTaskPersistenceParticipant implements RebindablePersistenceParticipant, ResolvedPersistenceOwnership {
    public static final String OWNER = "resync.jobs.automation-tasks";
    public static final String DIRECTORY = "runtime";
    public static final String FILE_NAME = "automation-tasks.json";
    private static final String PREVIOUS_SUFFIX = ".previous";
    private static final String CORRUPT_SUFFIX = ".corrupt";
    private static final String QUARANTINE_DIRECTORY = ".quarantine";
    private static final String JOURNAL_DIRECTORY = "journals";
    private static final String ATOMIC_TEMP_PREFIX = ".resync-";
    private static final String ATOMIC_TEMP_SUFFIX = ".tmp";
    private static final String ATOMIC_BACKUP_TEMP_SUFFIX = ".backup.tmp";
    private static final int MAX_PRESERVATION_ATTEMPTS = 128;

    private final Path scopeRoot;
    private final AutomationTaskService service;

    public AutomationTaskPersistenceParticipant(Path scopeRoot, AutomationTaskService service) throws IOException {
        this.scopeRoot = MigrationPaths.requirePath(scopeRoot, "scopeRoot");
        this.service = Objects.requireNonNull(service, "service");
        Path expected = persistenceFile(this.scopeRoot);
        Path actual = MigrationPaths.requirePath(service.persistenceRoot(), "automation task persistence file");
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("Automation Task Participant Root Does Not Match Service Persistence Root");
        }
        if (!Files.exists(expected.getParent(), LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(expected.getParent());
        }
        MigrationPaths.requireDirectory(expected.getParent(), "automation task persistence directory");
        validateFile(expected);
        if (!Files.exists(expected, LinkOption.NOFOLLOW_LINKS)) {
            service.flushPersistence();
        }
        validateDurabilityFiles(expected);
    }

    @Override
    public String owner() {
        return OWNER;
    }

    @Override
    public Path root() {
        return MigrationPaths.requirePath(service.persistenceRoot(), "automation task persistence file");
    }

    @Override
    public boolean owns(Path file) {
        Path journal = root();
        Path candidate = MigrationPaths.requirePath(file, "file");
        return ownsResolved(journal, candidate);
    }

    @Override
    public boolean ownsResolved(Path file) {
        return ownsResolved(service.persistenceRoot(), file);
    }

    private static boolean ownsResolved(Path journal, Path candidate) {
        Path previous = journal.resolveSibling(journal.getFileName() + PREVIOUS_SUFFIX);
        Path quarantine = journal.getParent().resolve(QUARANTINE_DIRECTORY).resolve(JOURNAL_DIRECTORY);
        return candidate.equals(journal)
            || candidate.equals(previous)
            || isQuarantineArtifact(candidate, quarantine, journal);
    }

    @Override
    public Path rebindScope() {
        return scopeRoot;
    }

    @Override
    public void flush() throws IOException {
        validateDurabilityFiles(root());
        service.flushPersistence();
        validateDurabilityFiles(root());
    }

    @Override
    public void quiesce() throws IOException {
        validateDurabilityFiles(root());
        service.quiescePersistence();
        validateDurabilityFiles(root());
    }

    @Override
    public void resume() throws IOException {
        validateDurabilityFiles(root());
        service.resumePersistence();
        validateDurabilityFiles(root());
    }

    @Override
    public void rebind(Path activeRoot) throws IOException {
        Path scope = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        Path candidate = persistenceFile(scope);
        Path parent = MigrationPaths.requireDirectory(candidate.getParent(), "automation task persistence directory");
        if (!candidate.startsWith(parent) || candidate.equals(parent)) {
            throw new IOException("Automation Task Rebind Escaped Active Root");
        }
        validateFile(candidate);
        validateDurabilityFiles(candidate);
        service.rebindPersistence(candidate);
        validateDurabilityFiles(service.persistenceRoot());
    }

    @Override
    public void healthCheck() throws IOException {
        validateDurabilityFiles(root());
        service.healthCheckPersistence();
        validateDurabilityFiles(root());
    }

    private static Path persistenceFile(Path scopeRoot) {
        return scopeRoot.resolve(DIRECTORY).resolve(FILE_NAME).toAbsolutePath().normalize();
    }

    private static void validateFile(Path file) throws IOException {
        Path normalized = MigrationPaths.requirePath(file, "automation task persistence file");
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IOException("Automation task persistence file has no parent");
        }
        MigrationPaths.requireDirectory(parent, "automation task persistence directory");
        MigrationPaths.requireNoSymlinkTraversal(parent, normalized);
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)
            && (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized))) {
            throw new IOException("Automation task persistence file must be a regular non-symbolic-link file");
        }
    }

    private static void validateDurabilityFiles(Path journal) throws IOException {
        Path normalizedJournal = MigrationPaths.requirePath(journal, "automation task persistence file");
        validateFile(normalizedJournal);
        validateFile(normalizedJournal.resolveSibling(normalizedJournal.getFileName() + PREVIOUS_SUFFIX));
        Path quarantine = normalizedJournal.getParent().resolve(QUARANTINE_DIRECTORY).resolve(JOURNAL_DIRECTORY);
        if (!Files.exists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        MigrationPaths.requireDirectory(quarantine, "automation task quarantine directory");
        try (var files = Files.list(quarantine)) {
            for (Path candidate : files.toList()) {
                if (candidate.getFileName() == null) {
                    continue;
                }
                String name = candidate.getFileName().toString();
                if (!isPotentialQuarantineArtifact(name, normalizedJournal)) {
                    continue;
                }
                if (!isQuarantineArtifact(candidate, quarantine, normalizedJournal)) {
                    throw new IOException("Unexpected automation task quarantine file: " + candidate);
                }
                validateFile(candidate);
            }
        }
    }

    private static boolean isQuarantineArtifact(Path candidate, Path quarantine, Path journal) {
        if (candidate.getParent() == null || !candidate.getParent().equals(quarantine) || candidate.getFileName() == null) {
            return false;
        }
        String name = candidate.getFileName().toString();
        String journalName = journal.getFileName().toString();
        return isPreservedName(name, journalName + CORRUPT_SUFFIX)
            || isPreservedName(name, journalName + PREVIOUS_SUFFIX + CORRUPT_SUFFIX)
            || isAtomicEvidenceName(name);
    }

    private static boolean isPotentialQuarantineArtifact(String name, Path journal) {
        String journalName = journal.getFileName().toString();
        return name.startsWith(journalName + ".")
            || name.startsWith(ATOMIC_TEMP_PREFIX);
    }

    private static boolean isPreservedName(String name, String baseName) {
        if (name.equals(baseName)) {
            return true;
        }
        String prefix = baseName + ".";
        if (!name.startsWith(prefix) || name.length() > prefix.length() + 3) {
            return false;
        }
        String attempt = name.substring(prefix.length());
        if (attempt.isEmpty() || attempt.charAt(0) == '0') {
            return false;
        }
        for (int index = 0; index < attempt.length(); index++) {
            if (attempt.charAt(index) < '0' || attempt.charAt(index) > '9') {
                return false;
            }
        }
        try {
            int value = Integer.parseInt(attempt);
            return value > 0 && value < MAX_PRESERVATION_ATTEMPTS;
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private static boolean isAtomicEvidenceName(String name) {
        return isAtomicEvidenceName(name, ATOMIC_BACKUP_TEMP_SUFFIX)
            || isAtomicEvidenceName(name, ATOMIC_TEMP_SUFFIX);
    }

    private static boolean isAtomicEvidenceName(String name, String suffix) {
        int baseLength = ATOMIC_TEMP_PREFIX.length() + 36 + suffix.length();
        if (name.length() < baseLength) {
            return false;
        }
        String baseName = name.substring(0, baseLength);
        if (!baseName.startsWith(ATOMIC_TEMP_PREFIX) || !baseName.endsWith(suffix)) {
            return false;
        }
        String identifier = baseName.substring(ATOMIC_TEMP_PREFIX.length(), baseName.length() - suffix.length());
        try {
            return UUID.fromString(identifier).toString().equals(identifier) && isPreservedName(name, baseName);
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }
}
