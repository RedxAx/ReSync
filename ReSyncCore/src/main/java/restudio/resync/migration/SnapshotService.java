package restudio.resync.migration;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class SnapshotService {
    private final MigrationFence fence;
    private final MigrationPreflight preflight;

    public SnapshotService(MigrationFence fence) {
        this.fence = Objects.requireNonNull(fence, "fence");
        this.preflight = new MigrationPreflight();
    }

    public PreflightResult preflight(Path sourceRoot, Path stagingRoot, PersistenceParticipantRegistry participants, long reservedBytes) {
        return preflight.inspect(sourceRoot, stagingRoot, participants, reservedBytes);
    }

    public VerifiedSnapshotAdmission admitExported(Path snapshotRoot) throws IOException {
        return VerifiedSnapshotAdmission.admit(snapshotRoot);
    }

    public Snapshot copyAdmitted(VerifiedSnapshotAdmission admission, Path stagingRoot) throws IOException {
        VerifiedSnapshotAdmission sourceAdmission = Objects.requireNonNull(admission, "admission");
        Snapshot sourceSnapshot = sourceAdmission.snapshot();
        Path source = sourceSnapshot.root();
        Path staging = MigrationPaths.requirePath(stagingRoot, "stagingRoot");
        MigrationPaths.requireDistinctRoots(source, staging);
        StagingArtifactRecovery.prepareSnapshot(staging);
        requireAvailableSidecars(staging);
        requireEmptyStaging(staging);
        Path marker = sidecar(staging, StagingArtifactRecovery.COPY_MARKER_SUFFIX);
        SnapshotStateStore.write(marker, SnapshotState.STAGING,
            new SnapshotVerification(false, zeroHash(), List.of("Snapshot Copy In Progress")));
        try {
            copyTree(source, staging);
            SnapshotVerification copied = sourceSnapshot.manifest().verify(staging);
            copied.requireVerified();
            sourceAdmission.snapshot();
            sourceAdmission.copySidecars(staging);
            VerifiedSnapshotAdmission retained = admitExported(staging);
            Snapshot snapshot = retained.snapshot();
            if (!snapshot.manifest().canonicalText().equals(sourceSnapshot.manifest().canonicalText())
                || !snapshot.manifest().manifestHash().equals(sourceSnapshot.manifest().manifestHash())
                || !snapshot.metadata().equals(sourceSnapshot.metadata())) {
                throw new MigrationException("Admitted Snapshot Copy Does Not Preserve Its Bound Manifest");
            }
            Files.deleteIfExists(marker);
            return snapshot;
        } catch (IOException | RuntimeException exception) {
            try {
                SnapshotStateStore.write(marker, SnapshotState.FAILED,
                    new SnapshotVerification(false, sourceSnapshot.manifest().manifestHash(), List.of(message(exception))));
            } catch (IOException markerException) {
                exception.addSuppressed(markerException);
            }
            try {
                StagingArtifactRecovery.quarantineSnapshotFailure(staging);
            } catch (IOException quarantineException) {
                exception.addSuppressed(quarantineException);
            }
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
    }

    public Snapshot create(Path sourceRoot, Path stagingRoot, SnapshotMetadata metadata, PersistenceParticipantRegistry participants) throws IOException {
        StagingArtifactRecovery.prepareSnapshot(stagingRoot);
        PreflightResult result = preflight.inspect(sourceRoot, stagingRoot, participants, 0);
        result.requirePassed();
        MigrationFence.MigrationLease migration = fence.acquireMigration();
        try {
            participants.flushAll();
            boolean quiesced = false;
            try {
                participants.quiesceAll();
                quiesced = true;
                return createFenced(sourceRoot, stagingRoot, metadata, participants);
            } finally {
                if (quiesced) {
                    participants.resumeAll();
                }
            }
        } finally {
            migration.close();
        }
    }

    public Snapshot createFenced(Path sourceRoot, Path stagingRoot, SnapshotMetadata metadata,
                                 PersistenceParticipantRegistry participants) throws IOException {
        Path source = MigrationPaths.requireDirectory(sourceRoot, "sourceRoot");
        SnapshotManifest manifest = SnapshotManifest.scan(source, metadata, participants);
        return createFenced(source, stagingRoot, metadata, participants, manifest);
    }

    public Snapshot createFenced(Path sourceRoot, Path stagingRoot, SnapshotMetadata metadata,
                                 PersistenceParticipantRegistry participants, SnapshotManifest boundManifest) throws IOException {
        Path source = MigrationPaths.requireDirectory(sourceRoot, "sourceRoot");
        Path staging = MigrationPaths.requirePath(stagingRoot, "stagingRoot");
        MigrationPaths.requireDistinctRoots(source, staging);
        if (!boundManifest.metadata().equals(metadata)) {
            throw new MigrationException("Snapshot Manifest Metadata Does Not Match Snapshot Metadata");
        }
        participants.validateForRoot(source);
        StagingArtifactRecovery.prepareSnapshot(staging);
        requireEmptyStaging(staging);
        Path manifestPath = sidecar(staging, ".manifest");
        Path statePath = sidecar(staging, ".state");
        SnapshotManifest manifest = null;
        try {
            manifest = boundManifest;
            SnapshotStateStore.write(statePath, SnapshotState.STAGING, new SnapshotVerification(false, zeroHash(), List.of("Snapshot Copy In Progress")));
            copyTree(source, staging, participants);
            SnapshotVerification verification = manifest.verify(staging);
            if (!verification.verified()) {
                SnapshotStateStore.write(statePath, SnapshotState.FAILED, verification);
                throw new MigrationException("Snapshot Verification Failed: " + String.join("; ", verification.failures()));
            }
            manifest.write(manifestPath);
            ProductionSnapshotMetadataManifest.write(staging, manifest);
            SnapshotStateStore.write(statePath, SnapshotState.VERIFIED, verification);
            return new Snapshot(staging, manifestPath, statePath, metadata, manifest, SnapshotState.VERIFIED, verification);
        } catch (IOException | RuntimeException exception) {
            String hash = manifest == null ? zeroHash() : manifest.manifestHash();
            try {
                SnapshotStateStore.write(statePath, SnapshotState.FAILED, new SnapshotVerification(false, hash, List.of(message(exception))));
            } catch (IOException stateException) {
                exception.addSuppressed(stateException);
            }
            try {
                StagingArtifactRecovery.quarantineSnapshotFailure(staging);
            } catch (IOException quarantineException) {
                exception.addSuppressed(quarantineException);
            }
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
    }

    public SnapshotVerification verify(Snapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        SnapshotVerification verification;
        try {
            SnapshotManifest persisted = SnapshotManifest.read(snapshot.manifestPath());
            if (!persisted.metadata().equals(snapshot.metadata())
                || !persisted.manifestHash().equals(snapshot.manifest().manifestHash())
                || !persisted.canonicalText().equals(snapshot.manifest().canonicalText())) {
                verification = new SnapshotVerification(false, snapshot.manifest().manifestHash(),
                    List.of("Persisted Snapshot Manifest Does Not Match Bound Manifest"));
            } else {
                ProductionSnapshotMetadataManifest.Values metadata = ProductionSnapshotMetadataManifest.read(snapshot.root());
                if (!metadata.metadata().equals(persisted.metadata())
                    || !metadata.manifestHash().equals(persisted.manifestHash())) {
                    verification = new SnapshotVerification(false, snapshot.manifest().manifestHash(),
                        List.of("Persisted Snapshot Metadata Does Not Match Bound Manifest"));
                } else {
                    verification = persisted.verify(snapshot.root());
                }
            }
        } catch (IOException | RuntimeException exception) {
            verification = new SnapshotVerification(false, snapshot.manifest().manifestHash(), List.of(message(exception)));
        }
        SnapshotState state = verification.verified() ? SnapshotState.VERIFIED : SnapshotState.FAILED;
        SnapshotStateStore.write(snapshot.statePath(), state, verification);
        return verification;
    }

    public void quarantineCurrentSnapshot(Path stagingRoot) throws IOException {
        StagingArtifactRecovery.quarantineCurrentSnapshot(stagingRoot);
    }

    private static void copyTree(Path source, Path target, PersistenceParticipantRegistry participants) throws IOException {
        Files.walkFileTree(source, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new MigrationException("Symbolic Link Directory Is Not Allowed: " + directory);
                }
                if (!directory.equals(source) && participants.isExternalPath(source, directory)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path destination = directory.equals(source) ? target : MigrationPaths.resolveInside(target, MigrationPaths.relative(source, directory));
                Files.createDirectories(destination);
                if (!directory.equals(source) && participants.isDerivedCachePath(source, directory)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file)) {
                    throw new MigrationException("Symbolic Link File Is Not Allowed: " + file);
                }
                if (!attributes.isRegularFile()) {
                    throw new MigrationException("Non-Regular File Is Not Allowed: " + file);
                }
                if (participants.isExternalPath(source, file)) {
                    return FileVisitResult.CONTINUE;
                }
                Path destination = MigrationPaths.resolveInside(target, MigrationPaths.relative(source, file));
                AtomicFiles.copy(file, destination);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw new MigrationException("Cannot Copy Snapshot File: " + file, exception);
            }
        });
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new MigrationException("Symbolic Link Directory Is Not Allowed: " + directory);
                }
                Path destination = directory.equals(source)
                    ? target : MigrationPaths.resolveInside(target, MigrationPaths.relative(source, directory));
                Files.createDirectories(destination);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file)) {
                    throw new MigrationException("Symbolic Link File Is Not Allowed: " + file);
                }
                if (!attributes.isRegularFile()) {
                    throw new MigrationException("Non-Regular File Is Not Allowed: " + file);
                }
                AtomicFiles.copy(file, MigrationPaths.resolveInside(target, MigrationPaths.relative(source, file)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw new MigrationException("Cannot Copy Snapshot File: " + file, exception);
            }
        });
    }

    private static void requireEmptyStaging(Path staging) throws IOException {
        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(staging) || !Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("stagingRoot Must Be A Non-Symbolic-Link Directory");
            }
            try (var stream = Files.list(staging)) {
                if (stream.findAny().isPresent()) {
                    throw new MigrationException("stagingRoot Must Be Empty");
                }
            }
        } else {
            Files.createDirectories(staging);
        }
    }

    private static void requireAvailableSidecars(Path staging) throws MigrationException {
        for (Path path : List.of(sidecar(staging, ".manifest"), sidecar(staging, ".state"),
            ProductionSnapshotMetadataManifest.pathFor(staging), sidecar(staging, StagingArtifactRecovery.COPY_MARKER_SUFFIX))) {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Snapshot Copy Sidecar Already Exists: " + path.getFileName());
            }
        }
    }

    private static Path sidecar(Path staging, String suffix) {
        Path parent = staging.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Staging Root Has No Parent");
        }
        return parent.resolve(staging.getFileName() + suffix).toAbsolutePath().normalize();
    }

    private static String zeroHash() {
        return "0".repeat(64);
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank() ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
