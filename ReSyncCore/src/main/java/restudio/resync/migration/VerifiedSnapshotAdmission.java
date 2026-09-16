package restudio.resync.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class VerifiedSnapshotAdmission {
    private final Path root;
    private final Path realRoot;
    private final Path manifestPath;
    private final Path statePath;
    private final Path metadataPath;
    private final SnapshotManifest manifest;
    private final byte[] manifestBytes;
    private final byte[] stateBytes;
    private final byte[] metadataBytes;

    private VerifiedSnapshotAdmission(Path root, Path realRoot, Path manifestPath, Path statePath, Path metadataPath,
                                      SnapshotManifest manifest, byte[] manifestBytes, byte[] stateBytes,
                                      byte[] metadataBytes) {
        this.root = root;
        this.realRoot = realRoot;
        this.manifestPath = manifestPath;
        this.statePath = statePath;
        this.metadataPath = metadataPath;
        this.manifest = manifest;
        this.manifestBytes = manifestBytes.clone();
        this.stateBytes = stateBytes.clone();
        this.metadataBytes = metadataBytes.clone();
    }

    static VerifiedSnapshotAdmission admit(Path snapshotRoot) throws IOException {
        Path root = MigrationPaths.requireDirectory(snapshotRoot, "snapshotRoot");
        MigrationPaths.requireNoSymlinkTree(root);
        Path realRoot = root.toRealPath();
        Path manifestPath = sidecar(root, ".manifest");
        Path statePath = sidecar(root, ".state");
        Path metadataPath = ProductionSnapshotMetadataManifest.pathFor(root);
        requireRegular(manifestPath, "Snapshot Manifest");
        requireRegular(statePath, "Snapshot State");
        requireRegular(metadataPath, "Production Snapshot Metadata Manifest");

        SnapshotManifest manifest = SnapshotManifest.read(manifestPath);
        if (manifest.entries().isEmpty()) {
            throw new MigrationException("Verified Exported Snapshot Has No Owned Files");
        }
        byte[] manifestBytes = Files.readAllBytes(manifestPath);
        byte[] stateBytes = Files.readAllBytes(statePath);
        byte[] metadataBytes = Files.readAllBytes(metadataPath);
        requireCanonicalManifest(manifest, manifestBytes);
        requireVerifiedState(manifest, statePath, stateBytes);
        ProductionSnapshotMetadataManifest.Values productionMetadata = ProductionSnapshotMetadataManifest.read(root);
        if (!productionMetadata.metadata().equals(manifest.metadata())
            || !productionMetadata.manifestHash().equals(manifest.manifestHash())) {
            throw new MigrationException("Production Snapshot Metadata Does Not Match Exported Snapshot Manifest");
        }
        SnapshotVerification current = manifest.verify(root);
        current.requireVerified();
        if (!current.manifestHash().equals(manifest.manifestHash())) {
            throw new MigrationException("Exported Snapshot Verification Hash Does Not Match Manifest");
        }
        return new VerifiedSnapshotAdmission(root, realRoot, manifestPath, statePath, metadataPath, manifest,
            manifestBytes, stateBytes, metadataBytes);
    }

    public Path root() {
        return root;
    }

    public SnapshotMetadata metadata() {
        return manifest.metadata();
    }

    public String manifestHash() {
        return manifest.manifestHash();
    }

    public Snapshot snapshot() throws IOException {
        Path currentRoot = MigrationPaths.requireDirectory(root, "snapshotRoot");
        MigrationPaths.requireNoSymlinkTree(currentRoot);
        if (!currentRoot.toRealPath().equals(realRoot)) {
            throw new MigrationException("Exported Snapshot Root Identity Changed After Admission");
        }
        requireUnchanged(manifestPath, manifestBytes, "Snapshot Manifest");
        requireUnchanged(statePath, stateBytes, "Snapshot State");
        requireUnchanged(metadataPath, metadataBytes, "Production Snapshot Metadata Manifest");
        SnapshotManifest currentManifest = SnapshotManifest.read(manifestPath);
        if (!currentManifest.metadata().equals(manifest.metadata())
            || !currentManifest.manifestHash().equals(manifest.manifestHash())
            || !currentManifest.canonicalText().equals(manifest.canonicalText())) {
            throw new MigrationException("Exported Snapshot Manifest Changed After Admission");
        }
        requireVerifiedState(currentManifest, statePath, stateBytes);
        ProductionSnapshotMetadataManifest.Values currentMetadata = ProductionSnapshotMetadataManifest.read(root);
        if (!currentMetadata.metadata().equals(manifest.metadata())
            || !currentMetadata.manifestHash().equals(manifest.manifestHash())) {
            throw new MigrationException("Exported Snapshot Metadata Changed After Admission");
        }
        SnapshotVerification verification = currentManifest.verify(root);
        verification.requireVerified();
        if (!verification.manifestHash().equals(manifest.manifestHash())) {
            throw new MigrationException("Exported Snapshot Verification Hash Changed After Admission");
        }
        return new Snapshot(root, manifestPath, statePath, manifest.metadata(), manifest, SnapshotState.VERIFIED,
            verification);
    }

    PersistenceParticipantRegistry participantRegistry(Path activeRoot) {
        Path scope = MigrationPaths.requirePath(activeRoot, "activeRoot");
        Map<String, List<String>> pathsByOwner = new TreeMap<>();
        for (SnapshotManifest.Entry entry : manifest.entries()) {
            pathsByOwner.computeIfAbsent(entry.owner(), ignored -> new ArrayList<>()).add(entry.relativePath());
        }
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(scope);
        pathsByOwner.forEach((owner, paths) -> registry.register(new ManifestParticipant(owner, scope, paths)));
        return registry;
    }

    void copySidecars(Path targetRoot) throws IOException {
        snapshot();
        Path manifestTarget = sidecar(targetRoot, ".manifest");
        Path stateTarget = sidecar(targetRoot, ".state");
        Path metadataTarget = ProductionSnapshotMetadataManifest.pathFor(targetRoot);
        for (Path target : List.of(manifestTarget, stateTarget, metadataTarget)) {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Snapshot Copy Sidecar Already Exists: " + target.getFileName());
            }
        }
        AtomicFiles.writeNew(manifestTarget, manifestBytes.clone());
        AtomicFiles.writeNew(metadataTarget, metadataBytes.clone());
        AtomicFiles.writeNew(stateTarget, stateBytes.clone());
    }

    private static void requireCanonicalManifest(SnapshotManifest manifest, byte[] bytes) throws MigrationException {
        byte[] canonical = (manifest.canonicalText() + "manifest-hash=" + manifest.manifestHash() + "\n")
            .getBytes(StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, canonical)) {
            throw new MigrationException("Exported Snapshot Manifest Is Not Canonical");
        }
    }

    private static void requireVerifiedState(SnapshotManifest manifest, Path statePath, byte[] bytes) throws IOException {
        SnapshotVerification recorded = SnapshotStateStore.readVerification(statePath);
        byte[] canonical = ("state=VERIFIED\nverified=true\nmanifest-hash=" + manifest.manifestHash()
            + "\nfailures=0\n").getBytes(StandardCharsets.UTF_8);
        if (!recorded.verified() || !recorded.failures().isEmpty()
            || !recorded.manifestHash().equals(manifest.manifestHash()) || !Arrays.equals(bytes, canonical)) {
            throw new MigrationException("Exported Snapshot State Is Not An Exact Verified Manifest Binding");
        }
    }

    private static void requireUnchanged(Path path, byte[] expected, String label) throws IOException {
        requireRegular(path, label);
        if (!Arrays.equals(Files.readAllBytes(path), expected)) {
            throw new MigrationException(label + " Changed After Admission");
        }
    }

    private static void requireRegular(Path path, String label) throws MigrationException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException(label + " Must Be A Regular Non-Symbolic-Link File: " + path);
        }
    }

    private static Path sidecar(Path root, String suffix) {
        Path normalized = MigrationPaths.requirePath(root, "snapshotRoot");
        Path parent = normalized.getParent();
        Path name = normalized.getFileName();
        if (parent == null || name == null) {
            throw new IllegalArgumentException("Snapshot Root Must Have A Parent");
        }
        return parent.resolve(name + suffix).toAbsolutePath().normalize();
    }

    private static final class ManifestParticipant implements RebindablePersistenceParticipant {
        private final String owner;
        private final List<String> relativePaths;
        private final Set<String> ownedPaths;
        private Path activeRoot;

        private ManifestParticipant(String owner, Path root, List<String> relativePaths) {
            this.owner = MigrationCanonical.requireText(owner, "participant owner");
            this.activeRoot = MigrationPaths.requirePath(root, "participant scope");
            this.relativePaths = List.copyOf(relativePaths);
            this.ownedPaths = Set.copyOf(new LinkedHashSet<>(relativePaths));
            if (this.relativePaths.isEmpty() || this.ownedPaths.size() != this.relativePaths.size()) {
                throw new IllegalArgumentException("Manifest Participant Paths Must Be Unique And Non-Empty");
            }
        }

        @Override
        public String owner() {
            return owner;
        }

        @Override
        public synchronized Path root() {
            return MigrationPaths.resolveInside(activeRoot, relativePaths.getFirst());
        }

        @Override
        public synchronized Path rebindScope() {
            return activeRoot;
        }

        @Override
        public synchronized boolean owns(Path file) {
            Path candidate = MigrationPaths.requirePath(file, "file");
            if (!candidate.startsWith(activeRoot) || candidate.equals(activeRoot)) {
                return false;
            }
            return ownedPaths.contains(MigrationPaths.relative(activeRoot, candidate));
        }

        @Override
        public void flush() {
        }

        @Override
        public void quiesce() {
        }

        @Override
        public void resume() {
        }

        @Override
        public synchronized void rebind(Path activeRoot) throws IOException {
            this.activeRoot = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        }

        @Override
        public void healthCheck() {
        }
    }
}
