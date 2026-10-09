package restudio.resync.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

public final class FreshRootProvenance {
    private static final String FORMAT = "fresh-root-provenance-v1";
    private static final String FILE_NAME = "fresh-root-provenance-v1";
    private final Path proofPath;
    private final Phase phase;
    private final Path sourceRoot;
    private final Path activeRoot;
    private final String sourceHash;
    private final String activeHash;
    private final String originHash;
    private final String artifactHash;
    private final String proofHash;

    private FreshRootProvenance(Path proofPath, Phase phase, Path sourceRoot, Path activeRoot, String sourceHash,
                                String activeHash, String originHash, String artifactHash, String proofHash) {
        this.proofPath = proofPath;
        this.phase = phase;
        this.sourceRoot = sourceRoot;
        this.activeRoot = activeRoot;
        this.sourceHash = sourceHash;
        this.activeHash = activeHash;
        this.originHash = originHash;
        this.artifactHash = artifactHash;
        this.proofHash = proofHash;
    }

    static FreshRootProvenance initialize(Path sourceRoot, Path coordinationRoot) throws IOException {
        Path source = MigrationPaths.requirePath(sourceRoot, "dataRoot");
        Path coordination = MigrationPaths.requirePath(coordinationRoot, "coordinationRoot");
        MigrationPaths.requireDistinctRoots(source, coordination);
        MigrationPaths.requireWritableParent(coordination);
        Files.createDirectories(coordination);
        if (Files.isSymbolicLink(coordination) || !Files.isDirectory(coordination, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("coordinationRoot Must Be A Non-Symbolic-Link Directory");
        }
        Path proof = coordination.resolve(FILE_NAME).toAbsolutePath().normalize();
        if (Files.exists(proof, LinkOption.NOFOLLOW_LINKS)) {
            FreshRootProvenance existing = read(proof);
            if (!existing.sourceRoot.equals(source)) {
                throw new MigrationException("Fresh Root Provenance Does Not Match The ReSync Data Root");
            }
            if (existing.phase == Phase.CONSUMED) {
                if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                    MigrationPaths.requireDirectory(source, "dataRoot");
                }
                return null;
            }
            if (existing.phase == Phase.ACTIVE_EMPTY) {
                MigrationPaths.requireDirectory(source, "dataRoot");
                return existing;
            }
            ensureSource(existing, source);
            return existing.phase == Phase.SOURCE_EMPTY ? existing : write(proof, Phase.SOURCE_EMPTY, source, null,
                TreeDigest.of(source), "", existing.proofHash, "");
        }
        if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            MigrationPaths.requireDirectory(source, "dataRoot");
            if (!empty(coordination) || !FreshInstallInputs.accepts(source)) {
                return null;
            }
            return write(proof, Phase.SOURCE_EMPTY, source, null, TreeDigest.of(source), "", "", "");
        }
        if (!empty(coordination)) {
            Path pointer = coordination.resolve("restore-control").resolve("active-root");
            if (Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            throw new MigrationException("ReSync Data Root Is Missing And No Active Root Is Recorded: " + source);
        }
        FreshRootProvenance beginning = write(proof, Phase.SOURCE_ABSENT, source, null, "", "", "", "");
        Files.createDirectory(source);
        ensureEmpty(source, "Fresh ReSync Data Root");
        return write(proof, Phase.SOURCE_EMPTY, source, null, TreeDigest.of(source), "", beginning.proofHash, "");
    }

    FreshRootProvenance bind(Path preparedRoot) throws IOException {
        Path active = MigrationPaths.requireDirectory(preparedRoot, "activeRoot");
        if (phase == Phase.ACTIVE_EMPTY) {
            if (!activeRoot.equals(active)) {
                throw new MigrationException("Fresh Root Provenance Does Not Match The Prepared Active Root");
            }
            return this;
        }
        if (phase != Phase.SOURCE_EMPTY) {
            throw new MigrationException("Fresh Root Provenance Is Not Ready For Active Root Binding");
        }
        ensureInputs(sourceRoot, "Fresh ReSync Data Root");
        ensureInputs(active, "Fresh ReSync Active Root");
        String currentSourceHash = TreeDigest.of(sourceRoot);
        String currentActiveHash = TreeDigest.of(active);
        if (!currentSourceHash.equals(sourceHash) || !currentActiveHash.equals(sourceHash)) {
            throw new MigrationException("Fresh ReSync Root Identity Changed During Bootstrap");
        }
        return write(proofPath, Phase.ACTIVE_EMPTY, sourceRoot, active, sourceHash, currentActiveHash, proofHash, "");
    }

