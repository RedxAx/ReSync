package restudio.resync.migration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.resources.AssetFileFormat;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class MigrationReportsPersistenceParticipant implements CloseablePersistenceParticipant,
    PersistenceOwnershipProvider {
    public static final String OWNER = "resync.migration-reports";
    public static final String DIRECTORY = ".migrations";
    public static final String RECIPE_REPORT_FILE = RecipeMigrationReportContract.FILE_NAME;
    public static final String CATALOG_REBIND_REPORT_FILE = "core-catalog-binding-rebind-v1.json";
    private static final String REWRITE_REPORT = "report.json";
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
        Path rewrite = activeRoot.resolve(RewriteGraphsV2.ID);
        if (candidate.equals(rewrite)) {
            return true;
        }
        if (rewrite.equals(candidate.getParent())) {
            return REWRITE_REPORT.equals(candidate.getFileName().toString())
                || isRewriteSourceName(candidate.getFileName().toString());
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
        PersistenceOwnershipIndex.Builder builder = PersistenceOwnershipIndex.builder(context)
            .exactRoot()
            .exact(QUARANTINE_CONTAINER)
            .exact(QUARANTINE_DIRECTORY)
            .directChildLiteral(ReSyncDataFixer.VERSION_FILE)
            .directChildPrefix("data-fix-")
            .directChildLiteral(RECIPE_REPORT_FILE)
            .directChildLiteral(CATALOG_REBIND_REPORT_FILE)
            .directChildAtomicTemp()
            .directChildAtomicTemp(QUARANTINE_DIRECTORY);
        Path rewrite = activeRoot.resolve(RewriteGraphsV2.ID);
        if (Files.exists(rewrite, LinkOption.NOFOLLOW_LINKS)) {
            try {
                validateRewriteGraphReport(rewrite);
                builder.exact(RewriteGraphsV2.ID);
                try (var entries = Files.list(rewrite)) {
                    for (Path file : entries.toList()) {
                        builder.exact(RewriteGraphsV2.ID + "/" + file.getFileName());
                    }
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Rewritten graph migration ownership is invalid", exception);
            }
        }
        return builder.build();
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
                } else if (name.equals(RewriteGraphsV2.ID)) {
                    validateRewriteGraphReport(child);
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
                if (isReportFileName(name) || name.equals(QUARANTINE_CONTAINER) || name.equals(RewriteGraphsV2.ID)) {
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

    public static void writeRewriteGraphReport(ReSyncDataFix.Context context, CatalogBinding binding,
                                               List<Map<String, Object>> items) throws IOException {
        if (context.sourceVersion() != 1 || context.targetVersion() != 2
            || ReSyncDataFixer.installedVersion(context.root()).orElse(-1) != 1) {
            throw new IOException("Rewritten graph report requires its staged version 1 to 2 data fix");
        }
        Map<String, Object> body = Map.of("format", RewriteGraphsV2.ID, "catalogBinding", binding.canonicalText(),
            "items", List.copyOf(items));
        Map<String, Object> report = new LinkedHashMap<>(body);
        report.put("hash", CanonicalJson.sha256(RewriteGraphsV2.ID, body));
        Path directory = context.root().resolve(DIRECTORY).resolve(RewriteGraphsV2.ID);
        MigrationPaths.requireNoSymlinkTraversal(context.root(), directory);
        AtomicFiles.writeNew(directory.resolve(REWRITE_REPORT), CanonicalJson.canonicalize(report).getBytes(StandardCharsets.UTF_8));
        validateRewriteGraphReport(directory);
    }

    private static boolean isRewriteSourceName(String name) {
        if (name == null || !name.endsWith(".source.json")) {
            return false;
        }
        String id = name.substring(0, name.length() - ".source.json".length());
        try {
            return UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static void validateRewriteGraphReport(Path directory) throws IOException {
        MigrationPaths.requireDirectory(directory, "Rewritten graph migration evidence");
        MigrationPaths.requireNoSymlinkTree(directory);
        Path file = directory.resolve(REWRITE_REPORT);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > RewriteGraphsV2.MAX_BYTES) {
            throw new IOException("Rewritten graph migration report is missing or exceeds its recovery bound");
        }
        try {
            String canonical = Files.readString(file, StandardCharsets.UTF_8);
            Object parsed = CanonicalJson.parse(canonical);
            if (!(parsed instanceof Map<?, ?> report)
                || !report.keySet().equals(Set.of("format", "catalogBinding", "items", "hash"))
                || !RewriteGraphsV2.ID.equals(report.get("format"))
                || !canonical.equals(CanonicalJson.canonicalize(parsed))
                || !(report.get("items") instanceof List<?> items) || items.isEmpty()
                || items.size() > RewriteGraphsV2.MAX_GRAPHS) {
                throw new IOException("Rewritten graph migration report is invalid");
            }
            CatalogBinding.parseCanonicalText(Objects.toString(report.get("catalogBinding"), ""));
            Map<String, Object> body = Map.of("format", report.get("format"), "catalogBinding", report.get("catalogBinding"),
                "items", items);
            if (!CanonicalJson.sha256(RewriteGraphsV2.ID, body).equals(report.get("hash"))) {
                throw new IOException("Rewritten graph migration report hash differs");
            }
            Set<String> expected = new HashSet<>(Set.of(REWRITE_REPORT));
            Set<String> resources = new HashSet<>();
            long total = 0L;
            for (Object value : items) {
                if (!(value instanceof Map<?, ?> item)
                    || !item.keySet().equals(Set.of("resource", "sourceRevision", "sourceMutationId", "sourceAssetHash",
                        "sourcePayloadHash", "mutationId", "targetRevision", "targetAssetHash"))) {
                    throw new IOException("Rewritten graph migration item is invalid");
                }
                ServerResourceLocator resource = ServerResourceLocator.parseCanonicalText(Objects.toString(item.get("resource"), ""));
                if (!"restudio.resync".equals(resource.owner().value())
                    || !Set.of("flow", "command", "function").contains(resource.resourceType().value())
                    || !resources.add(resource.canonicalText())) {
                    throw new IOException("Rewritten graph migration resource is invalid");
                }
                long revision = new BigDecimal(item.get("sourceRevision").toString()).longValueExact();
                if (revision < 1 || new BigDecimal(item.get("targetRevision").toString()).longValueExact() != Math.addExact(revision, 1L)) {
                    throw new IOException("Rewritten graph migration revision is invalid");
                }
                UUID sourceMutation = UUID.fromString(Objects.toString(item.get("sourceMutationId"), ""));
                UUID mutation = UUID.fromString(Objects.toString(item.get("mutationId"), ""));
                ContentHash sourceHash = ContentHash.parseCanonicalText(Objects.toString(item.get("sourceAssetHash"), ""));
                ContentHash payloadHash = ContentHash.parseCanonicalText(Objects.toString(item.get("sourcePayloadHash"), ""));
                ContentHash.parseCanonicalText(Objects.toString(item.get("targetAssetHash"), ""));
                UUID expectedMutation = UUID.nameUUIDFromBytes((RewriteGraphsV2.ID + "\n" + resource.canonicalText() + "\n"
                    + revision + "\n" + sourceMutation + "\n" + sourceHash.canonicalText()).getBytes(StandardCharsets.UTF_8));
                if (!expectedMutation.equals(mutation)) {
                    throw new IOException("Rewritten graph migration identity is invalid");
                }
                String name = mutation + ".source.json";
                if (!expected.add(name)) {
                    throw new IOException("Rewritten graph migration source repeats");
                }
                Path source = directory.resolve(name);
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Rewritten graph migration source is missing");
                }
                total = Math.addExact(total, Files.size(source));
                if (total > RewriteGraphsV2.MAX_BYTES) {
                    throw new IOException("Rewritten graph migration evidence exceeds its recovery bound");
                }
                byte[] bytes = Files.readAllBytes(source);
                JsonObject raw = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                if (!sourceHash.canonicalText().equals(StorageSafety.sha256(bytes)) || !AssetFileFormat.verify(raw)
                    || !resource.id().equals(raw.get("id").getAsString())
                    || !resource.resourceType().value().equals(raw.get(AssetFileFormat.RESOURCE_TYPE).getAsString())
                    || raw.get(AssetFileFormat.FORMAT_VERSION).getAsBigDecimal().intValueExact() != AssetFileFormat.CURRENT_FORMAT_VERSION
                    || raw.get(AssetFileFormat.REVISION).getAsBigDecimal().longValueExact() != revision
                    || !sourceMutation.toString().equals(raw.get(AssetFileFormat.MUTATION_ID).getAsString())
                    || !payloadHash.canonicalText().equals(raw.get(AssetFileFormat.CONTENT_HASH).getAsString())) {
                    throw new IOException("Rewritten graph migration source differs from its report");
                }
            }
            try (var entries = Files.list(directory)) {
                List<Path> actual = entries.limit(RewriteGraphsV2.MAX_GRAPHS + 2L).toList();
                if (actual.size() != expected.size() || actual.stream().anyMatch(path -> !expected.contains(path.getFileName().toString()))) {
                    throw new IOException("Rewritten graph migration evidence contains an unknown entry");
                }
            }
        } catch (RuntimeException exception) {
            throw new IOException("Rewritten graph migration evidence is invalid", exception);
        }
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
