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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public final class DirectoryMigrationStager implements MigrationStager {
    private static final String QUARANTINE_ROOT = ".quarantine/migration";

    @Override
    public StagedMigration stage(Path sourceRoot, Path stagingRoot, MigrationPlan plan) throws IOException {
        Objects.requireNonNull(plan, "plan");
        QuarantineReport report = emptyReportFor(plan);
        return stage(sourceRoot, stagingRoot, plan, report, report.accept("offline-stager", Instant.EPOCH));
    }

    @Override
    public StagedMigration stage(Path sourceRoot, Path stagingRoot, MigrationPlan plan, QuarantineReport report, QuarantineAcceptance acceptance) throws IOException {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(report, "report");
        Path source = MigrationPaths.requireDirectory(sourceRoot, "sourceRoot");
        Path staging = prepareStagingRoot(stagingRoot, source);
        if (!plan.quarantineReportHash().equals(report.reportHash())) {
            throw new MigrationException("Migration Plan Does Not Match Quarantine Report");
        }
        report.requireAccepted(acceptance);

        SourceTree sourceTree = inspectSource(source);
        OperationSet operations = validateOperations(sourceTree, plan.operations());
        copyTree(source, staging);
        applyOperations(source, staging, operations);
        materializeQuarantine(source, staging, operations, report);
        verifyExpectedFiles(staging, sourceTree.files(), operations, report);
        MigrationPaths.requireNoSymlinkTree(staging);
        return new StagedMigration(staging, Optional.empty(), plan.planHash(), TreeDigest.of(staging));
    }

    public StagedMigration stage(Path sourceRoot, Path stagingRoot, MigrationPlan plan, QuarantineReport report) throws IOException {
        Objects.requireNonNull(report, "report");
        if (!report.records().isEmpty()) {
            throw new MigrationException("Explicit Quarantine Acceptance Is Required");
        }
        return stage(sourceRoot, stagingRoot, plan, report, report.accept("offline-stager", Instant.EPOCH));
    }

    private static QuarantineReport emptyReportFor(MigrationPlan plan) throws IOException {
        QuarantineReport empty = QuarantineReport.empty();
        if (!empty.reportHash().equals(plan.quarantineReportHash())) {
            throw new MigrationException("A Quarantine Report And Acceptance Are Required For This Plan");
        }
        return empty;
    }

    private static Path prepareStagingRoot(Path stagingRoot, Path source) throws IOException {
        Path staging = MigrationPaths.requirePath(stagingRoot, "stagingRoot");
        MigrationPaths.requireDistinctRoots(source, staging);
        MigrationPaths.requireWritableParent(staging);
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
        MigrationPaths.requireNoSymlinkTraversal(staging.getParent(), staging);
        return staging;
    }

    private static SourceTree inspectSource(Path source) throws IOException {
        Map<String, String> files = new TreeMap<>();
        Files.walkFileTree(source, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new MigrationException("Symbolic Link Directory Is Not Allowed: " + directory);
                }
                if (!directory.equals(source)) {
                    String relative = MigrationPaths.relative(source, directory);
                    if (isReservedQuarantinePath(relative)) {
                        validateReservedQuarantine(directory);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    rejectReservedPath(relative);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new MigrationException("Only Regular Files Are Allowed In A Staged Root: " + file);
                }
                String relative = MigrationPaths.relative(source, file);
                if (isReservedQuarantinePath(relative)) {
                    throw new MigrationException("The Reserved Quarantine Path Is Not A Source Path: " + relative);
                }
                if (TreeDigest.isEphemeralAssetCoordinatorLock(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                files.put(relative, sha256(file));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw new MigrationException("Cannot Inspect Source File: " + file, exception);
            }
        });
        return new SourceTree(files);
    }

    private static OperationSet validateOperations(SourceTree sourceTree, List<MigrationOperation> values) throws IOException {
        List<MigrationOperation> operations = new ArrayList<>(values == null ? List.of() : values);
        operations.sort(Comparator.comparing(DirectoryMigrationStager::canonicalOperation));
        Map<String, MigrationOperation> targets = new HashMap<>();
        Map<String, List<MigrationOperation>> sources = new HashMap<>();
        for (MigrationOperation operation : operations) {
            MigrationOperationType type;
            try {
                type = operation.type();
            } catch (RuntimeException exception) {
                throw new MigrationException("Unsupported Migration Operation Type: " + operation.kind(), exception);
            }
            if (type == MigrationOperationType.GENERATE) {
                throw new MigrationException("Generate Operations Require The Standalone Upgrade Stager");
            }
            if (type.sourceRequired()) {
                if (operation.sourcePath().isEmpty()) {
                    throw new MigrationException("Migration Operation Requires A Source Path");
                }
                if (!sourceTree.files().containsKey(operation.sourcePath())) {
                    throw new MigrationException("Migration Source File Is Missing: " + operation.sourcePath());
                }
                sources.computeIfAbsent(operation.sourcePath(), ignored -> new ArrayList<>()).add(operation);
                String expected = operation.sourceHash();
                if (!expected.isEmpty() && !expected.equals(sourceTree.files().get(operation.sourcePath()))) {
                    throw new MigrationException("Migration Source Hash Does Not Match: " + operation.sourcePath());
                }
                if (type == MigrationOperationType.CONVERT
                    && (operation.targetHash().isEmpty() || operation.targetHash().equals(sourceTree.files().get(operation.sourcePath())))) {
                    throw new MigrationException("Convert Operation Must Declare A Changed Target Hash: " + operation.sourcePath());
                }
            }
            if (type.targetRequired()) {
                if (operation.targetPath().isEmpty()) {
                    throw new MigrationException("Migration Operation Requires A Target Path");
                }
                rejectReservedPath(operation.targetPath());
                MigrationOperation previous = targets.putIfAbsent(operation.targetPath(), operation);
                if (previous != null) {
                    throw new MigrationException("Migration Target Collision: " + operation.targetPath());
                }
                if (sourceTree.files().containsKey(operation.targetPath()) && !operation.targetPath().equals(operation.sourcePath())) {
                    throw new MigrationException("Migration Target Collides With An Unaffected Source File: " + operation.targetPath());
                }
            } else if (!operation.targetPath().isEmpty()) {
                throw new MigrationException("Delete Operation Must Not Declare A Target Path");
            }
            if (!operation.targetHash().isEmpty() && !type.targetRequired()) {
                throw new MigrationException("Delete Operation Must Not Declare A Target Hash");
            }
        }
        for (Map.Entry<String, List<MigrationOperation>> entry : sources.entrySet()) {
            String path = entry.getKey();
            List<MigrationOperation> uses = entry.getValue();
            boolean destructive = uses.stream().anyMatch(operation -> !operation.type().preservesSource());
            if (destructive && uses.size() > 1) {
                throw new MigrationException("A Destructive Migration Source Is Used More Than Once: " + path);
            }
        }
        return new OperationSet(List.copyOf(operations), Map.copyOf(targets));
    }

    private static void applyOperations(Path source, Path staging, OperationSet operationSet) throws IOException {
        List<String> destructiveSources = new ArrayList<>();
        for (MigrationOperation operation : operationSet.operations()) {
            MigrationOperationType type = operation.type();
            Path sourcePath = type.sourceRequired() ? MigrationPaths.resolveInside(source, operation.sourcePath()) : null;
            Path stagedSource = type.sourceRequired() ? MigrationPaths.resolveInside(staging, operation.sourcePath()) : null;
            if (type.sourceRequired() && !operation.sourceHash().isEmpty() && !operation.sourceHash().equals(sha256(sourcePath))) {
                throw new MigrationException("Migration Source Hash Changed During Staging: " + operation.sourcePath());
            }
            if (type == MigrationOperationType.DELETE) {
                Files.delete(stagedSource);
                continue;
            }
            Path target = MigrationPaths.resolveInside(staging, operation.targetPath());
            ensureParent(target, staging);
            if (!target.equals(stagedSource)) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new MigrationException("Migration Target Was Written Before Application: " + operation.targetPath());
                }
                AtomicFiles.copy(sourcePath, target);
            }
            if (!operation.targetHash().isEmpty() && !operation.targetHash().equals(sha256(target))) {
                throw new MigrationException("Migration Target Hash Does Not Match: " + operation.targetPath());
            }
            if (!type.preservesSource() && !operation.sourcePath().equals(operation.targetPath())) {
                destructiveSources.add(operation.sourcePath());
            }
        }
        for (String sourcePath : destructiveSources) {
            Path target = MigrationPaths.resolveInside(staging, sourcePath);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(target);
            }
        }
    }

    private static void materializeQuarantine(Path source, Path staging, OperationSet operations, QuarantineReport report) throws IOException {
        for (QuarantineRecord record : report.records()) {
            String recordId = requireRecordId(record.recordId());
            String sourceLocation = MigrationPaths.requireRelative(record.sourceLocation());
            rejectReservedPath(sourceLocation);
            Path sourcePath = MigrationPaths.resolveInside(source, sourceLocation);
            if (Files.isSymbolicLink(sourcePath) || !Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Quarantine Source Is Not A Regular File: " + sourceLocation);
            }
            if (!record.sourceHash().isEmpty() && !record.sourceHash().equals(sha256(sourcePath))) {
                throw new MigrationException("Quarantine Source Hash Does Not Match: " + sourceLocation);
            }
            Path destination = MigrationPaths.resolveInside(staging, QUARANTINE_ROOT + "/" + recordId + "/" + sourceLocation);
            ensureParent(destination, staging);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Quarantine Target Collision: " + destination);
            }
            if (operations.targets().containsKey(sourceLocation)) {
                throw new MigrationException("Quarantine Source Collides With A Declared Target: " + sourceLocation);
            }
            AtomicFiles.copy(sourcePath, destination);
            Path activeSource = MigrationPaths.resolveInside(staging, sourceLocation);
            if (Files.exists(activeSource, LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(activeSource);
            }
        }
    }

    private static void verifyExpectedFiles(Path staging, Map<String, String> sourceFiles, OperationSet operationSet, QuarantineReport report) throws IOException {
        Map<String, String> expected = new TreeMap<>(sourceFiles);
        expected.keySet().removeIf(TreeDigest::isEphemeralAssetCoordinatorLock);
        for (MigrationOperation operation : operationSet.operations()) {
            MigrationOperationType type = operation.type();
            if (!type.preservesSource()) {
                expected.remove(operation.sourcePath());
            }
            if (type.targetRequired()) {
                String targetHash = operation.targetHash().isEmpty() ? null : operation.targetHash();
                if (targetHash == null && operation.targetPath().equals(operation.sourcePath())) {
                    targetHash = sourceFiles.get(operation.sourcePath());
                }
                expected.put(operation.targetPath(), targetHash);
            }
        }
        for (QuarantineRecord record : report.records()) {
            String sourceLocation = MigrationPaths.requireRelative(record.sourceLocation());
            String sourceHash = sourceFiles.get(sourceLocation);
            if (sourceHash == null) {
                throw new MigrationException("Quarantine Source Is Not In The Source File Set: " + sourceLocation);
            }
            expected.remove(sourceLocation);
            expected.put(QUARANTINE_ROOT + "/" + requireRecordId(record.recordId()) + "/" + sourceLocation, sourceHash);
        }
        Map<String, String> actual = new TreeMap<>();
        Files.walkFileTree(staging, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new MigrationException("Only Regular Files Are Allowed In A Staged Root: " + file);
                }
                String relative = MigrationPaths.relative(staging, file);
                if (TreeDigest.isEphemeralAssetCoordinatorLock(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                actual.put(relative, sha256(file));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw new MigrationException("Cannot Verify Staged Root: " + file, exception);
            }
        });
        if (!expected.keySet().equals(actual.keySet())) {
            Set<String> unexpected = new HashSet<>(actual.keySet());
            unexpected.removeAll(expected.keySet());
            Set<String> missing = new HashSet<>(expected.keySet());
            missing.removeAll(actual.keySet());
            throw new MigrationException("Staged Root File Set Does Not Match Plan: unexpected=" + unexpected + ", missing=" + missing);
        }
        for (Map.Entry<String, String> expectedFile : expected.entrySet()) {
            if (expectedFile.getValue() != null && !expectedFile.getValue().equals(actual.get(expectedFile.getKey()))) {
                throw new MigrationException("Staged File Hash Does Not Match: " + expectedFile.getKey());
            }
        }
    }

    private static void copyTree(Path source, Path staging) throws IOException {
        Files.walkFileTree(source, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new MigrationException("Symbolic Link Directory Is Not Allowed: " + directory);
                }
                if (!directory.equals(source)) {
                    String relative = MigrationPaths.relative(source, directory);
                    if (isReservedQuarantinePath(relative)) {
                        validateReservedQuarantine(directory);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                Path destination = directory.equals(source) ? staging : MigrationPaths.resolveInside(staging, MigrationPaths.relative(source, directory));
                MigrationPaths.requireNoSymlinkTraversal(staging, destination);
                Files.createDirectories(destination);
                MigrationPaths.requireNoSymlinkTraversal(staging, destination);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new MigrationException("Only Regular Files Are Allowed In A Staged Root: " + file);
                }
                String relative = MigrationPaths.relative(source, file);
                if (isReservedQuarantinePath(relative)) {
                    throw new MigrationException("The Reserved Quarantine Path Must Be A Directory: " + relative);
                }
                if (TreeDigest.isEphemeralAssetCoordinatorLock(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                Path destination = MigrationPaths.resolveInside(staging, relative);
                ensureParent(destination, staging);
                AtomicFiles.copy(file, destination);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw new MigrationException("Cannot Copy Staged Root: " + file, exception);
            }
        });
    }

    private static void ensureParent(Path destination, Path staging) throws IOException {
        Path parent = destination.getParent();
        if (parent == null) {
            throw new MigrationException("Staged Destination Has No Parent: " + destination);
        }
        MigrationPaths.requireNoSymlinkTraversal(staging, parent);
        Files.createDirectories(parent);
        MigrationPaths.requireNoSymlinkTraversal(staging, parent);
    }

    private static void rejectReservedPath(String path) throws IOException {
        MigrationPaths.requireRelative(path);
        if (isReservedQuarantinePath(path)) {
            throw new MigrationException("The Reserved Quarantine Path Cannot Be A Migration Target: " + path);
        }
    }

    private static boolean isReservedQuarantinePath(String path) {
        return path.equals(".quarantine") || path.startsWith(".quarantine/");
    }

    private static void validateReservedQuarantine(Path quarantine) throws IOException {
        Files.walkFileTree(quarantine, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new MigrationException("Symbolic Link Directory Is Not Allowed: " + directory);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new MigrationException("Only Regular Files Are Allowed In Reserved Quarantine: " + file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw new MigrationException("Cannot Inspect Reserved Quarantine: " + file, exception);
            }
        });
    }

    private static String requireRecordId(String value) throws IOException {
        if (value == null || value.isBlank() || value.contains("/") || value.contains("\\") || value.equals(".") || value.equals("..")) {
            throw new MigrationException("Quarantine Record ID Must Be One Safe Path Segment");
        }
        return value;
    }

    private static String canonicalOperation(MigrationOperation operation) {
        return operation.kind() + "\u0000" + operation.adapterId() + "\u0000" + operation.sourcePath() + "\u0000" + operation.targetPath() + "\u0000" + operation.sourceHash() + "\u0000" + operation.targetHash();
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path); var stream = new DigestInputStream(input, digest)) {
                stream.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new MigrationException("SHA-256 Is Unavailable", exception);
        }
    }

    private record SourceTree(Map<String, String> files) {
        private SourceTree {
            files = Map.copyOf(files);
        }
    }

    private record OperationSet(List<MigrationOperation> operations, Map<String, MigrationOperation> targets) {
        private OperationSet {
            operations = List.copyOf(operations);
            targets = Map.copyOf(targets);
        }
    }
}