    public void verify(Path coordinationRoot, Path expectedActiveRoot) throws IOException {
        Path coordination = MigrationPaths.requireDirectory(coordinationRoot, "coordinationRoot");
        Path active = MigrationPaths.requireDirectory(expectedActiveRoot, "activeRoot");
        if (!proofPath.equals(coordination.resolve(FILE_NAME).toAbsolutePath().normalize()) || phase != Phase.ACTIVE_EMPTY
            || !activeRoot.equals(active)) {
            throw new MigrationException("Fresh Root Provenance Does Not Authorize This Active Root");
        }
        FreshRootProvenance current = read(proofPath);
        if (current.phase != Phase.ACTIVE_EMPTY || !current.proofHash.equals(proofHash)
            || !current.sourceRoot.equals(sourceRoot) || !current.activeRoot.equals(activeRoot)
            || !current.sourceHash.equals(sourceHash) || !current.activeHash.equals(activeHash)) {
            throw new MigrationException("Fresh Root Provenance Changed Before Consumption");
        }
        ensureInputs(sourceRoot, "Fresh ReSync Data Root");
        ensureFreshActive(activeRoot);
        if (!TreeDigest.of(sourceRoot).equals(sourceHash)
            || !TreeDigest.freshInputsOf(activeRoot).equals(activeHash)) {
            throw new MigrationException("Fresh Root Provenance No Longer Matches Its Initial Persistence Roots");
        }
    }

    public void consume(String artifactHash) throws IOException {
        String artifact = requireHash(artifactHash, "artifactHash");
        Path coordination = Objects.requireNonNull(proofPath.getParent(), "proofPath parent");
        verify(coordination, activeRoot);
        write(proofPath, Phase.CONSUMED, sourceRoot, activeRoot, sourceHash, activeHash, proofHash, artifact);
    }

    public static void verifyConsumed(Path coordinationRoot, Path expectedActiveRoot, String expectedOriginHash,
                                      String expectedArtifactHash) throws IOException {
        Path active = MigrationPaths.requireDirectory(expectedActiveRoot, "activeRoot");
        Path genesis = verifyConsumedInstallation(coordinationRoot, expectedOriginHash, expectedArtifactHash);
        if (!genesis.equals(active) || !genesis.toRealPath().equals(active.toRealPath())) {
            throw new MigrationException("Consumed Fresh Root Provenance Does Not Match The Active Root And Artifact");
        }
    }

    public static Path verifyConsumedInstallation(Path coordinationRoot, String expectedOriginHash,
                                                   String expectedArtifactHash) throws IOException {
        Path coordination = MigrationPaths.requireDirectory(coordinationRoot, "coordinationRoot");
        String origin = requireHash(expectedOriginHash, "originHash");
        String artifact = requireHash(expectedArtifactHash, "artifactHash");
        Path proof = coordination.resolve(FILE_NAME).toAbsolutePath().normalize();
        FreshRootProvenance current = read(proof);
        if (!current.proofPath.equals(proof) || current.phase != Phase.CONSUMED || !current.sourceHash.equals(current.activeHash)
            || !current.originHash.equals(origin) || !current.artifactHash.equals(artifact)) {
            throw new MigrationException("Consumed Fresh Root Provenance Does Not Match The Active Root And Artifact");
        }
        Path genesis = MigrationPaths.requireDirectory(current.activeRoot, "Fresh installation root");
        MigrationPaths.requireNoSymlinkTraversal(coordination, genesis);
        return genesis;
    }

