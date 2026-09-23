package restudio.resync.migration;

import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class MigrationReportsPersistenceParticipant implements CloseablePersistenceParticipant,
    PersistenceOwnershipProvider {
    public static final String OWNER = "resync.migration-reports";
    public static final String DIRECTORY = ".migrations";
    public static final String RECIPE_REPORT_FILE = RecipeMigrationReportContract.FILE_NAME;
    public static final String CATALOG_REBIND_REPORT_FILE = "core-catalog-binding-rebind-v1.json";
    public static final String QUARANTINE_DIRECTORY = ".quarantine/migration-report-temps";
    private static final String QUARANTINE_CONTAINER = ".quarantine";
    private static final String ATOMIC_TEMP_PREFIX = ".resync-";
    private static final String ATOMIC_TEMP_SUFFIX = ".tmp";
    private static final int UUID_LENGTH = 36;
    private static final List<String> REPORT_FILES = List.of(
        ReSyncDataFixer.VERSION_FILE,
        RECIPE_REPORT_FILE,
        CATALOG_REBIND_REPORT_FILE);

    private final Path scopeRoot;
    private final ReSyncJsonResourceStorage storage;
    private volatile Path activeRoot;
    private volatile boolean admitted;
    private boolean admissionCreated;
    private boolean quiesced;
    private volatile boolean closed;

    public MigrationReportsPersistenceParticipant(Path dataRoot) {
        this(dataRoot, null);
    }

    public MigrationReportsPersistenceParticipant(Path dataRoot, ReSyncJsonResourceStorage storage) {
        try {
            this.scopeRoot = MigrationPaths.requireDirectory(dataRoot, "dataRoot");
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("Migration reports dataRoot is invalid", exception);
        }
        this.storage = storage;
        if (storage != null && !scopeRoot.equals(storage.getScopePath())) {
            throw new IllegalArgumentException("Migration reports scope does not match JSON resource storage scope");
        }
        this.activeRoot = reportRoot(scopeRoot);
        try {
            if (Files.exists(activeRoot, LinkOption.NOFOLLOW_LINKS)) {
                validateTree(activeRoot);
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("Migration reports root is invalid", exception);
        }
        if (storage != null) {
            storage.bindMigrationReportsAuthority(this);
        }
    }

    @Override
    public String owner() {
        return OWNER;
    }

    @Override
    public Path root() {
        return activeRoot;
    }

    @Override
    public Path rebindScope() {
        return scopeRoot;
    }

    @Override
    public synchronized boolean owns(Path file) {
        Path candidate = MigrationPaths.requirePath(file, "file");
        Path quarantineContainer = quarantineContainer(activeRoot);
        Path quarantineRoot = quarantineRoot(activeRoot);
        if (candidate.equals(activeRoot) || candidate.equals(quarantineContainer) || candidate.equals(quarantineRoot)) {
            return true;
        }
        if (candidate.getParent() != null && candidate.getParent().equals(activeRoot)) {
            String name = candidate.getFileName().toString();
            return isReportFileName(name) || isAtomicTempName(name);
        }
        return candidate.getParent() != null && candidate.getParent().equals(quarantineRoot)
            && isAtomicTempName(candidate.getFileName().toString());
    }

    @Override
    public PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context) {
        return PersistenceOwnershipIndex.builder(context)
            .exactRoot()
            .exact(QUARANTINE_CONTAINER)
            .exact(QUARANTINE_DIRECTORY)
            .directChildLiteral(ReSyncDataFixer.VERSION_FILE)
            .directChildPrefix("data-fix-")
            .directChildLiteral(RECIPE_REPORT_FILE)
            .directChildLiteral(CATALOG_REBIND_REPORT_FILE)
            .directChildAtomicTemp()
            .directChildAtomicTemp(QUARANTINE_DIRECTORY)
            .build();
    }

    public synchronized void admit() throws IOException {
        requireOpen();
        if (admitted) {
            return;
        }
        boolean created = false;
        if (Files.exists(activeRoot, LinkOption.NOFOLLOW_LINKS)) {
            MigrationPaths.requireDirectory(activeRoot, "migration reports root");
        } else {
            MigrationPaths.requireWritableParent(activeRoot);
            Files.createDirectory(activeRoot);
            created = true;
        }
        try {
            validateTree(activeRoot);
        } catch (IOException | RuntimeException exception) {
            if (created) {
                try {
                    Files.deleteIfExists(activeRoot);
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            throw exception;
        }
        admitted = true;
        admissionCreated = created;
    }

    public synchronized void rollbackAdmission() throws IOException {
        if (!admitted || !admissionCreated || !Files.exists(activeRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(activeRoot) || !Files.isDirectory(activeRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Migration reports admission root is no longer a directory");
        }
        try (var entries = Files.list(activeRoot)) {
            if (entries.findAny().isPresent()) {
                return;
            }
        }
        Files.deleteIfExists(activeRoot);
        admitted = false;
        admissionCreated = false;
    }

    @Override
    public synchronized void flush() throws IOException {
        requireAdmitted();
        if (quiesced) {
            throw new IOException("Migration reports are quiesced");
        }
        validateTree(activeRoot);
        if (storage != null && activeRoot.getParent().equals(storage.getScopePath())) {
            storage.flushMigrationReports(this, activeRoot);
        }
        forceTree(activeRoot);
        validateTree(activeRoot);
    }

    @Override
    public synchronized void quiesce() throws IOException {
        requireAdmitted();
        if (quiesced) {
            return;
        }
        validateTree(activeRoot);
        quiesced = true;
    }

    @Override
    public synchronized void resume() throws IOException {
        requireAdmitted();
        validateTree(activeRoot);
        quiesced = false;
    }

    @Override
    public synchronized void rebind(Path activeScope) throws IOException {
        requireAdmitted();
        if (!quiesced) {
            throw new IOException("Migration reports must be quiesced before rebind");
        }
        Path candidateScope = MigrationPaths.requireDirectory(activeScope, "activeRoot");
        Path candidateRoot = reportRoot(candidateScope);
        validateTree(candidateRoot);
        activeRoot = candidateRoot;
        admissionCreated = false;
    }

    @Override
    public synchronized void healthCheck() throws IOException {
        requireAdmitted();
        validateTree(activeRoot);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        if (!admitted) {
            closed = true;
            return;
        }
        if (!quiesced) {
            flush();
            quiesced = true;
        }
        validateTree(activeRoot);
        closed = true;
    }

    public synchronized void shutdown() throws IOException {
        close();
    }

    public synchronized void writeCatalogRebindReport(String canonicalReport) throws IOException {
        requireAdmitted();
        if (quiesced) {
            throw new IOException("Migration reports are quiesced");
        }
        writeCatalogRebindRecoveryReport(canonicalReport);
    }

    public synchronized void writeCatalogRebindRecoveryReport(String canonicalReport) throws IOException {
        requireAdmitted();
        String report = Objects.requireNonNull(canonicalReport, "Core catalog rebind report is required");
        validateCatalogRebindReport(report);
        Path file = activeRoot.resolve(CATALOG_REBIND_REPORT_FILE).toAbsolutePath().normalize();
        AtomicFiles.write(file, report.getBytes(StandardCharsets.UTF_8));
        AtomicFiles.force(file);
        StorageSafety.forceDirectory(activeRoot);
    }

    public synchronized String readCatalogRebindReport() throws IOException {
        requireAdmitted();
        Path file = activeRoot.resolve(CATALOG_REBIND_REPORT_FILE).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Core catalog rebind report is missing");
        }
        String report = Files.readString(file, StandardCharsets.UTF_8);
        validateCatalogRebindReport(report);
        return report;
    }

    public boolean isAdmitted() {
        return admitted && !closed;
    }

    public synchronized boolean isQuiesced() {
        return quiesced;
    }

    public boolean isClosed() {
        return closed;
    }

    private void requireOpen() throws IOException {
        if (closed) {
            throw new IOException("Migration reports participant is shut down");
        }
    }

    private void requireAdmitted() throws IOException {
        requireOpen();
        if (!admitted) {
            throw new IOException("Migration reports participant has not been admitted");
        }
    }

    private static Path reportRoot(Path scope) {
        Path normalizedScope = MigrationPaths.requirePath(scope, "dataRoot");
        Path root = normalizedScope.resolve(DIRECTORY).toAbsolutePath().normalize();
        if (!root.startsWith(normalizedScope) || root.equals(normalizedScope)) {
            throw new IllegalArgumentException("Migration reports root escaped dataRoot");
        }
        return root;
    }

    private static Path reportFile(Path root) {
        return root.resolve(RECIPE_REPORT_FILE).toAbsolutePath().normalize();
    }

    private static void validateTree(Path root) throws IOException {
        MigrationPaths.requireDirectory(root, "migration reports root");
        MigrationPaths.requireNoSymlinkTree(root);
        recoverAtomicTemps(root);
        MigrationPaths.requireNoSymlinkTree(root);
        try (var entries = Files.list(root)) {
            List<Path> children = entries.toList();
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (name.equals(RECIPE_REPORT_FILE)) {
                    if (Files.isSymbolicLink(child) || !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Recipe migration report must be a regular non-symbolic-link file");
                    }
                    RecipeMigrationReportContract.read(child);
                } else if (name.equals(ReSyncDataFixer.VERSION_FILE)) {
                    if (ReSyncDataFixer.installedVersion(root.getParent()).isEmpty()) {
                        throw new IOException("ReSync data version is missing");
                    }
                } else if (ReSyncDataFixer.isDataFixReportName(name)) {
                    ReSyncDataFixer.validateReport(child);
                } else if (name.equals(CATALOG_REBIND_REPORT_FILE)) {
                    if (Files.isSymbolicLink(child) || !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Core catalog rebind report must be a regular non-symbolic-link file");
                    }
                    validateCatalogRebindReport(Files.readString(child));
                } else if (name.equals(QUARANTINE_CONTAINER)) {
                    validateQuarantine(root);
                } else {
                    throw new IOException("Migration reports root contains an unknown entry");
                }
            }
        }
    }

    private static void recoverAtomicTemps(Path root) throws IOException {
        List<Path> temporaryFiles = new ArrayList<>();
        try (var entries = Files.list(root)) {
            for (Path child : entries.toList()) {
                String name = child.getFileName().toString();
                if (isReportFileName(name) || name.equals(QUARANTINE_CONTAINER)) {
                    continue;
                }
                if (!isAtomicTempName(name)) {
                    throw new IOException("Migration reports root contains an unknown entry: " + child);
                }
                requireAtomicTempFile(child);
                temporaryFiles.add(child);
            }
        }
        validateQuarantine(root);
        if (temporaryFiles.isEmpty()) {
            return;
        }
        Path container = quarantineContainer(root);
        if (Files.notExists(container, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(container);
        }
        if (Files.isSymbolicLink(container) || !Files.isDirectory(container, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Migration report quarantine container is invalid");
        }
        try (var entries = Files.list(container)) {
            for (Path child : entries.toList()) {
                if (!child.getFileName().toString().equals(quarantineRoot(root).getFileName().toString())) {
                    throw new IOException("Migration report quarantine container contains an unknown entry: " + child);
                }
            }
        }
        Path quarantine = quarantineRoot(root);
        if (Files.notExists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(quarantine);
        }
        if (Files.isSymbolicLink(quarantine) || !Files.isDirectory(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Migration report quarantine root is invalid");
        }
        for (Path temporary : temporaryFiles) {
            Path target = quarantine.resolve(temporary.getFileName().toString()).toAbsolutePath().normalize();
            if (!target.startsWith(quarantine) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Migration report quarantine target collides: " + target);
            }
        }
        for (Path temporary : temporaryFiles) {
            Path target = quarantine.resolve(temporary.getFileName().toString()).toAbsolutePath().normalize();
            try {
                requireAtomicTempFile(temporary);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException exception) {
                throw new IOException("Failed to quarantine migration report atomic temp: " + temporary, exception);
            }
        }
        forceTree(quarantine);
        StorageSafety.forceDirectory(container);
        StorageSafety.forceDirectory(root);
        validateQuarantine(root);
    }

    private static void validateQuarantine(Path root) throws IOException {
        Path container = quarantineContainer(root);
        if (Files.notExists(container, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(container) || !Files.isDirectory(container, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Migration report quarantine container is invalid");
        }
        Path quarantine = quarantineRoot(root);
        try (var entries = Files.list(container)) {
            for (Path child : entries.toList()) {
                if (!child.equals(quarantine)) {
                    throw new IOException("Migration report quarantine container contains an unknown entry: " + child);
                }
            }
        }
        if (Files.notExists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Migration report quarantine root is missing");
        }
        if (Files.isSymbolicLink(quarantine) || !Files.isDirectory(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Migration report quarantine root is invalid");
        }
        try (var entries = Files.list(quarantine)) {
            for (Path child : entries.toList()) {
                if (!isAtomicTempName(child.getFileName().toString())) {
                    throw new IOException("Migration report quarantine contains an unknown entry: " + child);
                }
                requireAtomicTempFile(child);
            }
        }
    }

    private static void requireAtomicTempFile(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Migration report atomic temp must be a regular non-symbolic-link file: " + file);
        }
    }

    private static boolean isReportFileName(String name) {
        return REPORT_FILES.contains(name) || ReSyncDataFixer.isDataFixReportName(name);
    }

    private static void validateCatalogRebindReport(String report) throws IOException {
        try {
            Object parsed = CanonicalJson.parse(report);
            String canonical = CanonicalJson.canonicalize(parsed);
            if (!canonical.equals(report)) {
                throw new IOException("Core catalog rebind report is not canonical");
            }
            if (!(parsed instanceof Map<?, ?> map)
                || !map.keySet().equals(Set.of("format", "migrationId", "manifestHash", "planHash",
                    "sourceBinding", "targetBinding", "items"))
                || !"core-catalog-binding-rebind-report-v1".equals(map.get("format"))
                || !"core-catalog-binding-rebind-v1".equals(map.get("migrationId"))
                || !(map.get("items") instanceof List<?>)) {
                throw new IOException("Core catalog rebind report format is invalid");
            }
            new ContentHash(Objects.toString(map.get("manifestHash"), ""));
            new ContentHash(Objects.toString(map.get("planHash"), ""));
            CatalogBinding.parseCanonicalText(Objects.toString(map.get("sourceBinding"), ""));
            CatalogBinding.parseCanonicalText(Objects.toString(map.get("targetBinding"), ""));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Core catalog rebind report is invalid", exception);
        }
    }

    private static boolean isAtomicTempName(String name) {
        if (name == null || name.length() != ATOMIC_TEMP_PREFIX.length() + UUID_LENGTH
            + ATOMIC_TEMP_SUFFIX.length() || !name.startsWith(ATOMIC_TEMP_PREFIX)
            || !name.endsWith(ATOMIC_TEMP_SUFFIX)) {
            return false;
        }
        int uuidStart = ATOMIC_TEMP_PREFIX.length();
        for (int index = 0; index < UUID_LENGTH; index++) {
            char character = name.charAt(uuidStart + index);
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

    private static Path quarantineContainer(Path root) {
        return root.resolve(QUARANTINE_CONTAINER).toAbsolutePath().normalize();
    }

    private static Path quarantineRoot(Path root) {
        return root.resolve(QUARANTINE_DIRECTORY).toAbsolutePath().normalize();
    }

    private static void forceTree(Path root) throws IOException {
        try (var entries = Files.list(root)) {
            for (Path child : entries.toList()) {
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    forceTree(child);
                } else {
                    AtomicFiles.force(child);
                }
            }
        }
        StorageSafety.forceDirectory(root);
    }
}
