package restudio.resync.upgrade.sqlite;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.migration.ManagedFlowFileLegacyOwnershipManifest;
import restudio.resync.migration.ManagedFlowFileMigrationDiagnosticCode;
import restudio.resync.migration.ManagedFlowFileMigrationCompletion;
import restudio.resync.migration.ManagedFlowFileMigrationContract;
import restudio.resync.migration.ManagedFlowFileStoreContract;
import restudio.resync.migration.MigrationOperationType;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.migration.TreeDigest;
import restudio.resync.flow.migration.FlowGraphMigrationSchemaCatalog;
import restudio.resync.upgrade.ImmutableSnapshotAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotInput;
import restudio.resync.upgrade.flow.ManagedFlowFileMigrationProvider;
import restudio.resync.upgrade.flow.LegacyFlowGraphSnapshotAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class SqliteManagedFlowFileMigrationProvider implements ManagedFlowFileMigrationProvider {
    public static final String PROVIDER_ID = "resync.managed-flow-files.sqlite";
    private static final String FILE_ROOT = ManagedFlowFileStoreContract.ROOT_DIRECTORY;
    private static final String DATABASE_PATH = FILE_ROOT + "/" + ManagedFlowFileStoreContract.DATABASE_FILE;
    private static final String MIGRATION_ROOT = FILE_ROOT + "/" + ManagedFlowFileMigrationContract.MIGRATION_DIRECTORY;
    private static final String MANIFEST_PATH = MIGRATION_ROOT + "/" + ManagedFlowFileMigrationContract.MANIFEST_FILE;
    private static final String COMPLETION_PATH = MIGRATION_ROOT + "/" + ManagedFlowFileMigrationContract.COMPLETION_FILE;
    private static final Map<String, LegacyFlowGraphSnapshotAdapter.NodeSchema> PRODUCT_SCHEMAS = productSchemas();
    private static final Set<String> ACCEPTED_SOURCE_OWNERS = Set.of(
        ManagedFlowFileStoreContract.OWNER,
        ProductionPersistenceOwners.STANDALONE_ROOT);
    private static final String FILE_KIND = "FILE";
    private static final String DIRECTORY_KIND = "DIRECTORY";

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public Result produce(OfflineUpgradeSnapshotInput input, Collection<Graph> graphs,
                          Collection<String> quarantinedGraphPaths) throws IOException {
        boolean eligibleSource = graphs != null && graphs.stream()
            .anyMatch(graph -> LegacyFlowGraphSnapshotAdapter.isSupportedGraphType(graph.type()))
            || input != null && input.snapshot().directories().stream()
                .anyMatch(LegacyFlowGraphSnapshotAdapter::isSupportedGraphDirectory);
        return produce(input, graphs, quarantinedGraphPaths, eligibleSource);
    }

    @Override
    public Result produce(OfflineUpgradeSnapshotInput input, Collection<Graph> graphs,
                          Collection<String> quarantinedGraphPaths, boolean eligibleSource) throws IOException {
        Objects.requireNonNull(input, "input");
        List<Graph> orderedGraphs = new ArrayList<>(graphs == null ? List.of() : graphs);
        orderedGraphs.sort(Comparator.comparing(Graph::path));
        List<Graph> managedGraphs = orderedGraphs.stream()
            .filter(graph -> LegacyFlowGraphSnapshotAdapter.isSupportedGraphType(graph.type())).toList();
        Set<String> quarantined = new LinkedHashSet<>(quarantinedGraphPaths == null ? Set.of() : quarantinedGraphPaths);
        List<QuarantineRecord> quarantines = new ArrayList<>();
        Path sourceRoot = MigrationPaths.requireDirectory(input.provenanceRoot(), "managed flow-file source root");
        if (existingMigrationIsAdmitted(sourceRoot)) {
            return new Result(List.of(), List.of(DATABASE_PATH, MANIFEST_PATH, COMPLETION_PATH), List.of());
        }
        if (managedGraphs.isEmpty() && !eligibleSource) {
            return Result.empty();
        }
        String sourceArchiveIdentity = sourceArchiveIdentity(input, sourceRoot);
        ManagedFlowFileMigrationContract.SourceIdentity source = sourceIdentity(input, sourceRoot);
        Set<String> conflictedGraphs = new LinkedHashSet<>();
        Set<String> fileGraphPaths = new LinkedHashSet<>();
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        Map<String, Set<String>> candidateGraphs = new LinkedHashMap<>();
        for (Graph graph : orderedGraphs) {
            if (containsCanonicalManagedFileNode(graph)) {
                fileGraphPaths.add(graph.path());
            }
            if (quarantined.contains(graph.path())) {
                continue;
            }
            try {
                Map<String, Candidate> graphCandidates = inspectGraph(input, sourceRoot, graph);
                for (Candidate candidate : graphCandidates.values()) {
                    Candidate previous = candidates.putIfAbsent(candidate.logicalPath(), candidate);
                    if (previous != null && !sameCandidate(previous, candidate)) {
                        Set<String> references = new LinkedHashSet<>(candidateGraphs.getOrDefault(candidate.logicalPath(), Set.of()));
                        references.add(graph.path());
                        conflictedGraphs.addAll(references);
                        throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.PATH_CONFLICT,
                            "Two claimed graphs bind the same managed flow-file path to different source kinds or bytes.",
                            "Repair the conflicting static file paths before retrying the migration.",
                            new ArrayList<>(references));
                    }
                    candidateGraphs.computeIfAbsent(candidate.logicalPath(), ignored -> new LinkedHashSet<>()).add(graph.path());
                }
            } catch (GraphFailure failure) {
                quarantines.add(quarantine(graph, failure));
                quarantined.add(graph.path());
            } catch (RuntimeException | IOException failure) {
                quarantines.add(quarantine(graph, new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.GRAPH_INVALID,
                    "The graph could not be inspected for static managed flow-file paths: " + reason(failure),
                "Repair the graph document before retrying the migration.", List.of(graph.path()))));
                quarantined.add(graph.path());
            }
        }
        for (Graph graph : orderedGraphs) {
            if (conflictedGraphs.contains(graph.path()) && !quarantined.contains(graph.path()) && quarantines.stream()
                .noneMatch(value -> value.sourceLocation().equals(graph.path()))) {
                quarantines.add(quarantine(graph, new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.PATH_CONFLICT,
                    "Two claimed graphs bind the same managed flow-file path to different source kinds or bytes.",
                    "Repair the conflicting static file paths before retrying the migration.", List.of(graph.path()))));
                quarantined.add(graph.path());
            }
        }
        Set<String> survivingManagedGraphs = managedGraphs.stream().map(Graph::path)
            .filter(path -> !quarantined.contains(path)).collect(Collectors.toCollection(LinkedHashSet::new));
        if (!managedGraphs.isEmpty() && survivingManagedGraphs.isEmpty()) {
            return new Result(List.of(), List.of(), quarantines, Map.of());
        }
        if (!fileGraphPaths.isEmpty() && fileGraphPaths.stream().allMatch(quarantined::contains)) {
            return new Result(List.of(), List.of(), quarantines, Map.of());
        }
        if (!conflictedGraphs.isEmpty()) {
            candidates.entrySet().removeIf(entry -> candidateGraphs.getOrDefault(entry.getKey(), Set.of()).stream()
                .allMatch(conflictedGraphs::contains));
        }
        List<ManagedFlowFileMigrationContract.LogicalEntry> logicalEntries = candidates.values().stream()
            .sorted(Comparator.comparing(Candidate::logicalPath, SqliteManagedFlowFileMigrationProvider::compareLogicalPath))
            .map(Candidate::logicalEntry)
            .toList();
        try {
            ManagedFlowFileMigrationContract.validateLogicalEntries(logicalEntries);
        } catch (IllegalArgumentException failure) {
            Set<String> conflictPaths = conflictingPaths(candidates);
            Set<String> affectedGraphs = conflictPaths.stream()
                .flatMap(path -> candidateGraphs.getOrDefault(path, Set.of()).stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
            if (affectedGraphs.isEmpty()) {
                affectedGraphs.addAll(candidateGraphs.values().stream().flatMap(Set::stream).toList());
            }
            for (Graph graph : orderedGraphs) {
                if (affectedGraphs.contains(graph.path()) && !quarantined.contains(graph.path()) && quarantines.stream()
                    .noneMatch(value -> value.sourceLocation().equals(graph.path()))) {
                    quarantines.add(quarantine(graph, new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.PATH_CONFLICT,
                        "The claimed managed flow-file paths contain a file and directory prefix conflict.",
                        "Repair the static managed flow-file paths before retrying the migration.", List.of(graph.path()))));
                    quarantined.add(graph.path());
                }
            }
            Set<String> finalAffectedGraphs = Set.copyOf(affectedGraphs);
            candidates.entrySet().removeIf(entry -> candidateGraphs.getOrDefault(entry.getKey(), Set.of()).stream()
                .allMatch(finalAffectedGraphs::contains));
            logicalEntries = candidates.values().stream()
                .sorted(Comparator.comparing(Candidate::logicalPath, SqliteManagedFlowFileMigrationProvider::compareLogicalPath))
                .map(Candidate::logicalEntry).toList();
            try {
                ManagedFlowFileMigrationContract.validateLogicalEntries(logicalEntries);
            } catch (IllegalArgumentException ignored) {
                logicalEntries = List.of();
                candidates.clear();
            }
        }
        ManagedFlowFileLegacyOwnershipManifest manifest = new ManagedFlowFileLegacyOwnershipManifest(source,
            logicalEntries.stream().map(value -> new ManagedFlowFileMigrationContract.LegacyEntry(
                value.logicalPath(), value.kind(), value.size(), value.sha256())).toList());
        String logicalContentHash = ManagedFlowFileMigrationContract.canonicalLogicalContentHash(logicalEntries);
        String identitySeed = digest("managed-flow-files\u0000" + source.snapshotId() + "\u0000"
            + source.installId() + "\u0000" + sourceArchiveIdentity + "\u0000" + manifest.manifestHash());
        String migrationId = migrationId(input.root(), identitySeed);
        String targetInstallIdentity = source.installId();
        ManagedFlowFileMigrationContract.StoreMetadata metadata = ManagedFlowFileMigrationContract.StoreMetadata
            .migratedPending(targetInstallIdentity, source, sourceArchiveIdentity, migrationId);
        byte[] database = buildDatabase(metadata, logicalEntries);
        String physicalDatabaseHash = ManagedFlowFileMigrationContract.sha256(database);
        ManagedFlowFileMigrationCompletion completion = new ManagedFlowFileMigrationCompletion(
            1, migrationId, manifest.manifestHash(), ManagedFlowFileMigrationContract.DatabaseIdentity.current(),
            manifest.entryCount(), logicalContentHash, physicalDatabaseHash, source, sourceArchiveIdentity,
            targetInstallIdentity);
        ExistingState existing = existingState(sourceRoot, manifest, completion);
        List<String> targetPaths = List.of(DATABASE_PATH, MANIFEST_PATH, COMPLETION_PATH);
        if (existing == ExistingState.VALID) {
            return new Result(List.of(), targetPaths, quarantines);
        }
        if (existing == ExistingState.TAMPERED) {
            if (orderedGraphs.isEmpty()) {
                throw new IOException("Managed flow-file migration records are tampered for a zero-entry source");
            }
            Graph anchor = managedGraphs.stream().filter(value -> !quarantined.contains(value.path())).findFirst()
                .orElse(orderedGraphs.getFirst());
            GraphFailure failure = new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.TARGET_TAMPERED,
                "An existing managed flow-file migration is incomplete or does not match its bound records.",
                "Restore the migration archive or remove the incomplete managed store before retrying the migration.",
                targetPaths);
            quarantines.add(quarantine(anchor, failure));
            return new Result(List.of(), List.of(), quarantines);
        }
        Set<String> contributors = candidates.isEmpty()
            ? Set.copyOf(survivingManagedGraphs)
            : candidateGraphs.values().stream().flatMap(Set::stream)
                .filter(path -> !quarantined.contains(path)).collect(Collectors.toUnmodifiableSet());
        String anchor = contributors.stream().sorted().findFirst()
            .orElse(survivingManagedGraphs.stream().sorted().findFirst().orElse(""));
        Map<String, Set<String>> generatedContributors = Map.of(
            DATABASE_PATH, contributors,
            MANIFEST_PATH, contributors,
            COMPLETION_PATH, contributors);
        List<OfflineUpgradeSnapshotAdapter.FileTransform> files = List.of(
            new OfflineUpgradeSnapshotAdapter.FileTransform(anchor, DATABASE_PATH, database, MigrationOperationType.GENERATE),
            new OfflineUpgradeSnapshotAdapter.FileTransform(anchor, MANIFEST_PATH, manifest.canonicalBytes(), MigrationOperationType.GENERATE),
            new OfflineUpgradeSnapshotAdapter.FileTransform(anchor, COMPLETION_PATH, completion.canonicalBytes(), MigrationOperationType.GENERATE));
        return new Result(files, List.of(), quarantines, generatedContributors);
    }

    private Map<String, Candidate> inspectGraph(OfflineUpgradeSnapshotInput input, Path sourceRoot, Graph graph)
        throws IOException, GraphFailure {
        if (!LegacyFlowGraphSnapshotAdapter.isSupportedGraphType(graph.type())) {
            return Map.of();
        }
        Object decoded = CanonicalCodec.decodePermissive(graph.bytes()).toJava();
        if (!(decoded instanceof Map<?, ?> rawRoot)) {
            throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.GRAPH_INVALID, "The graph document is not an object.",
                "Repair the graph document before retrying the migration.", List.of(graph.path()));
        }
        Map<String, Object> root = object(rawRoot, "graph");
        if (!graph.type().equals(text(root.get("resourceType"))) || !graph.id().equals(text(root.get("id")))) {
            throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.GRAPH_INVALID,
                "The graph identity does not match the graph owner declaration.",
                "Repair the graph resource type and identity before retrying the migration.", List.of(graph.path()));
        }
        Map<String, Object> nodes = objectOrEmpty(root.get("nodes"));
        Map<String, Set<String>> connectedPins = connectedPins(root.get("connections"));
        Map<String, String> expectedKinds = new LinkedHashMap<>();
        for (String nodeId : nodes.keySet().stream().sorted().toList()) {
            Object rawNode = nodes.get(nodeId);
            if (!(rawNode instanceof Map<?, ?> rawMap)) {
                throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.GRAPH_INVALID, "A graph node is not an object.",
                    "Repair the graph node before retrying the migration.", List.of(graph.path(), nodeId));
            }
            Map<String, Object> node = object(rawMap, "node");
            NodeDescriptor fileNode = canonicalFileNode(node);
            if (fileNode == null) {
                continue;
            }
            Map<String, Object> values = objectOrEmpty(node.get("inputValues"));
            List<String> pins = fileNode.pathPins();
            String expectedKind = fileNode.directory() ? DIRECTORY_KIND : FILE_KIND;
            for (String pin : pins) {
                if (connectedPins.getOrDefault(nodeId, Set.of()).contains(pin)) {
                    throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.PATH_CONNECTED,
                        "A managed flow-file path is connected to another node and is not statically provable.",
                        "Replace the connected path with a static literal or accept the graph quarantine.",
                        List.of(graph.path(), nodeId, pin));
                }
                Object rawPath = values.get(pin);
                if (!(rawPath instanceof String path)) {
                    throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.PATH_AMBIGUOUS,
                        "A managed flow-file path is missing or is not a string literal.",
                        "Replace the path with a static literal or accept the graph quarantine.",
                        List.of(graph.path(), nodeId, pin));
                }
                if (path.indexOf('{') >= 0 || path.indexOf('}') >= 0) {
                    throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.PATH_TEMPLATE,
                        "A managed flow-file path contains a template expression.",
                        "Replace the template with a static literal or accept the graph quarantine.",
                        List.of(graph.path(), nodeId, pin));
                }
                String logicalPath;
                try {
                    logicalPath = ManagedFlowFileMigrationContract.requireLogicalPath(path);
                } catch (IllegalArgumentException failure) {
                    throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.PATH_UNSAFE,
                        "A managed flow-file path is unsafe: " + reason(failure),
                        "Replace the path with a safe relative literal or accept the graph quarantine.",
                        List.of(graph.path(), nodeId, pin, path));
                }
                String previousKind = expectedKinds.putIfAbsent(logicalPath, expectedKind);
                if (previousKind != null && !previousKind.equals(expectedKind)) {
                    throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.PATH_AMBIGUOUS,
                        "The same managed flow-file path is used as both a file and a directory.",
                        "Use one path kind consistently or accept the graph quarantine.", List.of(graph.path(), logicalPath));
                }
            }
        }
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (Map.Entry<String, String> path : expectedKinds.entrySet()) {
            Candidate candidate = sourceCandidate(input, sourceRoot, path.getKey(), path.getValue());
            if (candidate != null) {
                candidates.put(candidate.logicalPath(), candidate);
                addParentDirectories(input, sourceRoot, candidates, candidate.logicalPath(), graph.path());
            }
        }
        return candidates;
    }

    private Candidate sourceCandidate(OfflineUpgradeSnapshotInput input, Path sourceRoot, String logicalPath,
                                      String expectedKind) throws IOException, GraphFailure {
        Path sourcePath;
        try {
            sourcePath = MigrationPaths.resolveInside(sourceRoot, logicalPath);
        } catch (RuntimeException failure) {
            throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.SOURCE_UNSAFE,
                "The managed flow-file source path cannot be safely resolved: " + reason(failure),
                "Repair the source tree or accept the graph quarantine.", List.of(logicalPath));
        }
        if (Files.notExists(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
            throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.SOURCE_MISSING,
                "The statically claimed managed flow-file source does not exist.",
                "Restore the source path or accept the graph quarantine.", List.of(logicalPath));
        }
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(sourcePath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException failure) {
            throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.SOURCE_UNSAFE,
                "The managed flow-file source cannot be inspected: " + reason(failure),
                "Repair the source tree or accept the graph quarantine.", List.of(logicalPath));
        }
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.SOURCE_SYMLINK,
                "The statically claimed managed flow-file source is a symbolic link or reparse object.",
                "Replace the source object with a regular file or directory before retrying the migration.", List.of(logicalPath));
        }
        String actualKind = attributes.isDirectory() ? DIRECTORY_KIND : attributes.isRegularFile() ? FILE_KIND : "";
        if (actualKind.isBlank() || !actualKind.equals(expectedKind)) {
            throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.SOURCE_KIND,
                "The statically claimed managed flow-file source kind does not match the graph operation.",
                "Repair the source path kind or accept the graph quarantine.", List.of(logicalPath));
        }
        if (DIRECTORY_KIND.equals(actualKind)) {
            if (!input.snapshot().directories().contains(logicalPath)) {
                throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.SOURCE_NOT_MANIFESTED,
                    "The claimed managed flow-file directory is not present in the explicit snapshot manifest.",
                    "Regenerate the snapshot manifest before retrying the migration.", List.of(logicalPath));
            }
            return new Candidate(logicalPath, ManagedFlowFileMigrationContract.EntryKind.DIRECTORY, new byte[0]);
        }
        ImmutableSnapshotAdapter.Entry entry = input.snapshot().entries().stream()
            .filter(value -> value.relativePath().equals(logicalPath)).findFirst().orElse(null);
        if (entry == null) {
            throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.SOURCE_NOT_MANIFESTED,
                "The claimed managed flow-file source is not present in the explicit snapshot manifest.",
                "Regenerate the snapshot manifest before retrying the migration.", List.of(logicalPath));
        }
        if (!ACCEPTED_SOURCE_OWNERS.contains(entry.owner())) {
            throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.PARTICIPANT_OWNERSHIP,
                "The claimed managed flow-file source belongs to another persistence participant.",
                "Repair the participant ownership or accept the graph quarantine.", List.of(logicalPath, entry.owner()));
        }
        byte[] bytes = Files.readAllBytes(sourcePath);
        String digest = ManagedFlowFileMigrationContract.sha256(bytes);
        if (bytes.length != entry.size() || !digest.equalsIgnoreCase(entry.sha256())) {
            throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.SOURCE_TAMPERED,
                "The claimed managed flow-file source differs from the explicit snapshot manifest.",
                "Restore the source snapshot or retry from a fresh verified snapshot.", List.of(logicalPath));
        }
        return new Candidate(logicalPath, ManagedFlowFileMigrationContract.EntryKind.FILE, bytes);
    }

    private void addParentDirectories(OfflineUpgradeSnapshotInput input, Path sourceRoot,
                                      Map<String, Candidate> candidates, String logicalPath, String graphPath)
        throws IOException, GraphFailure {
        int separator = logicalPath.indexOf('/');
        while (separator >= 0) {
            String parent = logicalPath.substring(0, separator);
            Candidate existing = candidates.get(parent);
            if (existing != null) {
                if (existing.kind() != ManagedFlowFileMigrationContract.EntryKind.DIRECTORY) {
                    throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.PATH_CONFLICT,
                        "A managed flow-file path is both a file and a parent directory.",
                        "Repair the static paths before retrying the migration.", List.of(graphPath, parent));
                }
            } else {
                Path parentPath = MigrationPaths.resolveInside(sourceRoot, parent);
                if (!Files.isDirectory(parentPath, LinkOption.NOFOLLOW_LINKS) || !input.snapshot().directories().contains(parent)) {
                    throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.SOURCE_NOT_MANIFESTED,
                        "A required parent directory is not present in the explicit source manifest.",
                        "Regenerate the source snapshot or accept the graph quarantine.", List.of(graphPath, parent));
                }
                candidates.put(parent, new Candidate(parent, ManagedFlowFileMigrationContract.EntryKind.DIRECTORY, new byte[0]));
            }
            separator = logicalPath.indexOf('/', separator + 1);
        }
    }

    private static Map<String, Set<String>> connectedPins(Object rawConnections) throws GraphFailure {
        Map<String, Set<String>> result = new HashMap<>();
        if (rawConnections == null) {
            return result;
        }
        if (!(rawConnections instanceof List<?> connections)) {
            throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.GRAPH_INVALID,
                "The graph connections field is not an array.",
                "Repair the graph connections before retrying the migration.", List.of());
        }
        for (Object rawConnection : connections) {
            if (!(rawConnection instanceof Map<?, ?> raw)) {
                throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.GRAPH_INVALID,
                    "A graph connection is not an object.",
                    "Repair the graph connections before retrying the migration.", List.of());
            }
            Map<String, Object> connection = object(raw, "connection");
            Object rawNode = connection.get("targetNodeId");
            Object rawPin = connection.get("targetPin");
            if (!(rawNode instanceof String node) || node.isBlank()
                || !(rawPin instanceof String pin) || pin.isBlank()) {
                throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.GRAPH_INVALID,
                    "A graph connection has an invalid target node or pin.",
                    "Repair the graph connection before retrying the migration.", List.of());
            }
            result.computeIfAbsent(node, ignored -> new LinkedHashSet<>()).add(pin);
        }
        return result;
    }

    private static Set<String> conflictingPaths(Map<String, Candidate> candidates) {
        Map<String, List<String>> folded = new LinkedHashMap<>();
        for (String path : candidates.keySet()) {
            folded.computeIfAbsent(ManagedFlowFileMigrationContract.caseCollisionKey(path), ignored -> new ArrayList<>())
                .add(path);
        }
        Set<String> conflicts = new LinkedHashSet<>();
        for (List<String> paths : folded.values()) {
            if (paths.size() > 1) {
                conflicts.addAll(paths);
            }
        }
        List<String> sorted = candidates.keySet().stream()
            .sorted(Comparator.comparing(value -> value, SqliteManagedFlowFileMigrationProvider::compareLogicalPath)).toList();
        for (String path : sorted) {
            int separator = path.indexOf('/');
            while (separator >= 0) {
                String parent = path.substring(0, separator);
                Candidate candidate = candidates.get(parent);
                if (candidate != null && candidate.kind() != ManagedFlowFileMigrationContract.EntryKind.DIRECTORY) {
                    conflicts.add(parent);
                    conflicts.add(path);
                }
                separator = path.indexOf('/', separator + 1);
            }
        }
        return conflicts;
    }

    private static boolean sameCandidate(Candidate left, Candidate right) {
        return left.logicalPath().equals(right.logicalPath()) && left.kind() == right.kind()
            && Arrays.equals(left.content(), right.content());
    }

    private static NodeDescriptor canonicalFileNode(Map<String, Object> node) throws GraphFailure {
        String type = text(node.get("type"));
        LegacyFlowGraphSnapshotAdapter.NodeSchema schema = PRODUCT_SCHEMAS.get(type);
        if (schema == null) {
            return null;
        }
        Map<String, Object> identity = schema.identity();
        if (!"FileHandler".equals(text(identity.get("handler")))
            || !"DATABASE".equals(text(identity.get("category")))
            || !"trusted_server_flow".equals(text(identity.get("authorizationPolicy")))) {
            return null;
        }
        String expectedOperation = text(schema.handlerDefaults().get("operation"));
        if (!expectedOperation.matches("file_[a-z0-9_]+")) {
            return null;
        }
        Map<String, Object> handlerConfig = objectOrEmpty(node.get("handlerConfig"));
        String operation = text(handlerConfig.get("operation"));
        if (!operation.isBlank() && !expectedOperation.equals(operation)) {
            throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.GRAPH_INVALID,
                "The canonical File node carries an unexpected handler operation.",
                "Restore the generated File node definition before retrying the migration.", List.of(type, operation));
        }
        String handler = text(node.get("handler"));
        if (!handler.isBlank() && !"FileHandler".equals(handler)) {
            throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.GRAPH_INVALID,
                "The File node handler is not owned by the canonical File capability.",
                "Restore the canonical File node identity before retrying the migration.", List.of(type, handler));
        }
        String authorization = text(node.get("authorizationPolicy"));
        if (!authorization.isBlank() && !"trusted_server_flow".equals(authorization)) {
            throw new GraphFailure(ManagedFlowFileMigrationDiagnosticCode.GRAPH_INVALID,
                "The File node capability is not authorized for managed flow-file migration.",
                "Restore the canonical File node capability before retrying the migration.", List.of(type, authorization));
        }
        List<String> pathPins = schema.inputTypes().containsKey("source_path")
            && schema.inputTypes().containsKey("dest_path")
            ? List.of("source_path", "dest_path") : List.of("path");
        if (!schema.inputTypes().containsKey("path") && pathPins.size() == 1) {
            return null;
        }
        boolean directory = expectedOperation.equals("file_list_dir") || expectedOperation.equals("file_create_dir");
        return new NodeDescriptor(type, expectedOperation, pathPins, directory);
    }

    private static boolean containsCanonicalManagedFileNode(Graph graph) {
        if (!LegacyFlowGraphSnapshotAdapter.isSupportedGraphType(graph.type())) {
            return false;
        }
        try {
            Object decoded = CanonicalCodec.decodePermissive(graph.bytes()).toJava();
            if (!(decoded instanceof Map<?, ?> rawRoot)) {
                return false;
            }
            Map<String, Object> root = object(rawRoot, "graph");
            Map<String, Object> nodes = objectOrEmpty(root.get("nodes"));
            for (Object rawNode : nodes.values()) {
                if (!(rawNode instanceof Map<?, ?> rawMap)) {
                    continue;
                }
                try {
                    if (canonicalFileNode(object(rawMap, "node")) != null) {
                        return true;
                    }
                } catch (GraphFailure failure) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static Map<String, LegacyFlowGraphSnapshotAdapter.NodeSchema> productSchemas() {
        try (InputStream stream = SqliteManagedFlowFileMigrationProvider.class
            .getResourceAsStream(FlowGraphMigrationSchemaCatalog.RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Generated Flow schema is unavailable");
            }
            return LegacyFlowGraphSnapshotAdapter.loadProductSchemas(stream);
        } catch (IOException failure) {
            throw new IllegalStateException("Generated Flow schema cannot be read", failure);
        }
    }

    private static ManagedFlowFileMigrationContract.SourceIdentity sourceIdentity(OfflineUpgradeSnapshotInput input,
                                                                                   Path sourceRoot) throws IOException {
        String sourceInstall = null;
        ImmutableSnapshotAdapter.Entry identityEntry = input.snapshot().entries().stream()
            .filter(value -> value.relativePath().equals("server-id")).findFirst().orElse(null);
        if (identityEntry != null) {
            Path identityPath = MigrationPaths.resolveInside(sourceRoot, "server-id");
            byte[] bytes = Files.readAllBytes(identityPath);
            if (bytes.length != identityEntry.size()
                || !ManagedFlowFileMigrationContract.sha256(bytes).equalsIgnoreCase(identityEntry.sha256())) {
                throw new IOException("Server identity source differs from the explicit snapshot manifest");
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.endsWith("\n")) {
                text = text.substring(0, text.length() - 1);
            }
            try {
                sourceInstall = UUID.fromString(text).toString();
            } catch (IllegalArgumentException failure) {
                throw new IOException("Server identity source is not a canonical UUID", failure);
            }
        }
        if (sourceInstall == null) {
            sourceInstall = "source-install-" + digest(input.snapshot().metadata().snapshotId() + "\u0000"
                + input.snapshot().manifestHash()).substring(0, 32);
        }
        return new ManagedFlowFileMigrationContract.SourceIdentity(input.snapshot().metadata().snapshotId(), sourceInstall);
    }

    private static String sourceArchiveIdentity(OfflineUpgradeSnapshotInput input, Path sourceRoot) throws IOException {
        try {
            return TreeDigest.of(sourceRoot);
        } catch (IOException | RuntimeException failure) {
            return digest("managed-flow-files-source-archive\u0000" + input.snapshot().manifestHash());
        }
    }

    private static byte[] buildDatabase(ManagedFlowFileMigrationContract.StoreMetadata metadata,
                                        List<ManagedFlowFileMigrationContract.LogicalEntry> entries) throws IOException {
        Path directory;
        try {
            directory = Files.createTempDirectory("resync-managed-flow-files-");
        } catch (IOException failure) {
            throw new IOException("Cannot create managed flow-file SQLite temporary directory", failure);
        }
        Path database = directory.resolve(ManagedFlowFileStoreContract.DATABASE_FILE);
        try {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA foreign_keys = ON");
                    statement.execute("PRAGMA busy_timeout = 5000");
                    statement.execute("PRAGMA journal_mode = DELETE");
                    statement.execute("PRAGMA synchronous = FULL");
                    statement.execute(ManagedFlowFileStoreContract.createMetadataTableSql());
                    statement.execute(ManagedFlowFileStoreContract.createFilesTableSql());
                }
                connection.setAutoCommit(false);
                insertMetadata(connection, metadata);
                insertEntry(connection, "", DIRECTORY_KIND, new byte[0]);
                for (ManagedFlowFileMigrationContract.LogicalEntry entry : entries.stream()
                    .sorted(ManagedFlowFileMigrationContract.logicalEntryComparator()).toList()) {
                    insertEntry(connection, entry.logicalPath(), entry.kind() == ManagedFlowFileMigrationContract.EntryKind.FILE
                        ? FILE_KIND : DIRECTORY_KIND, entry.content());
                }
                connection.commit();
                connection.setAutoCommit(true);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("VACUUM");
                }
            } catch (SQLException failure) {
                throw new IOException("Cannot create canonical managed flow-file SQLite database", failure);
            }
            force(database);
            try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
                for (Path child : children) {
                    if (!child.equals(database)) {
                        throw new IOException("Managed flow-file SQLite writer left a sidecar file: " + child);
                    }
                }
            }
            return Files.readAllBytes(database);
        } finally {
            deleteTree(directory);
        }
    }

    private static void insertMetadata(Connection connection, ManagedFlowFileMigrationContract.StoreMetadata metadata)
        throws SQLException {
        String sql = "INSERT INTO " + ManagedFlowFileStoreContract.METADATA_TABLE
            + "(id, schema_id, format_version, writer_id, writer_version, origin, contract_version, install_identity, "
            + "source_snapshot_identity, source_install_identity, source_archive_identity, migration_id, "
            + "completion_authority_hash, completion_hash) VALUES(1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, metadata.schemaId());
            statement.setInt(2, metadata.formatVersion());
            statement.setString(3, metadata.writerId());
            statement.setInt(4, metadata.writerVersion());
            statement.setString(5, metadata.origin().wireValue());
            statement.setInt(6, metadata.contractVersion());
            statement.setString(7, metadata.installIdentity());
            statement.setString(8, metadata.sourceSnapshotId());
            statement.setString(9, metadata.sourceInstallId());
            statement.setString(10, metadata.sourceArchiveIdentity());
            statement.setString(11, metadata.migrationId());
            statement.setNull(12, Types.VARCHAR);
            statement.setNull(13, Types.VARCHAR);
            statement.executeUpdate();
        }
    }

    private static void insertEntry(Connection connection, String path, String kind, byte[] content) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO " + ManagedFlowFileStoreContract.FILES_TABLE + "(path, kind, content) VALUES(?, ?, ?)")) {
            statement.setString(1, path);
            statement.setString(2, kind);
            statement.setBytes(3, content);
            statement.executeUpdate();
        }
    }

    private static ExistingState existingState(Path sourceRoot,
                                                ManagedFlowFileLegacyOwnershipManifest expectedManifest,
                                                ManagedFlowFileMigrationCompletion expectedCompletion) throws IOException {
        List<Path> targets = List.of(sourceRoot.resolve(DATABASE_PATH), sourceRoot.resolve(MANIFEST_PATH),
            sourceRoot.resolve(COMPLETION_PATH));
        boolean any = targets.stream().anyMatch(path -> Files.exists(path, LinkOption.NOFOLLOW_LINKS));
        if (!any) {
            return ExistingState.NONE;
        }
        if (targets.stream().anyMatch(path -> !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
            return ExistingState.TAMPERED;
        }
        try {
            if (!hasExactMigrationRecords(sourceRoot)) {
                return ExistingState.TAMPERED;
            }
            ManagedFlowFileLegacyOwnershipManifest manifest = ManagedFlowFileLegacyOwnershipManifest.read(
                Files.readAllBytes(sourceRoot.resolve(MANIFEST_PATH)));
            ManagedFlowFileMigrationCompletion completion = ManagedFlowFileMigrationCompletion.read(
                Files.readAllBytes(sourceRoot.resolve(COMPLETION_PATH)));
            if (!manifest.canonicalText().equals(expectedManifest.canonicalText())) {
                return ExistingState.TAMPERED;
            }
            if (!completion.canonicalText().equals(expectedCompletion.canonicalText())) {
                return ExistingState.TAMPERED;
            }
            ManagedFlowFileMigrationContract.StoreMetadata metadata = readMetadata(sourceRoot.resolve(DATABASE_PATH));
            if (metadata.origin() == ManagedFlowFileMigrationContract.Origin.OFFLINE_MIGRATION_PENDING) {
                if (!expectedCompletion.matchesPending(manifest, metadata)
                    || !ManagedFlowFileMigrationContract.sha256(Files.readAllBytes(sourceRoot.resolve(DATABASE_PATH)))
                    .equals(expectedCompletion.physicalDatabaseHash())) {
                    return ExistingState.TAMPERED;
                }
            } else if (metadata.origin() == ManagedFlowFileMigrationContract.Origin.OFFLINE_MIGRATION_ADMITTED) {
                if (!expectedCompletion.matchesAdmitted(manifest, metadata)) {
                    return ExistingState.TAMPERED;
                }
            } else {
                return ExistingState.TAMPERED;
            }
            return ExistingState.VALID;
        } catch (RuntimeException | SQLException failure) {
            return ExistingState.TAMPERED;
        }
    }

    private static boolean existingMigrationIsAdmitted(Path sourceRoot) throws IOException {
        Path database = MigrationPaths.resolveInside(sourceRoot, DATABASE_PATH);
        Path manifest = MigrationPaths.resolveInside(sourceRoot, MANIFEST_PATH);
        Path completion = MigrationPaths.resolveInside(sourceRoot, COMPLETION_PATH);
        boolean any = List.of(database, manifest, completion).stream()
            .anyMatch(path -> Files.exists(path, LinkOption.NOFOLLOW_LINKS));
        if (!any) {
            return false;
        }
        if (List.of(database, manifest, completion).stream()
            .anyMatch(path -> !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
            return false;
        }
        try {
            if (!hasExactMigrationRecords(sourceRoot)) {
                return false;
            }
            ManagedFlowFileLegacyOwnershipManifest ownership = ManagedFlowFileLegacyOwnershipManifest.read(
                Files.readAllBytes(manifest));
            ManagedFlowFileMigrationCompletion value = ManagedFlowFileMigrationCompletion.read(
                Files.readAllBytes(completion));
            ManagedFlowFileMigrationContract.StoreMetadata metadata = readMetadata(database);
            if (metadata.origin() == ManagedFlowFileMigrationContract.Origin.OFFLINE_MIGRATION_PENDING) {
                return value.matchesPending(ownership, metadata)
                    && ManagedFlowFileMigrationContract.sha256(Files.readAllBytes(database))
                    .equals(value.physicalDatabaseHash());
            }
            return metadata.origin() == ManagedFlowFileMigrationContract.Origin.OFFLINE_MIGRATION_ADMITTED
                && value.matchesAdmitted(ownership, metadata);
        } catch (RuntimeException | SQLException failure) {
            return false;
        }
    }

    private static boolean hasExactMigrationRecords(Path sourceRoot) throws IOException {
        Path migration = MigrationPaths.resolveInside(sourceRoot, MIGRATION_ROOT);
        if (!Files.isDirectory(migration, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        MigrationPaths.requireNoSymlinkTree(migration);
        try (DirectoryStream<Path> children = Files.newDirectoryStream(migration)) {
            List<String> names = new ArrayList<>();
            for (Path child : children) {
                names.add(child.getFileName().toString());
            }
            return names.stream().sorted().toList().equals(List.of(
                ManagedFlowFileMigrationContract.MANIFEST_FILE,
                ManagedFlowFileMigrationContract.COMPLETION_FILE));
        }
    }

    private static ManagedFlowFileMigrationContract.StoreMetadata readMetadata(Path database) throws SQLException {
        try (Connection connection = readOnlyConnection(database)) {
            String query = "SELECT schema_id, format_version, writer_id, writer_version, origin, contract_version, "
                + "install_identity, source_snapshot_identity, source_install_identity, source_archive_identity, migration_id, "
                + "completion_authority_hash, completion_hash FROM " + ManagedFlowFileStoreContract.METADATA_TABLE
                + " WHERE id = 1";
            try (PreparedStatement statement = connection.prepareStatement(query); ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Managed flow-file metadata is missing");
                }
                return new ManagedFlowFileMigrationContract.StoreMetadata(
                    result.getString(1), result.getInt(2), result.getString(3), result.getInt(4),
                    ManagedFlowFileMigrationContract.Origin.parse(result.getString(5)), result.getInt(6),
                    result.getString(7), result.getString(8), result.getString(9), result.getString(10),
                    result.getString(11), result.getString(12), result.getString(13));
            }
        }
    }

    private static Connection readOnlyConnection(Path database) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only = ON");
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
        return connection;
    }

    private static QuarantineRecord quarantine(Graph graph, GraphFailure failure) {
        String hash = ManagedFlowFileMigrationContract.sha256(graph.bytes());
        String recordId = "managed-flow-files-" + digest(failure.code + "\u0000" + graph.path() + "\u0000" + hash
            + "\u0000" + failure.reason).substring(0, 24);
        return new QuarantineRecord(recordId, failure.code, graph.path(), failure.reason, failure.references,
            failure.action, hash);
    }

    private static Map<String, Object> object(Object value, String label) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(label + " contains a non-string key");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static Map<String, Object> objectOrEmpty(Object value) {
        return value instanceof Map<?, ?> ? object(value, "object") : Map.of();
    }

    private static String text(Object value) {
        return value instanceof String string ? string : "";
    }

    private static int compareLogicalPath(String left, String right) {
        return restudio.resync.flow.canonical.CanonicalJson.compareCodePoints(left, right);
    }

    private static String digest(String value) {
        return ManagedFlowFileMigrationContract.sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String migrationId(Path sourceRoot, String identitySeed) {
        Path name = sourceRoot.getFileName();
        String value = name == null ? "" : name.toString();
        return value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
            ? value : "managed-flow-files-" + identitySeed.substring(0, 32);
    }

    private static String reason(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
            ? failure.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
    }

    private static void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private enum ExistingState {
        NONE,
        VALID,
        TAMPERED
    }

    private record Candidate(String logicalPath, ManagedFlowFileMigrationContract.EntryKind kind, byte[] content) {
        private Candidate {
            logicalPath = ManagedFlowFileMigrationContract.requireLogicalPath(logicalPath);
            kind = Objects.requireNonNull(kind, "kind");
            content = content.clone();
            if (kind == ManagedFlowFileMigrationContract.EntryKind.DIRECTORY && content.length != 0) {
                throw new IllegalArgumentException("Managed flow-file directory content must be empty");
            }
        }

        private ManagedFlowFileMigrationContract.LogicalEntry logicalEntry() {
            return new ManagedFlowFileMigrationContract.LogicalEntry(logicalPath, kind, content);
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    private record NodeDescriptor(String type, String operation, List<String> pathPins, boolean directory) {
        private NodeDescriptor {
            type = Objects.requireNonNull(type, "type");
            operation = Objects.requireNonNull(operation, "operation");
            pathPins = List.copyOf(pathPins);
        }
    }

    private static final class GraphFailure extends Exception {
        private final String code;
        private final String reason;
        private final String action;
        private final List<String> references;

        private GraphFailure(String code, String reason, String action, List<String> references) {
            super(reason);
            this.code = code;
            this.reason = reason;
            this.action = action;
            this.references = references == null ? List.of() : List.copyOf(references);
        }
    }
}