    public static boolean recordsCurrentInstallation(Path coordinationRoot, Path expectedSourceRoot) throws IOException {
        Path coordination = MigrationPaths.requireDirectory(coordinationRoot, "coordinationRoot");
        Path source = MigrationPaths.requirePath(expectedSourceRoot, "dataRoot");
        Path proof = coordination.resolve(FILE_NAME);
        if (!Files.exists(proof, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        FreshRootProvenance current = read(proof);
        if (!current.sourceRoot.equals(source)) {
            throw new MigrationException("Fresh Root Provenance Does Not Match The ReSync Data Root");
        }
        return current.phase == Phase.ACTIVE_EMPTY || current.phase == Phase.CONSUMED;
    }

    public Path proofPath() {
        return proofPath;
    }

    public Path sourceRoot() {
        return sourceRoot;
    }

    public Path activeRoot() {
        return activeRoot;
    }

    public String sourceHash() {
        return sourceHash;
    }

    public String activeHash() {
        return activeHash;
    }

    public String proofHash() {
        return proofHash;
    }

    private static void ensureSource(FreshRootProvenance provenance, Path source) throws IOException {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(source);
        }
        ensureInputs(source, "Fresh ReSync Data Root");
        if (!provenance.sourceHash.isEmpty() && !TreeDigest.of(source).equals(provenance.sourceHash)) {
            throw new MigrationException("Fresh ReSync Data Root Changed After Provenance Was Recorded");
        }
    }

    private static void ensureEmpty(Path root, String name) throws IOException {
        Path directory = MigrationPaths.requireDirectory(root, "root");
        MigrationPaths.requireNoSymlinkTree(directory);
        if (!empty(directory)) {
            throw new MigrationException(name + " Must Be Entirely Empty Before Persistence Writers Start");
        }
    }

    private static void ensureInputs(Path root, String name) throws IOException {
        if (!FreshInstallInputs.accepts(root)) {
            throw new MigrationException(name + " Contains Data Other Than Fresh Configuration Or Empty Directories");
        }
    }

    private static void ensureFreshActive(Path root) throws IOException {
        Path directory = MigrationPaths.requireDirectory(root, "activeRoot");
        MigrationPaths.requireNoSymlinkTree(directory);
        List<Path> entries;
        try (var children = Files.list(directory)) {
            entries = children.toList();
        }
        Path versionDirectory = directory.resolve(ReSyncDataFixer.VERSION_DIRECTORY);
        for (Path entry : entries) {
            if (!entry.equals(versionDirectory) && !FreshInstallInputs.acceptsEntry(entry)) {
                throw new MigrationException("Fresh ReSync Active Root Contains Unowned Data");
            }
        }
        if (!Files.exists(versionDirectory, LinkOption.NOFOLLOW_LINKS)) return;
        if (ReSyncDataFixer.installedVersion(directory).isEmpty()) {
            throw new MigrationException("Fresh ReSync Active Root Has Invalid Migration Data");
        }
        try (var versionEntries = Files.list(versionDirectory)) {
            List<Path> files = versionEntries.toList();
            if (files.size() != 1 || !files.getFirst().equals(ReSyncDataFixer.versionPath(directory))) {
                throw new MigrationException("Fresh ReSync Active Root Contains Unowned Migration Data");
            }
        }
    }

    private static boolean empty(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }

    private static FreshRootProvenance write(Path proofPath, Phase phase, Path sourceRoot, Path activeRoot,
                                             String sourceHash, String activeHash, String originHash,
                                             String artifactHash) throws IOException {
        String canonical = canonical(phase, sourceRoot, activeRoot, sourceHash, activeHash, originHash, artifactHash);
        String proofHash = MigrationCanonical.sha256(canonical);
        AtomicFiles.write(proofPath, (canonical + "proofHash=" + proofHash + "\n").getBytes(StandardCharsets.UTF_8));
        return new FreshRootProvenance(proofPath, phase, sourceRoot, activeRoot, sourceHash, activeHash, originHash,
            artifactHash, proofHash);
    }

    private static FreshRootProvenance read(Path proofPath) throws IOException {
        if (Files.isSymbolicLink(proofPath) || !Files.isRegularFile(proofPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Fresh Root Provenance Must Be A Regular Non-Symbolic-Link File");
        }
        String content = Files.readString(proofPath, StandardCharsets.UTF_8);
        List<String> lines = content.lines().toList();
        if (lines.size() != 9 || !lines.getFirst().equals("format=" + FORMAT)) {
            throw new MigrationException("Fresh Root Provenance Format Is Invalid");
        }
        Phase phase;
        try {
            phase = Phase.valueOf(value(lines.get(1), "phase"));
        } catch (IllegalArgumentException exception) {
            throw new MigrationException("Fresh Root Provenance Phase Is Invalid", exception);
        }
        Path source = decodePath(value(lines.get(2), "source"), "source");
        String activeValue = value(lines.get(3), "active");
        Path active = activeValue.isEmpty() ? null : decodePath(activeValue, "active");
        String sourceHash = value(lines.get(4), "sourceHash");
        String activeHash = value(lines.get(5), "activeHash");
        String originHash = value(lines.get(6), "originHash");
        String artifactHash = value(lines.get(7), "artifactHash");
        String proofHash = requireHash(value(lines.get(8), "proofHash"), "proofHash");
        String canonical = canonical(phase, source, active, sourceHash, activeHash, originHash, artifactHash);
        if (!content.equals(canonical + "proofHash=" + proofHash + "\n")
            || !MigrationCanonical.sha256(canonical).equals(proofHash)) {
            throw new MigrationException("Fresh Root Provenance Hash Is Invalid");
        }
        validatePhase(phase, active, sourceHash, activeHash, originHash, artifactHash);
        return new FreshRootProvenance(proofPath, phase, source, active, sourceHash, activeHash, originHash,
            artifactHash, proofHash);
    }

    private static void validatePhase(Phase phase, Path activeRoot, String sourceHash, String activeHash,
                                      String originHash, String artifactHash) throws MigrationException {
        if (phase == Phase.SOURCE_ABSENT && (activeRoot != null || !sourceHash.isEmpty() || !activeHash.isEmpty()
            || !originHash.isEmpty() || !artifactHash.isEmpty())) {
            throw new MigrationException("Fresh Root Absent Provenance Contains Unexpected State");
        }
        if (phase == Phase.SOURCE_EMPTY && (activeRoot != null || sourceHash.isEmpty() || !activeHash.isEmpty()
            || originHash.isEmpty() || !artifactHash.isEmpty())) {
            throw new MigrationException("Fresh Root Source Provenance Is Incomplete");
        }
        if (phase == Phase.ACTIVE_EMPTY && (activeRoot == null || sourceHash.isEmpty() || activeHash.isEmpty()
            || originHash.isEmpty() || !artifactHash.isEmpty())) {
            throw new MigrationException("Fresh Root Active Provenance Is Incomplete");
        }
        if (phase == Phase.CONSUMED && (activeRoot == null || sourceHash.isEmpty() || activeHash.isEmpty()
            || originHash.isEmpty() || artifactHash.isEmpty())) {
            throw new MigrationException("Fresh Root Consumed Provenance Is Incomplete");
        }
    }

    private static String canonical(Phase phase, Path sourceRoot, Path activeRoot, String sourceHash,
                                    String activeHash, String originHash, String artifactHash) {
        return "format=" + FORMAT + "\n"
            + "phase=" + phase.name() + "\n"
            + "source=" + encodePath(sourceRoot) + "\n"
            + "active=" + (activeRoot == null ? "" : encodePath(activeRoot)) + "\n"
            + "sourceHash=" + sourceHash + "\n"
            + "activeHash=" + activeHash + "\n"
            + "originHash=" + originHash + "\n"
            + "artifactHash=" + artifactHash + "\n";
    }

    private static String value(String line, String name) throws MigrationException {
        String prefix = name + "=";
        if (!line.startsWith(prefix)) {
            throw new MigrationException("Fresh Root Provenance Field Is Missing: " + name);
        }
        return line.substring(prefix.length());
    }

    private static String requireHash(String value, String name) {
        if (value == null || value.length() != 64 || !value.chars().allMatch(character ->
            character >= '0' && character <= '9' || character >= 'a' && character <= 'f')) {
            throw new IllegalArgumentException(name + " Must Be A Lowercase SHA-256 Hash");
        }
        return value;
    }

    private static String encodePath(Path path) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            path.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Path decodePath(String value, String name) throws MigrationException {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            String path = new String(decoded, StandardCharsets.UTF_8);
            Path normalized = MigrationPaths.requirePath(Path.of(path), name);
            if (!encodePath(normalized).equals(value)) {
                throw new MigrationException("Fresh Root Provenance Path Is Not Canonical: " + name);
            }
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw new MigrationException("Fresh Root Provenance Path Is Invalid: " + name, exception);
        }
    }

    private enum Phase {
        SOURCE_ABSENT,
        SOURCE_EMPTY,
        ACTIVE_EMPTY,
        CONSUMED
    }
}
