package restudio.resync.migration;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class VerifiedSnapshotExporter {
    private VerifiedSnapshotExporter() {
    }

    public static VerifiedSnapshotAdmission export(
        Path stagedRoot,
        VerifiedSnapshotAdmission verifiedSourceAdmission,
        SnapshotMetadata targetMetadata,
        Collection<SourceOwnerBinding> sourceOwnerBindings,
        Collection<ChangeTargetOwner> changeTargetOwners,
        Path exportRoot
    ) throws IOException {
        Path staged = MigrationPaths.requireDirectory(stagedRoot, "stagedRoot");
        Path export = MigrationPaths.requirePath(exportRoot, "exportRoot");
        VerifiedSnapshotAdmission sourceAdmission = Objects.requireNonNull(verifiedSourceAdmission, "verifiedSourceAdmission");
        SnapshotMetadata metadata = Objects.requireNonNull(targetMetadata, "targetMetadata");
        MigrationPaths.requireDistinctRoots(staged, export);
        Snapshot source = sourceAdmission.snapshot();
        MigrationPaths.requireDistinctRoots(source.root(), staged);
        MigrationPaths.requireDistinctRoots(source.root(), export);

        Map<String, SnapshotManifest.Entry> sourceEntries = indexSourceEntries(source.manifest());
        rejectFileAliases(source.root(), sourceEntries.keySet(), "Verified Source Snapshot");
        Map<String, String> sourceOwners = normalizeSourceOwners(sourceEntries, sourceOwnerBindings);
        Map<String, String> targetOwners = normalizeTargetOwners(changeTargetOwners);
        rejectSourceTargetAliases(sourceOwners.keySet(), targetOwners.keySet());
        StagedTree stagedTree = inspectStaged(staged, sourceEntries, targetOwners);
        SnapshotManifest manifest = manifest(metadata, stagedTree, sourceOwners, targetOwners);
        SnapshotService snapshots = new SnapshotService(new MigrationFence());

        ExistingExport existing = existingExport(export, manifest, snapshots);
        if (existing.admission() != null) {
            sourceAdmission.snapshot();
            return existing.admission();
        }
        if (existing.recoverablePartial()) {
            recoverPartial(export);
        }
        materialize(staged, stagedTree, export, manifest, sourceAdmission, snapshots);
        return snapshots.admitExported(export);
    }

    public static VerifiedSnapshotAdmission materialize(
        Path stagedRoot,
        VerifiedSnapshotAdmission verifiedSourceAdmission,
        SnapshotMetadata targetMetadata,
        Collection<SourceOwnerBinding> sourceOwnerBindings,
        Collection<ChangeTargetOwner> changeTargetOwners,
        Path exportRoot
    ) throws IOException {
        return export(stagedRoot, verifiedSourceAdmission, targetMetadata, sourceOwnerBindings,
            changeTargetOwners, exportRoot);
    }

    public static VerifiedSnapshotAdmission export(Request request) throws IOException {
        Objects.requireNonNull(request, "request");
        return export(request.stagedRoot(), request.verifiedSourceAdmission(), request.targetMetadata(),
            request.sourceOwnerBindings(), request.changeTargetOwners(), request.exportRoot());
    }

    private static ExistingExport existingExport(Path export, SnapshotManifest expected, SnapshotService snapshots)
        throws IOException {
        Path manifestPath = sidecar(export, ".manifest");
        Path statePath = sidecar(export, ".state");
        Path metadataPath = ProductionSnapshotMetadataManifest.pathFor(export);
        boolean rootExists = Files.exists(export, LinkOption.NOFOLLOW_LINKS);
        boolean rootDirectory = rootExists && !Files.isSymbolicLink(export)
            && Files.isDirectory(export, LinkOption.NOFOLLOW_LINKS);
        boolean sidecarExists = List.of(manifestPath, statePath, metadataPath).stream()
            .anyMatch(path -> Files.exists(path, LinkOption.NOFOLLOW_LINKS));
        if (rootExists && !rootDirectory) {
            throw new MigrationException("exportRoot Must Be A Non-Symbolic-Link Directory");
        }
        if (!rootExists && sidecarExists) {
            if (recoverablePartial(statePath, expected.manifestHash())) {
                return new ExistingExport(null, true);
            }
            throw new MigrationException("Export Sidecars Exist Without Their Export Root");
        }
        if (!rootExists) {
            return new ExistingExport(null, false);
        }
        boolean empty;
        try (var children = Files.list(export)) {
            empty = children.findAny().isEmpty();
        }
        if (empty && !sidecarExists) {
            return new ExistingExport(null, false);
        }
        if (!sidecarExists) {
            throw new MigrationException("Export Root Contains Unbound Materialization Debris");
        }
        if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)
            || !Files.isRegularFile(statePath, LinkOption.NOFOLLOW_LINKS)
            || !Files.isRegularFile(metadataPath, LinkOption.NOFOLLOW_LINKS)) {
            if (recoverablePartial(statePath, expected.manifestHash())) {
                return new ExistingExport(null, true);
            }
            throw new MigrationException("Export Root Contains Incomplete Snapshot Sidecars");
        }
        VerifiedSnapshotAdmission admission;
        try {
            admission = snapshots.admitExported(export);
        } catch (IOException | RuntimeException exception) {
            if (recoverablePartial(statePath, expected.manifestHash())) {
                return new ExistingExport(null, true);
            }
            throw new MigrationException("Existing Export Is Invalid", exception);
        }
        if (!admission.snapshot().manifest().canonicalText().equals(expected.canonicalText())
            || !admission.snapshot().metadata().equals(expected.metadata())) {
            throw new MigrationException("Export Root Contains A Conflicting Snapshot");
        }
        return new ExistingExport(admission, false);
    }

    private static boolean recoverablePartial(Path statePath, String expectedManifestHash) {
        if (Files.isSymbolicLink(statePath) || !Files.isRegularFile(statePath, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(statePath);
            if (lines.isEmpty() || !(lines.getFirst().equals("state=STAGING")
                || lines.getFirst().equals("state=VERIFIED") || lines.getFirst().equals("state=FAILED"))) {
                return false;
            }
            return SnapshotStateStore.readVerification(statePath).manifestHash().equals(expectedManifestHash);
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private static void materialize(
        Path staged,
        StagedTree initial,
        Path export,
        SnapshotManifest manifest,
        VerifiedSnapshotAdmission sourceAdmission,
        SnapshotService snapshots
    ) throws IOException {
        try {
            if (!Files.exists(export, LinkOption.NOFOLLOW_LINKS)) {
                MigrationPaths.requireWritableParent(export);
                Files.createDirectories(export);
            }
            MigrationPaths.requireDirectory(export, "exportRoot");
            requireEmpty(export);
            Path statePath = sidecar(export, ".state");
            Path manifestPath = sidecar(export, ".manifest");
            Path metadataPath = ProductionSnapshotMetadataManifest.pathFor(export);
            requireNewSidecars(manifestPath, statePath, metadataPath);
            SnapshotStateStore.write(statePath, SnapshotState.STAGING,
                new SnapshotVerification(false, manifest.manifestHash(), List.of("Export Copy In Progress")));
            copyTree(staged, export, initial);
            StagedTree finalTree = inspectStaged(staged, Map.of(), Map.of());
            if (!initial.sameFiles(finalTree) || !initial.directories().equals(finalTree.directories())) {
                throw new MigrationException("Staged Root Changed During Export");
            }
            sourceAdmission.snapshot();
            SnapshotVerification verification = manifest.verify(export);
            verification.requireVerified();
            SnapshotStateStore.write(statePath, SnapshotState.VERIFIED, verification);
            manifest.write(manifestPath);
            ProductionSnapshotMetadataManifest.write(export, manifest);
        } catch (IOException | RuntimeException exception) {
            try {
                recoverPartial(export);
            } catch (IOException | RuntimeException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
    }

    private static SnapshotManifest manifest(
        SnapshotMetadata metadata,
        StagedTree staged,
        Map<String, String> sourceOwners,
        Map<String, String> targetOwners
    ) throws IOException {
        List<SnapshotManifest.Entry> entries = new ArrayList<>();
        for (Map.Entry<String, FileInfo> entry : staged.files().entrySet()) {
            String relative = entry.getKey();
            String owner = targetOwners.get(relative);
            if (owner == null) {
                owner = sourceOwners.get(relative);
            }
            if (owner == null) {
                throw new MigrationException("Staged Root File Has No Authoritative Owner: " + relative);
            }
            FileInfo file = entry.getValue();
            entries.add(new SnapshotManifest.Entry(relative, file.size(), file.sha256(), owner));
        }
        return new SnapshotManifest(metadata, List.copyOf(new TreeSet<>(staged.directories())), entries);
    }

    private static StagedTree inspectStaged(
        Path staged,
        Map<String, SnapshotManifest.Entry> sourceEntries,
        Map<String, String> targetOwners
    ) throws IOException {
        Map<String, FileInfo> files = new TreeMap<>();
        Set<String> directories = new TreeSet<>();
        Map<String, String> foldedPaths = new HashMap<>();
        List<Path> filePaths = new ArrayList<>();
        Files.walkFileTree(staged, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new MigrationException("Symbolic Link Directory Is Not Allowed: " + directory);
                }
                if (!directory.equals(staged)) {
                    String relative = MigrationPaths.relative(staged, directory);
                    addPathAlias(foldedPaths, relative, "staged root directory");
                    directories.add(relative);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new MigrationException("Only Regular Files Are Allowed In A Staged Root: " + file);
                }
                String relative = MigrationPaths.relative(staged, file);
                addPathAlias(foldedPaths, relative, "staged root file");
                FileInfo info = new FileInfo(attributes.size(), sha256(file));
                files.put(relative, info);
                rejectFileAlias(filePaths, file, "Staged Root");
                filePaths.add(file);
                if (!sourceEntries.isEmpty()) {
                    SnapshotManifest.Entry source = sourceEntries.get(relative);
                    String targetOwner = targetOwners.get(relative);
                    if (source == null && targetOwner == null) {
                        throw new MigrationException("Staged Root File Has No Explicit Change Target Owner: " + relative);
                    }
                    if (source != null && targetOwner == null
                        && (source.size() != info.size() || !source.sha256().equals(info.sha256()))) {
                        throw new MigrationException("Changed Staged Source Requires An Explicit Change Target Owner: " + relative);
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw new MigrationException("Cannot Inspect Staged Root File: " + file, exception);
            }
        });
        rejectCaseAliasedHierarchy(files.keySet(), directories);
        for (String target : targetOwners.keySet()) {
            if (!files.containsKey(target)) {
                throw new MigrationException("Explicit Change Target Owner Has No Staged File: " + target);
            }
        }
        return new StagedTree(files, directories);
    }

    private static Map<String, SnapshotManifest.Entry> indexSourceEntries(SnapshotManifest manifest) throws IOException {
        Map<String, SnapshotManifest.Entry> entries = new TreeMap<>();
        Map<String, String> folded = new HashMap<>();
        for (SnapshotManifest.Entry entry : manifest.entries()) {
            addPathAlias(folded, entry.relativePath(), "source manifest");
            if (entries.put(entry.relativePath(), entry) != null) {
                throw new MigrationException("Source Manifest Contains A Duplicate File: " + entry.relativePath());
            }
        }
        return Map.copyOf(entries);
    }

    private static Map<String, String> normalizeSourceOwners(Map<String, SnapshotManifest.Entry> sourceEntries,
                                                              Collection<SourceOwnerBinding> values) throws IOException {
        Map<String, String> owners = new TreeMap<>();
        Map<String, String> folded = new HashMap<>();
        for (SourceOwnerBinding value : values == null ? List.<SourceOwnerBinding>of() : values) {
            Objects.requireNonNull(value, "source owner binding");
            addPathAlias(folded, value.relativePath(), "source owner binding");
            SnapshotManifest.Entry source = sourceEntries.get(value.relativePath());
            if (source == null) {
                throw new MigrationException("Source Owner Binding Is Not In The Verified Source: " + value.relativePath());
            }
            if (!source.owner().equals(value.owner())) {
                throw new MigrationException("Source Owner Binding Does Not Match The Verified Source Manifest: "
                    + value.relativePath());
            }
            if (owners.put(value.relativePath(), value.owner()) != null) {
                throw new MigrationException("Duplicate Source Owner Binding: " + value.relativePath());
            }
        }
        if (!owners.keySet().equals(sourceEntries.keySet())) {
            Set<String> missing = new HashSet<>(sourceEntries.keySet());
            missing.removeAll(owners.keySet());
            Set<String> extra = new HashSet<>(owners.keySet());
            extra.removeAll(sourceEntries.keySet());
            throw new MigrationException("Source Owner Bindings Are Incomplete: missing=" + missing + ", extra=" + extra);
        }
        return Map.copyOf(owners);
    }

    private static Map<String, String> normalizeTargetOwners(Collection<ChangeTargetOwner> values) throws IOException {
        Map<String, String> owners = new TreeMap<>();
        Map<String, String> folded = new HashMap<>();
        for (ChangeTargetOwner value : values == null ? List.<ChangeTargetOwner>of() : values) {
            Objects.requireNonNull(value, "change target owner");
            addPathAlias(folded, value.relativePath(), "change target owner");
            if (owners.put(value.relativePath(), value.owner()) != null) {
                throw new MigrationException("Duplicate Change Target Owner: " + value.relativePath());
            }
        }
        return Map.copyOf(owners);
    }

    private static void rejectSourceTargetAliases(Collection<String> sourcePaths, Collection<String> targetPaths)
        throws IOException {
        Map<String, String> folded = new HashMap<>();
        for (String path : sourcePaths) {
            folded.put(foldPath(path), path);
        }
        for (String path : targetPaths) {
            String previous = folded.putIfAbsent(foldPath(path), path);
            if (previous != null && !previous.equals(path)) {
                throw new MigrationException("Source And Change Target Paths Are Case-Aliases: "
                    + previous + " And " + path);
            }
        }
        for (String source : sourcePaths) {
            String foldedSource = foldPath(source);
            for (String target : targetPaths) {
                String foldedTarget = foldPath(target);
                if (foldedSource.startsWith(foldedTarget + "/") || foldedTarget.startsWith(foldedSource + "/")) {
                    throw new MigrationException("Source And Change Target Paths Collide: " + source + " And " + target);
                }
            }
        }
    }

    private static void copyTree(Path staged, Path export, StagedTree expected) throws IOException {
        for (String directory : expected.directories()) {
            Path target = MigrationPaths.resolveInside(export, directory);
            MigrationPaths.requireNoSymlinkTraversal(export, target.getParent());
            Files.createDirectories(target);
            MigrationPaths.requireNoSymlinkTraversal(export, target);
        }
        for (String relative : expected.files().keySet()) {
            Path source = MigrationPaths.resolveInside(staged, relative);
            Path target = MigrationPaths.resolveInside(export, relative);
            Path parent = target.getParent();
            if (parent == null) {
                throw new MigrationException("Export File Has No Parent: " + relative);
            }
            MigrationPaths.requireNoSymlinkTraversal(export, parent);
            Files.createDirectories(parent);
            MigrationPaths.requireNoSymlinkTraversal(export, parent);
            AtomicFiles.copy(source, target);
        }
    }

    private static void requireEmpty(Path root) throws IOException {
        try (var children = Files.list(root)) {
            if (children.findAny().isPresent()) {
                throw new MigrationException("exportRoot Must Be Empty Before Materialization");
            }
        }
    }

    private static void requireNewSidecars(Path manifest, Path state, Path metadata) throws IOException {
        for (Path path : List.of(manifest, state, metadata)) {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Export Sidecar Already Exists: " + path.getFileName());
            }
        }
    }

    private static void addPathAlias(Map<String, String> folded, String path, String label) throws MigrationException {
        String normalized = MigrationPaths.requireRelative(path);
        String previous = folded.putIfAbsent(foldPath(normalized), normalized);
        if (previous != null) {
            throw new MigrationException(label + " Contains A Case-Aliased Path: " + previous + " And " + normalized);
        }
    }

    private static void rejectFileAliases(Path root, Collection<String> relativePaths, String label) throws IOException {
        List<Path> previous = new ArrayList<>();
        for (String relative : new TreeSet<>(relativePaths)) {
            Path file = MigrationPaths.resolveInside(root, relative);
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException(label + " Contains A Non-Regular File: " + relative);
            }
            rejectFileAlias(previous, file, label);
            previous.add(file);
        }
    }

    private static void rejectFileAlias(List<Path> previous, Path candidate, String label) throws IOException {
        for (Path value : previous) {
            if (Files.isSameFile(value, candidate)) {
                throw new MigrationException(label + " Contains A File Alias: " + candidate);
            }
        }
    }

    private static void recoverPartial(Path export) throws IOException {
        if (Files.exists(export, LinkOption.NOFOLLOW_LINKS)) {
            MigrationPaths.requireDirectory(export, "exportRoot");
            MigrationPaths.requireNoSymlinkTree(export);
            List<Path> children;
            try (var stream = Files.list(export)) {
                children = stream.toList();
            }
            for (Path child : children) {
                deleteTree(child);
            }
        }
        for (Path sidecar : List.of(sidecar(export, ".manifest"), sidecar(export, ".state"),
            ProductionSnapshotMetadataManifest.pathFor(export))) {
            if (Files.isSymbolicLink(sidecar)
                || (Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS))) {
                throw new MigrationException("Export Recovery Sidecar Is Invalid: " + sidecar);
            }
            Files.deleteIfExists(sidecar);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void rejectCaseAliasedHierarchy(Collection<String> files, Collection<String> directories)
        throws IOException {
        for (String file : files) {
            String foldedFile = foldPath(file);
            for (String directory : directories) {
                String foldedDirectory = foldPath(directory);
                if (foldedFile.equals(foldedDirectory)
                    || (foldedFile.startsWith(foldedDirectory + "/")
                        && !file.startsWith(directory + "/"))
                    || (foldedDirectory.startsWith(foldedFile + "/")
                        && !directory.startsWith(file + "/"))) {
                    throw new MigrationException("Staged Root Contains A Case-Aliased File And Directory: "
                        + file + " And " + directory);
                }
            }
        }
    }

    private static String foldPath(String path) {
        return Normalizer.normalize(path, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static String sha256(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream stream = new DigestInputStream(input, digest)) {
                stream.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new MigrationException("SHA-256 Is Unavailable", exception);
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

    private static String message(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
            ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    public record Request(
        Path stagedRoot,
        VerifiedSnapshotAdmission verifiedSourceAdmission,
        SnapshotMetadata targetMetadata,
        Collection<SourceOwnerBinding> sourceOwnerBindings,
        Collection<ChangeTargetOwner> changeTargetOwners,
        Path exportRoot
    ) {
        public Request {
            stagedRoot = MigrationPaths.requirePath(stagedRoot, "stagedRoot");
            verifiedSourceAdmission = Objects.requireNonNull(verifiedSourceAdmission, "verifiedSourceAdmission");
            targetMetadata = Objects.requireNonNull(targetMetadata, "targetMetadata");
            sourceOwnerBindings = sourceOwnerBindings == null ? List.of() : List.copyOf(sourceOwnerBindings);
            changeTargetOwners = changeTargetOwners == null ? List.of() : List.copyOf(changeTargetOwners);
            exportRoot = MigrationPaths.requirePath(exportRoot, "exportRoot");
        }
    }

    public record SourceOwnerBinding(String relativePath, String owner) {
        public SourceOwnerBinding {
            relativePath = MigrationPaths.requireRelative(relativePath);
            owner = MigrationCanonical.requireText(owner, "owner");
        }
    }

    public record ChangeTargetOwner(String relativePath, String owner) {
        public ChangeTargetOwner {
            relativePath = MigrationPaths.requireRelative(relativePath);
            owner = MigrationCanonical.requireText(owner, "owner");
        }
    }

    private record ExistingExport(VerifiedSnapshotAdmission admission, boolean recoverablePartial) {
    }

    private record FileInfo(long size, String sha256) {
        private FileInfo {
            if (size < 0L) {
                throw new IllegalArgumentException("File Size Must Be Non-Negative");
            }
            sha256 = MigrationCanonical.requireDigest(sha256, "sha256");
        }
    }

    private record StagedTree(Map<String, FileInfo> files, Set<String> directories) {
        private StagedTree {
            files = Map.copyOf(new TreeMap<>(files));
            directories = Set.copyOf(new TreeSet<>(directories));
        }

        private boolean sameFiles(StagedTree other) {
            return files.equals(other.files);
        }
    }
}
