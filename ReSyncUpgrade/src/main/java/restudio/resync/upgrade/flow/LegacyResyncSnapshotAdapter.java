package restudio.resync.upgrade.flow;

import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.MigrationOperationType;
import restudio.resync.migration.AtomicFiles;
import restudio.resync.migration.ManagedFlowFileMigrationContract;
import restudio.resync.migration.ManagedFlowFileStoreContract;
import restudio.resync.migration.ManagedResourceFileContract;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.ProductionSnapshotMetadataManifest;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.upgrade.ImmutableSnapshotAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotInput;
import restudio.resync.upgrade.command.LegacyCommandBindingSnapshotAdapter;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class LegacyResyncSnapshotAdapter implements OfflineUpgradeSnapshotAdapter {
    public static final String ADAPTER_ID = "resync.legacy-snapshot";
    public static final int ADAPTER_VERSION = 1;
    private static final String GRAPH_OWNER = LegacyFlowGraphSnapshotAdapter.GRAPH_OWNER;
    private static final String TRIGGER_OWNER = LegacyCommandBindingSnapshotAdapter.TRIGGER_OWNER;

    private final LegacyFlowGraphSnapshotAdapter flow;
    private final LegacyCommandBindingSnapshotAdapter command;
    private final TypedAutomationPhase typedAutomation;
    private final List<ManagedFlowFileMigrationProvider> managedFlowFileProviders;

    public LegacyResyncSnapshotAdapter() {
        this(new LegacyFlowGraphSnapshotAdapter(), new LegacyCommandBindingSnapshotAdapter(),
            ManagedFlowFileMigrationProviders.discover());
    }

    LegacyResyncSnapshotAdapter(LegacyFlowGraphSnapshotAdapter flow, LegacyCommandBindingSnapshotAdapter command) {
        this(flow, command, ManagedFlowFileMigrationProviders.discover());
    }

    LegacyResyncSnapshotAdapter(LegacyFlowGraphSnapshotAdapter flow, LegacyCommandBindingSnapshotAdapter command,
                                List<? extends ManagedFlowFileMigrationProvider> managedFlowFileProviders) {
        this.flow = Objects.requireNonNull(flow, "flow");
        this.command = Objects.requireNonNull(command, "command");
        this.typedAutomation = new TypedAutomationPhase();
        this.managedFlowFileProviders = List.copyOf(Objects.requireNonNull(managedFlowFileProviders,
            "managedFlowFileProviders"));
    }

    @Override
    public OfflineUpgradeAdapter.AdapterKey key() {
        return new OfflineUpgradeAdapter.AdapterKey(ADAPTER_ID, ADAPTER_VERSION);
    }

    @Override
    public String owner() {
        return GRAPH_OWNER;
    }

    @Override
    public boolean claims(OfflineUpgradeSnapshotInput input) {
        Objects.requireNonNull(input, "input");
        return flow.claims(input) || command.claims(input) || input.snapshot().entries().stream()
            .anyMatch(entry -> (GRAPH_OWNER.equals(entry.owner()) && managedResourcePath(entry.relativePath()))
                || managedFlowFileTargetPath(entry.relativePath()));
    }

    @Override
    public boolean owns(String relativePath, String sourceOwner) {
        return (TRIGGER_OWNER.equals(sourceOwner) && command.owns(relativePath, sourceOwner))
            || (GRAPH_OWNER.equals(sourceOwner) && (flow.owns(relativePath, sourceOwner) || managedResourcePath(relativePath)))
            || ((ManagedFlowFileStoreContract.OWNER.equals(sourceOwner)
                || ProductionPersistenceOwners.STANDALONE_ROOT.equals(sourceOwner))
                && managedFlowFileTargetPath(relativePath));
    }

    private static boolean managedResourcePath(String relativePath) {
        String normalized = relativePath == null ? "" : relativePath.replace('\\', '/');
        return normalized.endsWith(".json") && (normalized.startsWith("assets/Automation/Variables/")
            || normalized.startsWith("assets/Automation/Schedules/"));
    }

    private static boolean managedFlowFileTargetPath(String relativePath) {
        String normalized = relativePath == null ? "" : relativePath.replace('\\', '/');
        String root = ManagedFlowFileStoreContract.ROOT_DIRECTORY + "/";
        return normalized.equals(root + ManagedFlowFileStoreContract.DATABASE_FILE)
            || normalized.equals(root + ManagedFlowFileMigrationContract.MIGRATION_DIRECTORY + "/"
                + ManagedFlowFileMigrationContract.MANIFEST_FILE)
            || normalized.equals(root + ManagedFlowFileMigrationContract.MIGRATION_DIRECTORY + "/"
                + ManagedFlowFileMigrationContract.COMPLETION_FILE);
    }

    @Override
    public SnapshotTransform transform(OfflineUpgradeSnapshotInput input) throws IOException {
        Objects.requireNonNull(input, "input");
        boolean flowClaims = flow.claims(input) || input.snapshot().entries().stream()
            .anyMatch(entry -> GRAPH_OWNER.equals(entry.owner()) && managedResourcePath(entry.relativePath()));
        boolean commandClaims = command.claims(input);
        TypedAutomationPhase.Result typedResult = flowClaims
            ? typedAutomation.transform(input)
            : TypedAutomationPhase.Result.empty();
        Set<String> skippedLegacyAutomationGraphs = typedResult.graphs().stream()
            .filter(graph -> typedAutomation.onlyContainsSkippedLegacyAutomation(input, graph))
            .map(TypedAutomationPhase.GraphSource::path)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Overlay typedOverlay = null;
        SnapshotTransform flowResult;
        if (flowClaims) {
            typedOverlay = overlay(input, typedResult.files(), skippedLegacyAutomationGraphs);
            try {
                flowResult = flow.transform(typedOverlay.input());
            } finally {
                typedOverlay.close();
            }
        } else {
            flowResult = new SnapshotTransform(List.of(), List.of(), List.of());
        }
        SnapshotTransform commandResult;
        if (!commandClaims) {
            commandResult = new SnapshotTransform(List.of(), List.of(), List.of());
        } else if (flowResult.files().isEmpty()) {
            commandResult = command.transform(input);
        } else {
            Overlay overlay = overlay(input, flowResult.files());
            try {
                commandResult = command.transform(overlay.input());
            } finally {
                overlay.close();
            }
        }
        Map<String, OfflineUpgradeSnapshotAdapter.FileTransform> files = new LinkedHashMap<>();
        Set<String> quarantinedPaths = new LinkedHashSet<>();
        quarantinedPaths.addAll(typedResult.quarantinedGraphPaths());
        quarantinedPaths.addAll(typedResult.quarantines().stream().map(QuarantineRecord::sourceLocation).toList());
        quarantinedPaths.addAll(flowResult.quarantines().stream().map(QuarantineRecord::sourceLocation).toList());
        Set<String> commandQuarantinedPaths = commandResult.quarantines().stream()
            .map(QuarantineRecord::sourceLocation)
            .collect(Collectors.toUnmodifiableSet());
        quarantinedPaths.addAll(commandQuarantinedPaths);
        List<QuarantineRecord> quarantines = new ArrayList<>();
        quarantines.addAll(typedResult.quarantines());
        quarantines.addAll(flowResult.quarantines());
        quarantines.addAll(commandResult.quarantines());
        ManagedFlowFileMigrationProvider.Result initialManagedFlowFiles = managedFlowFileMigration(input,
            typedResult.graphs(), quarantinedPaths, flowClaims);
        quarantinedPaths.addAll(initialManagedFlowFiles.quarantines().stream()
            .map(QuarantineRecord::sourceLocation).toList());
        quarantines.addAll(initialManagedFlowFiles.quarantines());
        closeQuarantinedDependencies(input, typedResult.graphDependencies(), quarantinedPaths, quarantines);
        ManagedFlowFileMigrationProvider.Result managedFlowFiles = managedFlowFileMigration(input,
            typedResult.graphs(), quarantinedPaths, flowClaims);
        Set<String> knownManagedQuarantines = initialManagedFlowFiles.quarantines().stream()
            .map(QuarantineRecord::recordId).collect(Collectors.toSet());
        quarantinedPaths.addAll(managedFlowFiles.quarantines().stream()
            .map(QuarantineRecord::sourceLocation).toList());
        managedFlowFiles.quarantines().stream()
            .filter(value -> !knownManagedQuarantines.contains(value.recordId()))
            .forEach(quarantines::add);
        ReanchoredManagedFiles reanchoredManagedFiles = reanchorManagedFiles(managedFlowFiles.files(),
            managedFlowFiles.generatedContributors(), quarantinedPaths);
        ReanchoredTypedFiles reanchoredTypedFiles = reanchorTypedFiles(typedResult.files(),
            typedResult.generatedResourceContributors(), typedResult.graphDependencies(), quarantinedPaths);
        List<OfflineUpgradeSnapshotAdapter.FileTransform> typedFiles = reanchoredTypedFiles.files();
        Set<String> quarantinedGeneratedTargets = reanchoredTypedFiles.quarantinedGeneratedTargets();
        Map<String, OfflineUpgradeSnapshotAdapter.FileTransform> reclassifications = typedFiles.stream()
            .filter(file -> file.operationType() == MigrationOperationType.CONVERT
                && !file.sourcePath().equals(file.targetPath()))
            .collect(Collectors.toMap(OfflineUpgradeSnapshotAdapter.FileTransform::targetPath, value -> value,
                (left, right) -> left, LinkedHashMap::new));
        Set<String> mergedReclassifications = new HashSet<>();
        for (OfflineUpgradeSnapshotAdapter.FileTransform file : flowResult.files()) {
            if (quarantinedPaths.contains(file.sourcePath())) {
                continue;
            }
            OfflineUpgradeSnapshotAdapter.FileTransform effective = filterProjectTransform(file, quarantinedGeneratedTargets);
            OfflineUpgradeSnapshotAdapter.FileTransform reclassification = reclassifications.get(effective.sourcePath());
            if (reclassification != null && effective.sourcePath().equals(effective.targetPath())) {
                effective = new OfflineUpgradeSnapshotAdapter.FileTransform(reclassification.sourcePath(),
                    reclassification.targetPath(), effective.bytes(), reclassification.operationType());
                mergedReclassifications.add(reclassification.targetPath());
            }
            if (!isNoOp(input, effective)) {
                files.put(fileKey(effective), effective);
            }
        }
        for (OfflineUpgradeSnapshotAdapter.FileTransform file : typedFiles) {
            if (quarantinedPaths.contains(file.sourcePath())) {
                continue;
            }
            if (mergedReclassifications.contains(file.targetPath())) {
                continue;
            }
            OfflineUpgradeSnapshotAdapter.FileTransform effective = filterProjectTransform(file, quarantinedGeneratedTargets);
            if (!isNoOp(input, effective)) {
                files.putIfAbsent(fileKey(effective), effective);
            }
        }
        for (OfflineUpgradeSnapshotAdapter.FileTransform file : commandResult.files()) {
            if (!quarantinedPaths.contains(file.sourcePath())) {
                OfflineUpgradeSnapshotAdapter.FileTransform effective = filterProjectTransform(file, quarantinedGeneratedTargets);
                if (!isNoOp(input, effective)) {
                    files.put(fileKey(effective), effective);
                }
            }
        }
        for (OfflineUpgradeSnapshotAdapter.FileTransform file : reanchoredManagedFiles.files()) {
            if (!quarantinedPaths.contains(file.sourcePath()) && !isNoOp(input, file)) {
                files.put(fileKey(file), file);
            }
        }
        Set<String> claimed = new LinkedHashSet<>();
        Set<String> sourcePaths = input.snapshot().entries().stream()
            .map(ImmutableSnapshotAdapter.Entry::relativePath).collect(Collectors.toSet());
        claimed.addAll(flowResult.claimedPaths().stream().filter(sourcePaths::contains).toList());
        claimed.addAll(typedResult.claimedPaths().stream().filter(sourcePaths::contains).toList());
        claimed.addAll(commandResult.claimedPaths().stream().filter(sourcePaths::contains).toList());
        claimed.addAll(sourcePaths.stream().filter(LegacyResyncSnapshotAdapter::managedResourcePath).toList());
        claimed.addAll(managedFlowFiles.claimedPaths().stream().filter(sourcePaths::contains).toList());
        return new SnapshotTransform(files.values(), claimed, quarantines);
    }

    private ManagedFlowFileMigrationProvider.Result managedFlowFileMigration(OfflineUpgradeSnapshotInput input,
                                                                               List<TypedAutomationPhase.GraphSource> graphs,
                                                                               Set<String> quarantinedPaths,
        boolean eligibleSource) throws IOException {
        if (managedFlowFileProviders.isEmpty()) {
            if (requiresManagedFlowFileProvider(input, graphs, eligibleSource)) {
                throw new IOException("Managed flow-file migration provider is unavailable for an eligible Flow source");
            }
            return ManagedFlowFileMigrationProvider.Result.empty();
        }
        if (managedFlowFileProviders.size() != 1) {
            throw new IOException("Exactly one managed flow-file migration provider is required");
        }
        List<ManagedFlowFileMigrationProvider.Graph> managedGraphs = new ArrayList<>();
        for (TypedAutomationPhase.GraphSource graph : graphs) {
            managedGraphs.add(new ManagedFlowFileMigrationProvider.Graph(graph.path(), graph.type(), graph.id(),
                Files.readAllBytes(MigrationPaths.resolveInside(input.root(), graph.path()))));
        }
        return managedFlowFileProviders.getFirst().produce(input, managedGraphs, quarantinedPaths, eligibleSource);
    }

    private boolean requiresManagedFlowFileProvider(OfflineUpgradeSnapshotInput input,
                                                    List<TypedAutomationPhase.GraphSource> graphs,
                                                    boolean eligibleSource) {
        if (!eligibleSource) {
            return false;
        }
        if (graphs.isEmpty()) {
            return input.snapshot().directories().stream().anyMatch(LegacyFlowGraphSnapshotAdapter::isSupportedGraphDirectory);
        }
        return graphs.stream().filter(graph -> LegacyFlowGraphSnapshotAdapter.isSupportedGraphType(graph.type())).anyMatch(graph -> {
            try {
                Object decoded = CanonicalCodec.decodePermissive(Files.readAllBytes(
                    MigrationPaths.resolveInside(input.root(), graph.path()))).toJava();
                Map<String, Object> root = object(decoded, "graph");
                Map<String, Object> nodes = objectOrEmpty(root.get("nodes"));
                return nodes.values().stream().anyMatch(value -> value instanceof Map<?, ?> raw
                    && LegacyFlowGraphSnapshotAdapter.isCanonicalManagedFlowFileNode(object(raw, "node")));
            } catch (IOException | RuntimeException ignored) {
                return false;
            }
        });
    }

    private ReanchoredManagedFiles reanchorManagedFiles(
        Collection<? extends OfflineUpgradeSnapshotAdapter.FileTransform> managedFiles,
        Map<String, ? extends Collection<String>> generatedContributors,
        Set<String> quarantinedPaths) {
        List<OfflineUpgradeSnapshotAdapter.FileTransform> files = new ArrayList<>();
        for (OfflineUpgradeSnapshotAdapter.FileTransform file : managedFiles) {
            if (file.operationType() != MigrationOperationType.GENERATE) {
                files.add(file);
                continue;
            }
            Collection<String> contributors = generatedContributors.get(file.targetPath());
            if (contributors == null || !quarantinedPaths.contains(file.sourcePath())) {
                files.add(file);
                continue;
            }
            String survivingContributor = contributors.stream().filter(path -> !quarantinedPaths.contains(path))
                .sorted().findFirst().orElse(null);
            if (survivingContributor != null) {
                files.add(new OfflineUpgradeSnapshotAdapter.FileTransform(survivingContributor, file.targetPath(),
                    file.bytes(), MigrationOperationType.GENERATE));
            }
        }
        return new ReanchoredManagedFiles(files);
    }

    private String fileKey(OfflineUpgradeSnapshotAdapter.FileTransform file) {
        return file.sourcePath() + "\u0000" + file.targetPath();
    }

    private OfflineUpgradeSnapshotAdapter.FileTransform filterProjectTransform(OfflineUpgradeSnapshotAdapter.FileTransform file,
                                                                                Set<String> quarantinedGeneratedTargets) {
        if (!isProjectMetadata(file.targetPath()) || quarantinedGeneratedTargets.isEmpty()) {
            return file;
        }
        try {
            Map<String, Object> project = object(CanonicalCodec.decodePermissive(file.bytes()).toJava(), "project metadata");
            if (!(project.get("resources") instanceof List<?> values)) {
                return file;
            }
            List<Object> filtered = new ArrayList<>();
            boolean changed = false;
            for (Object raw : values) {
                if (!(raw instanceof Map<?, ?>)) {
                    filtered.add(raw);
                    continue;
                }
                Map<String, Object> resource = object(raw, "project resource");
                String type = valueText(resource.get("type"));
                String id = valueText(resource.get("id"));
                String folder = valueText(resource.get("path")).replace('\\', '/');
                String normalized = folder.startsWith("assets/") ? folder : "assets/" + folder;
                String target = normalized.endsWith(".json") ? normalized : normalized + "/" + id + ".json";
                if (("variable_definition".equals(type) || "schedule_definition".equals(type))
                    && quarantinedGeneratedTargets.contains(target)) {
                    changed = true;
                    continue;
                }
                filtered.add(resource);
            }
            if (!changed) {
                return file;
            }
            project.put("resources", filtered);
            return new OfflineUpgradeSnapshotAdapter.FileTransform(file.sourcePath(), file.targetPath(),
                CanonicalCodec.encode(JsonValue.fromJava(project)), file.operationType());
        } catch (RuntimeException ignored) {
            return file;
        }
    }

    private boolean isNoOp(OfflineUpgradeSnapshotInput input, OfflineUpgradeSnapshotAdapter.FileTransform file) {
        if (file.sourcePath().isBlank() || !file.sourcePath().equals(file.targetPath())) {
            return false;
        }
        try {
            return input.snapshot().entries().stream().anyMatch(entry -> entry.relativePath().equals(file.sourcePath()))
                && Arrays.equals(Files.readAllBytes(MigrationPaths.resolveInside(input.root(), file.sourcePath())), file.bytes());
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean isProjectMetadata(String path) {
        return "assets/project.json".equals(path) || "project.json".equals(path);
    }

    private static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected " + field + " object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Object keys must be strings");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static Map<String, Object> objectOrEmpty(Object value) {
        return value instanceof Map<?, ?> ? object(value, "object") : Map.of();
    }

    private static String valueText(Object value) {
        return value instanceof String text ? text : "";
    }

    private void closeQuarantinedDependencies(OfflineUpgradeSnapshotInput input, Map<String, Set<String>> dependencies,
                                              Set<String> quarantinedPaths, List<QuarantineRecord> quarantines) {
        Set<String> records = quarantines.stream()
            .map(value -> value.sourceLocation() + "\u0000" + value.code()).collect(Collectors.toCollection(LinkedHashSet::new));
        boolean changed;
        do {
            changed = false;
            for (Map.Entry<String, Set<String>> dependency : dependencies.entrySet()) {
                if (quarantinedPaths.contains(dependency.getKey())) {
                    continue;
                }
                List<String> invalid = dependency.getValue().stream().filter(quarantinedPaths::contains).sorted().toList();
                if (invalid.isEmpty() || !quarantinedPaths.add(dependency.getKey())) {
                    continue;
                }
                changed = true;
                ImmutableSnapshotAdapter.Entry entry = input.snapshot().entries().stream()
                    .filter(value -> value.relativePath().equals(dependency.getKey())).findFirst().orElse(null);
                if (entry == null) {
                    continue;
                }
                String code = "MIGRATION.TYPED_AUTOMATION_DEPENDENCY_UNRESOLVED";
                if (records.add(entry.relativePath() + "\u0000" + code)) {
                    List<String> references = new ArrayList<>();
                    references.add(entry.relativePath());
                    references.addAll(invalid);
                    references.sort(String::compareTo);
                    quarantines.add(TypedAutomationPhase.quarantine(entry, code,
                        "The graph schedules a graph that was quarantined or removed by another migration phase.",
                        "Repair the scheduled graph dependency before retrying the migration.", references));
                }
            }
        } while (changed);
    }

    private ReanchoredTypedFiles reanchorTypedFiles(List<OfflineUpgradeSnapshotAdapter.FileTransform> typedFiles,
                                                     Map<String, Set<String>> generatedResourceContributors,
                                                     Map<String, Set<String>> graphDependencies,
                                                     Set<String> quarantinedPaths) {
        List<OfflineUpgradeSnapshotAdapter.FileTransform> files = new ArrayList<>();
        Set<String> quarantinedGeneratedTargets = new LinkedHashSet<>();
        List<String> survivingGraphs = graphDependencies.keySet().stream()
            .filter(path -> !quarantinedPaths.contains(path)).sorted().toList();
        for (OfflineUpgradeSnapshotAdapter.FileTransform file : typedFiles) {
            if (file.operationType() != MigrationOperationType.GENERATE || !quarantinedPaths.contains(file.sourcePath())) {
                files.add(file);
                continue;
            }
            Set<String> contributors = generatedResourceContributors.get(file.targetPath());
            if (contributors != null) {
                String survivingContributor = contributors.stream().filter(path -> !quarantinedPaths.contains(path))
                    .sorted().findFirst().orElse(null);
                if (survivingContributor != null) {
                    files.add(new OfflineUpgradeSnapshotAdapter.FileTransform(survivingContributor, file.targetPath(),
                        file.bytes(), MigrationOperationType.GENERATE));
                } else {
                    quarantinedGeneratedTargets.add(file.targetPath());
                }
                continue;
            }
            if (isProjectMetadata(file.targetPath())) {
                String survivingGraph = survivingGraphs.isEmpty() ? null : survivingGraphs.getFirst();
                if (survivingGraph != null) {
                    files.add(new OfflineUpgradeSnapshotAdapter.FileTransform(survivingGraph, file.targetPath(),
                        file.bytes(), MigrationOperationType.GENERATE));
                }
                continue;
            }
            if (managedResourcePath(file.targetPath())) {
                quarantinedGeneratedTargets.add(file.targetPath());
            }
        }
        return new ReanchoredTypedFiles(files, quarantinedGeneratedTargets);
    }

    private Overlay overlay(OfflineUpgradeSnapshotInput input,
                            Collection<? extends OfflineUpgradeSnapshotAdapter.FileTransform> transforms) throws IOException {
        return overlay(input, transforms, Set.of());
    }

    private Overlay overlay(OfflineUpgradeSnapshotInput input,
                            Collection<? extends OfflineUpgradeSnapshotAdapter.FileTransform> transforms,
                            Set<String> excludedPaths) throws IOException {
        Path parent = Files.createTempDirectory("resync-legacy-snapshot-overlay-");
        Path root = parent.resolve("snapshot");
        try {
            copyTree(input.root(), root);
            Map<String, byte[]> replacements = new HashMap<>();
            Set<String> removedSources = new HashSet<>();
            for (OfflineUpgradeSnapshotAdapter.FileTransform transform : transforms) {
                if (transform.sourcePath().equals(transform.targetPath())) {
                    replacements.put(transform.sourcePath(), transform.bytes());
                }
                if (transform.operationType() != null && !transform.operationType().preservesSource()
                    && !transform.sourcePath().isBlank() && !transform.sourcePath().equals(transform.targetPath())) {
                    Files.deleteIfExists(MigrationPaths.resolveInside(root, transform.sourcePath()));
                    removedSources.add(transform.sourcePath());
                }
                Path target = MigrationPaths.resolveInside(root, transform.targetPath());
                Files.createDirectories(target.getParent());
                AtomicFiles.write(target, transform.bytes());
            }
            copySidecar(ProductionSnapshotMetadataManifest.pathFor(input.root()), ProductionSnapshotMetadataManifest.pathFor(root));
            Path sourceManifest = input.root().resolveSibling(input.root().getFileName() + ".manifest");
            copySidecar(sourceManifest, root.resolveSibling(root.getFileName() + ".manifest"));
            Map<String, ImmutableSnapshotAdapter.Entry> entriesByPath = new LinkedHashMap<>();
            input.snapshot().entries().forEach(entry -> entriesByPath.put(entry.relativePath(), entry));
            excludedPaths.forEach(entriesByPath::remove);
            removedSources.forEach(entriesByPath::remove);
            for (OfflineUpgradeSnapshotAdapter.FileTransform transform : transforms) {
                if (!entriesByPath.containsKey(transform.targetPath())) {
                    entriesByPath.put(transform.targetPath(), new ImmutableSnapshotAdapter.Entry(
                        transform.targetPath(), transform.bytes().length, sha256(transform.bytes()), GRAPH_OWNER));
                }
            }
            List<ImmutableSnapshotAdapter.Entry> entries = entriesByPath.values().stream().map(entry -> {
                byte[] bytes = replacements.get(entry.relativePath());
                return bytes == null ? entry : new ImmutableSnapshotAdapter.Entry(entry.relativePath(), bytes.length, sha256(bytes), entry.owner());
            }).toList();
            ImmutableSnapshotAdapter.View view = new ImmutableSnapshotAdapter.View(input.snapshot().metadata(), input.snapshot().manifestHash(), input.snapshot().directories(), entries);
            return new Overlay(new OfflineUpgradeSnapshotInput(root, view, input.provenanceRoot()), parent);
        } catch (IOException | RuntimeException exception) {
            deleteTree(parent);
            throw exception;
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    AtomicFiles.copy(path, destination);
                }
            }
        }
    }

    private static void copySidecar(Path source, Path target) throws IOException {
        if (Files.isRegularFile(source)) {
            AtomicFiles.copy(source, target);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class TypedAutomationPhase {
        private static final String VARIABLE = "variable_definition";
        private static final String SCHEDULE = "schedule_definition";
        private static final Set<String> GRAPH_TYPES = Set.of("flow", "function", "command");
        private static final Set<String> VARIABLE_TYPES = Set.of(
            "variable.access", "variable_access", "variable_set_global", "variable_set_local", "variable_set_player",
            "variable_get_global", "variable_get_local", "variable_get_player", "variable_delete", "variable_exists",
            "variable_list_all", "variable_increment", "variable_decrement", "variable_multiply", "variable_divide",
            "variable.variable_access", "variable.variable_set_global", "variable.variable_set_local", "variable.variable_set_player",
            "variable.variable_get_global", "variable.variable_get_local", "variable.variable_get_player",
            "variable.variable_delete_global", "variable.variable_delete_local", "variable.variable_exists_global",
            "variable.variable_exists_local", "variable.variable_list_global", "variable.variable_list_local",
            "variable.variable_clear_global", "variable.variable_clear_local");
        private static final Set<String> SCHEDULE_TYPES = Set.of(
            "schedule.schedule", "schedule.schedule_repeating", "schedule.cron", "schedule.at.time", "schedule.interval",
            "schedule", "schedule_repeating", "schedule_at_time", "schedule_interval", "schedule_cron");
        private static final Set<String> CANCEL_TYPES = Set.of("schedule.cancel_task", "cancel.schedule", "cancel_task");
        private static final Set<String> AUTHORED_AUTOMATION_TYPES = Set.of(
            "automation.variable", "automation.timer", "automation.schedule", "automation.scheduled_task",
            "event.variable.changed", "event.timer", "event.scheduled_task", "event.schedule");

        private Result transform(OfflineUpgradeSnapshotInput input) throws IOException {
            MetadataIssue metadataIssue = invalidProjectMetadata(input);
            if (metadataIssue != null) {
                QuarantineRecord quarantine = quarantine(metadataIssue.entry(), "MIGRATION.TYPED_AUTOMATION_PROJECT_METADATA_INVALID",
                    "Project metadata is malformed or contains conflicting resource identities: " + metadataIssue.reason(),
                    "Repair project metadata before migrating typed automation resources.", List.of(metadataIssue.entry().relativePath()));
                return new Result(List.of(), Set.of(metadataIssue.entry().relativePath()), List.of(quarantine), Set.of(),
                    Map.of(), Map.of(), List.of());
            }
            List<GraphSource> graphs = discoverGraphs(input);
            if (graphs.isEmpty()) {
                return Result.empty();
            }
            List<QuarantineRecord> quarantines = new ArrayList<>();
            Set<String> quarantined = new LinkedHashSet<>();
            Set<String> recordedQuarantines = new HashSet<>();
            Map<String, BackupCandidate> functionRecoveries = resolveFunctionBackups(input, graphs, quarantined,
                quarantines, recordedQuarantines);
            List<GraphSource> effectiveGraphs = graphs.stream().map(graph -> functionRecoveries.containsKey(graph.path())
                ? new GraphSource(graph.entry(), graph.path(), "function", graph.id()) : graph).toList();
            Map<String, GraphSource> identities = new LinkedHashMap<>();
            List<GraphSource> validGraphs = new ArrayList<>();
            for (GraphSource graph : effectiveGraphs) {
                String identity = graph.type() + "\u0000" + graph.id();
                GraphSource previous = identities.putIfAbsent(identity, graph);
                if (previous != null) {
                    List<String> refs = List.of(previous.path(), graph.path()).stream().sorted().toList();
                    if (quarantined.add(previous.path())) {
                        quarantines.add(quarantine(previous.entry(), "MIGRATION.TYPED_AUTOMATION_GRAPH_IDENTITY_COLLISION",
                            "More than one graph document declares the same typed identity.",
                            "Retain one canonical graph document before retrying the automation migration.", refs));
                    }
                    if (quarantined.add(graph.path())) {
                        quarantines.add(quarantine(graph.entry(), "MIGRATION.TYPED_AUTOMATION_GRAPH_IDENTITY_COLLISION",
                            "More than one graph document declares the same typed identity.",
                            "Retain one canonical graph document before retrying the automation migration.", refs));
                    }
                    continue;
                }
                validGraphs.add(graph);
            }
            Map<VariableKey, Set<String>> inferredTypes = indexVariableTypes(input, validGraphs);
            Map<String, ExistingResource> existing = existingResources(input);
            Map<String, GraphSource> graphSources = validGraphs.stream().collect(Collectors.toMap(
                GraphSource::path, value -> value, (left, right) -> left, LinkedHashMap::new));
            Map<String, Set<String>> graphDependencies = new LinkedHashMap<>();
            for (GraphSource graph : validGraphs) {
                try {
                    graphDependencies.put(graph.path(), graphDependencies(input, graph, identities));
                } catch (RuntimeException | IOException ignored) {
                    graphDependencies.put(graph.path(), Set.of());
                }
            }
            Map<ResourceIdentity, List<ResourceOutput>> resourceCandidates = new LinkedHashMap<>();
            Map<ResourceIdentity, List<ResourceOutput>> resourceUsers = new LinkedHashMap<>();
            Map<String, GraphDocument> transformedGraphs = new LinkedHashMap<>();
            for (GraphSource graph : validGraphs) {
                if (quarantined.contains(graph.path())) {
                    continue;
                }
                try {
                    GraphDocument document = readGraph(input, graph);
                    Map<ResourceIdentity, ResourceOutput> graphGenerated = new LinkedHashMap<>();
                    boolean invalid = false;
                    boolean changed = functionRecoveries.containsKey(graph.path());
                    if (changed) {
                        document.root().put("function", true);
                        document.root().put("resourceType", "function");
                    }
                    Map<String, String> originalTypes = new LinkedHashMap<>();
                    Map<String, Boolean> authoredPinEligibility = new LinkedHashMap<>();
                    Map<String, Object> nodes = objectOrEmpty(document.root().get("nodes"));
                    document.root().put("nodes", nodes);
                    for (String nodeId : nodes.keySet().stream().sorted().toList()) {
                        Object raw = nodes.get(nodeId);
                        if (!(raw instanceof Map<?, ?>)) {
                            invalid = true;
                            break;
                        }
                        originalTypes.put(nodeId, text(object(raw, "node").get("type")));
                    }
                    if (invalid) {
                        quarantineGraph(graph, quarantined, quarantines, recordedQuarantines,
                            "MIGRATION.TYPED_AUTOMATION_GRAPH_INVALID",
                            "The graph contains a node that is not an object.",
                            "Repair the graph document before retrying the automation migration.", List.of(graph.path()));
                        continue;
                    }
                    for (String nodeId : nodes.keySet().stream().sorted().toList()) {
                        Object raw = nodes.get(nodeId);
                        if (!(raw instanceof Map<?, ?>)) {
                            invalid = true;
                            break;
                        }
                        Map<String, Object> node = deepObject(raw);
                        nodes.put(nodeId, node);
                        String originalType = originalTypes.getOrDefault(nodeId, text(node.get("type")));
                        NodeResult result;
                        if (isVariableType(originalType)) {
                            result = migrateVariable(document.root(), nodeId, node, graph, inferredTypes, existing, graphGenerated);
                        } else if (isScheduleType(originalType)) {
                            result = migrateSchedule(document.root(), nodeId, node, graph, identities, existing, graphGenerated);
                        } else if (isCancelType(originalType)) {
                            result = migrateCancel(document.root(), nodeId, node, originalTypes, identities);
                        } else {
                            result = NodeResult.UNCHANGED;
                        }
                        if (result == NodeResult.UNRESOLVED) {
                            invalid = true;
                            break;
                        }
                        AuthoredSchemaMigration authoredMigration = migrateAuthoredSchemaVersion(node, originalType);
                        authoredPinEligibility.put(nodeId, authoredMigration.pinEligible());
                        if (authoredMigration.changed()) {
                            changed = true;
                        }
                        if (result == NodeResult.MIGRATED) {
                            changed = true;
                        }
                    }
                    if (invalid) {
                        quarantineGraph(graph, quarantined, quarantines, recordedQuarantines,
                            "MIGRATION.TYPED_AUTOMATION_DEPENDENCY_UNRESOLVED",
                            "The graph contains a typed automation dependency that could not be resolved as one transaction.",
                            "Repair the referenced graph or existing automation resource before retrying the migration.",
                            graphGenerated.keySet().stream().map(ResourceIdentity::canonical).sorted().toList());
                        continue;
                    }
                    changed |= rewriteConnections(document.root(), originalTypes, nodes, authoredPinEligibility, changed);
                    if (changed) {
                        document.root().put("nodes", nodes);
                        transformedGraphs.put(graph.path(), document);
                    }
                    for (Map.Entry<ResourceIdentity, ResourceOutput> resource : graphGenerated.entrySet()) {
                        resourceCandidates.computeIfAbsent(resource.getKey(), ignored -> new ArrayList<>()).add(resource.getValue());
                    }
                } catch (RuntimeException | IOException failure) {
                    transformedGraphs.remove(graph.path());
                    quarantineGraph(graph, quarantined, quarantines, recordedQuarantines,
                        "MIGRATION.TYPED_AUTOMATION_GRAPH_INVALID",
                        "The graph contains malformed typed automation content: " + reason(failure),
                        "Repair the graph node, input values, or connections before retrying the automation migration.",
                        List.of(graph.path(), reason(failure)));
                }
            }
            Map<ResourceIdentity, ResourceOutput> generated = resolveResources(resourceCandidates, resourceUsers, graphSources,
                quarantined, quarantines, recordedQuarantines);
            resolveGeneratedTargets(input, generated, resourceUsers, graphSources, quarantined, quarantines, recordedQuarantines);
            resolveDependencyClosure(graphDependencies, graphSources, quarantined, quarantines, recordedQuarantines);
            reanchorResources(generated, resourceUsers, quarantined);
            transformedGraphs.entrySet().removeIf(entry -> quarantined.contains(entry.getKey()));
            generated.entrySet().removeIf(entry -> quarantined.contains(entry.getValue().anchorPath()));
            Map<String, Object> project = readProject(input);
            String projectPath = projectPath(input);
            boolean projectExists = hasEntry(input, projectPath);
            boolean projectChanged = project != null && reclassifyProjectGraphs(project, validGraphs, functionRecoveries, quarantined);
            if (!generated.isEmpty()) {
                if (project == null) {
                    project = new LinkedHashMap<>();
                    project.put("serverId", "project");
                    project.put("folders", new ArrayList<>());
                    project.put("resources", new ArrayList<>());
                    projectPath = "assets/project.json";
                    projectExists = hasEntry(input, projectPath);
                    GraphSource anchor = validGraphs.stream().filter(graph -> !quarantined.contains(graph.path())).findFirst().orElse(null);
                    if (anchor != null) {
                        projectChanged = true;
                        project.put("resources", graphMetadata(validGraphs, quarantined));
                    }
                }
                if (project != null) {
                    List<Object> resources = listOrEmpty(project.get("resources"));
                    for (ResourceOutput output : generated.values().stream().sorted(Comparator.comparing(value -> value.identity().canonical())).toList()) {
                        projectChanged |= ensureProjectResource(resources, output);
                    }
                    project.put("resources", resources);
                }
            }
            List<OfflineUpgradeSnapshotAdapter.FileTransform> files = new ArrayList<>();
            for (Map.Entry<String, GraphDocument> entry : transformedGraphs.entrySet()) {
                if (quarantined.contains(entry.getKey())) {
                    continue;
                }
                GraphSource graph = graphSources.get(entry.getKey());
                String targetPath = functionRecoveries.containsKey(entry.getKey())
                    ? functionGraphPath(graph.id()) : entry.getKey();
                MigrationOperationType operationType = targetPath.equals(entry.getKey()) ? null : MigrationOperationType.CONVERT;
                files.add(new OfflineUpgradeSnapshotAdapter.FileTransform(entry.getKey(), targetPath,
                    CanonicalCodec.encode(JsonValue.fromJava(entry.getValue().root())), operationType));
            }
            for (ResourceOutput output : generated.values().stream().sorted(Comparator.comparing(value -> value.identity().canonical())).toList()) {
                if (quarantined.contains(output.anchorPath()) || output.existingPath() != null) {
                    continue;
                }
                files.add(new OfflineUpgradeSnapshotAdapter.FileTransform(output.anchorPath(), output.targetPath(),
                    ManagedResourceFileContract.encode(output.identity().type(), output.identity().id(), output.payload()),
                    MigrationOperationType.GENERATE));
            }
            if (projectChanged && project != null && !projectPath.isBlank()) {
                String anchor = validGraphs.stream().filter(graph -> !quarantined.contains(graph.path())).map(GraphSource::path).findFirst().orElse(null);
                if (anchor != null) {
                    boolean generateProject = !projectExists && projectPath.equals("assets/project.json");
                    files.add(new OfflineUpgradeSnapshotAdapter.FileTransform(
                        generateProject ? anchor : projectPath,
                        projectPath,
                        CanonicalCodec.encode(JsonValue.fromJava(project)),
                        generateProject ? MigrationOperationType.GENERATE : null));
                }
            }
            Set<String> claimed = new LinkedHashSet<>(effectiveGraphs.stream().map(GraphSource::path).toList());
            if (projectExists) {
                claimed.add(projectPath);
            }
            claimed.addAll(input.snapshot().entries().stream().map(ImmutableSnapshotAdapter.Entry::relativePath)
                .filter(LegacyResyncSnapshotAdapter::managedResourcePath).toList());
            Map<String, Set<String>> generatedResourceContributors = new LinkedHashMap<>();
            for (Map.Entry<ResourceIdentity, ResourceOutput> entry : generated.entrySet()) {
                generatedResourceContributors.put(entry.getValue().targetPath(), resourceUsers.getOrDefault(entry.getKey(), List.of()).stream()
                    .map(ResourceOutput::anchorPath).collect(Collectors.toUnmodifiableSet()));
            }
            return new Result(files, claimed, quarantines, quarantined, graphDependencies, generatedResourceContributors,
                validGraphs);
        }

        private Map<String, BackupCandidate> resolveFunctionBackups(OfflineUpgradeSnapshotInput input,
                                                                     List<GraphSource> graphs,
                                                                     Set<String> quarantined,
                                                                     List<QuarantineRecord> quarantines,
                                                                     Set<String> recordedQuarantines) {
            Map<String, List<BackupCandidate>> candidatesByGraph = new LinkedHashMap<>();
            for (ImmutableSnapshotAdapter.Entry entry : input.snapshot().entries()) {
                BackupPath backupPath = backupPath(entry.relativePath());
                if (backupPath == null || backupPath.graphId().isBlank()) {
                    continue;
                }
                BackupCandidate candidate = readBackupCandidate(input, entry, backupPath);
                for (GraphSource graph : graphs) {
                    if ("flow".equals(graph.type()) && graph.id().equals(backupPath.graphId())) {
                        candidatesByGraph.computeIfAbsent(graph.path(), ignored -> new ArrayList<>()).add(candidate);
                    }
                }
            }
            Map<String, BackupCandidate> recoveries = new LinkedHashMap<>();
            for (GraphSource graph : graphs) {
                if (!"flow".equals(graph.type())) {
                    continue;
                }
                List<BackupCandidate> candidates = candidatesByGraph.getOrDefault(graph.path(), List.of());
                if (candidates.isEmpty()) {
                    continue;
                }
                BackupDecision decision = selectBackup(candidates);
                if (decision.status() == BackupStatus.RECOVER) {
                    recoveries.put(graph.path(), decision.candidate());
                    continue;
                }
                String code = decision.status() == BackupStatus.AMBIGUOUS
                    ? "MIGRATION.TYPED_AUTOMATION_BACKUP_AMBIGUOUS"
                    : decision.status() == BackupStatus.MISMATCH
                        ? "MIGRATION.TYPED_AUTOMATION_BACKUP_MISMATCH"
                        : "MIGRATION.TYPED_AUTOMATION_BACKUP_INVALID";
                quarantineGraph(graph, quarantined, quarantines, recordedQuarantines, code,
                    "The typed automation recovery backup is " + decision.status().name().toLowerCase(Locale.ROOT)
                        + " and cannot prove a function reclassification: " + decision.reason(),
                    "Repair or remove the affected recovery backup before retrying the migration.", decision.references());
            }
            return recoveries;
        }

        private BackupCandidate readBackupCandidate(OfflineUpgradeSnapshotInput input,
                                                     ImmutableSnapshotAdapter.Entry entry,
                                                     BackupPath backupPath) {
            Map<String, Object> envelope = null;
            String reason = "";
            boolean valid = backupPath.validShape();
            try {
                envelope = object(CanonicalCodec.decodePermissive(
                    Files.readAllBytes(MigrationPaths.resolveInside(input.root(), entry.relativePath()))).toJava(), "backup envelope");
            } catch (RuntimeException | IOException failure) {
                reason = "BACKUP_ENVELOPE_INVALID_JSON";
                valid = false;
            }
            if (valid && !GRAPH_OWNER.equals(entry.owner())) {
                reason = "BACKUP_OWNER_MISMATCH";
                valid = false;
            }
            String id = envelope == null ? "" : text(envelope.get("id"));
            String type = envelope == null ? "" : text(envelope.get("resourceType"));
            if (valid && !backupPath.graphId().equals(id)) {
                reason = "BACKUP_ENVELOPE_ID_MISMATCH";
                valid = false;
            }
            if (valid && !"function".equals(type)) {
                reason = "BACKUP_ENVELOPE_RESOURCE_TYPE_MISMATCH";
                valid = false;
            }
            if (valid && !LegacyFlowGraphSnapshotAdapter.hasValidAssetIdentity(envelope, "function")) {
                reason = "BACKUP_ENVELOPE_ASSET_IDENTITY_INVALID";
                valid = false;
            }
            if (!backupPath.validShape() && reason.isBlank()) {
                reason = "BACKUP_PATH_INVALID";
            }
            long revision = envelope == null ? -1L : longValue(envelope.get("assetRevision"), -1L);
            String fingerprint = envelope == null ? entry.sha256()
                : JsonValue.fromJava(envelope).canonicalText();
            return new BackupCandidate(backupPath.migrationId(), backupPath.graphId(), entry.relativePath(), envelope,
                valid, reason, revision, fingerprint);
        }

        private BackupDecision selectBackup(List<BackupCandidate> candidates) {
            Map<String, List<BackupCandidate>> byMigration = candidates.stream().collect(Collectors.groupingBy(
                BackupCandidate::migrationId, LinkedHashMap::new, Collectors.toCollection(ArrayList::new)));
            List<BackupGroup> groups = byMigration.entrySet().stream()
                .map(entry -> new BackupGroup(entry.getKey(), entry.getValue(), entry.getValue().stream()
                    .mapToLong(BackupCandidate::revision).max().orElse(-1L)))
                .sorted(Comparator.comparingLong(BackupGroup::revision).reversed()
                    .thenComparing(BackupGroup::migrationId, Comparator.reverseOrder()))
                .toList();
            BackupGroup newest = groups.getFirst();
            List<String> references = newest.candidates().stream().map(BackupCandidate::path).sorted().toList();
            List<BackupCandidate> invalid = newest.candidates().stream().filter(candidate -> !candidate.valid()).toList();
            if (!invalid.isEmpty()) {
                BackupCandidate first = invalid.stream().sorted(Comparator.comparing(BackupCandidate::path)).findFirst().orElseThrow();
                BackupStatus status = first.reason().contains("MISMATCH") ? BackupStatus.MISMATCH : BackupStatus.INVALID;
                return new BackupDecision(status, null, first.reason(), references);
            }
            Map<String, List<BackupCandidate>> byFingerprint = newest.candidates().stream().collect(Collectors.groupingBy(
                BackupCandidate::fingerprint, LinkedHashMap::new, Collectors.toCollection(ArrayList::new)));
            if (byFingerprint.size() > 1) {
                return new BackupDecision(BackupStatus.AMBIGUOUS, null,
                    "BACKUP_ENVELOPES_CONFLICT", references);
            }
            BackupCandidate selected = newest.candidates().stream().sorted(Comparator.comparing(BackupCandidate::path))
                .findFirst().orElseThrow();
            return new BackupDecision(BackupStatus.RECOVER, selected, "", references);
        }

        private BackupPath backupPath(String relativePath) {
            if (!LegacyFlowGraphSnapshotAdapter.isTypedAutomationBackupPath(relativePath)) {
                return null;
            }
            String normalized = relativePath.replace('\\', '/');
            String remainder = normalized.substring("assets/migration-backups/".length());
            int separator = remainder.indexOf('/');
            if (separator <= 0) {
                return null;
            }
            String migrationId = remainder.substring(0, separator);
            String backupFile = remainder.substring(separator + 1);
            String[] segments = backupFile.split("/", -1);
            String leaf = segments.length == 0 ? "" : segments[segments.length - 1];
            String graphId = leaf.endsWith(".json") ? leaf.substring(0, leaf.length() - 5) : "";
            boolean validShape = !graphId.isBlank() && (segments.length == 1
                || segments.length == 2 && GRAPH_TYPES.contains(segments[0]));
            return new BackupPath(migrationId, graphId, validShape);
        }

        private boolean reclassifyProjectGraphs(Map<String, Object> project, List<GraphSource> graphs,
                                                 Map<String, BackupCandidate> recoveries, Set<String> quarantined) {
            if (recoveries.isEmpty() || !(project.get("resources") instanceof List<?> rawResources)) {
                return false;
            }
            Set<String> recoveredIds = graphs.stream().filter(graph -> recoveries.containsKey(graph.path())
                    && !quarantined.contains(graph.path()))
                .map(GraphSource::id).collect(Collectors.toSet());
            List<Object> resources = new ArrayList<>(rawResources);
            project.put("resources", resources);
            boolean changed = false;
            for (int index = 0; index < resources.size(); index++) {
                Object raw = resources.get(index);
                if (!(raw instanceof Map<?, ?>)) {
                    continue;
                }
                Map<String, Object> resource = object(raw, "project resource");
                if (!"flow".equals(text(resource.get("type"))) || !recoveredIds.contains(text(resource.get("id")))) {
                    continue;
                }
                resource.put("type", "function");
                resource.put("path", "Blueprints/Functions");
                resources.set(index, resource);
                changed = true;
            }
            return changed;
        }

        private String functionGraphPath(String id) {
            return MigrationPaths.requireRelative(LegacyFlowGraphSnapshotAdapter.FUNCTION_PREFIX + "/" + id + ".json");
        }

        private long longValue(Object value, long fallback) {
            if (!(value instanceof Number number)) {
                return fallback;
            }
            try {
                if (number instanceof BigDecimal decimal) {
                    return decimal.longValueExact();
                }
                return number.longValue();
            } catch (ArithmeticException ignored) {
                return fallback;
            }
        }

        private boolean hasEntry(OfflineUpgradeSnapshotInput input, String path) {
            return !path.isBlank() && input.snapshot().entries().stream().anyMatch(entry -> entry.relativePath().equals(path));
        }

        private MetadataIssue invalidProjectMetadata(OfflineUpgradeSnapshotInput input) throws IOException {
            String path = projectPath(input);
            if (path.isBlank()) {
                return null;
            }
            ImmutableSnapshotAdapter.Entry entry = input.snapshot().entries().stream()
                .filter(value -> value.relativePath().equals(path)).findFirst().orElse(null);
            if (entry == null) {
                return null;
            }
            try {
                Map<String, Object> project = object(CanonicalCodec.decodePermissive(
                    Files.readAllBytes(MigrationPaths.resolveInside(input.root(), path))).toJava(), "project metadata");
                Object rawResources = project.get("resources");
                if (!(rawResources instanceof List<?> resources)) {
                    return new MetadataIssue(entry, "resources must be an array");
                }
                Map<String, String> identities = new LinkedHashMap<>();
                for (Object raw : resources) {
                    if (!(raw instanceof Map<?, ?>)) {
                        return new MetadataIssue(entry, "resource entries must be objects");
                    }
                    Map<String, Object> resource = object(raw, "project resource");
                    String type = text(resource.get("type"));
                    String id = text(resource.get("id"));
                    String folder = text(resource.get("path"));
                    if (type.isBlank() || id.isBlank() || folder.isBlank()) {
                        return new MetadataIssue(entry, "resource identity is incomplete");
                    }
                    String normalizedFolder = MigrationPaths.requireRelative(folder).replace('\\', '/');
                    String identity = type + "\u0000" + id;
                    String previous = identities.putIfAbsent(identity, normalizedFolder);
                    if (previous != null && !previous.equals(normalizedFolder)) {
                        return new MetadataIssue(entry, "resource identity has conflicting paths: " + type + "/" + id);
                    }
                }
                return null;
            } catch (RuntimeException | IOException failure) {
                return new MetadataIssue(entry, reason(failure));
            }
        }

        private Map<VariableKey, Set<String>> indexVariableTypes(OfflineUpgradeSnapshotInput input, List<GraphSource> graphs) {
            Map<VariableKey, Set<String>> result = new LinkedHashMap<>();
            for (GraphSource graph : graphs) {
                try {
                    GraphDocument document = readGraph(input, graph);
                    Map<String, Object> nodes = objectOrEmpty(document.root().get("nodes"));
                    for (Map.Entry<String, Object> entry : nodes.entrySet()) {
                        if (!(entry.getValue() instanceof Map<?, ?> raw)) {
                            continue;
                        }
                        Map<String, Object> node = object(raw, "node");
                        String type = text(node.get("type"));
                        if (!isVariableType(type) || wired(document.root(), entry.getKey(), Set.of("name", "scope", "persist"))) {
                            continue;
                        }
                        Map<String, Object> values = inputValues(node);
                        String action = variableAction(type, text(values.getOrDefault("mode", "get")));
                        if ("list".equals(action)) {
                            continue;
                        }
                        String name = text(first(values, "name", "key", "variable", "variable_name")).strip();
                        if (name.isBlank()) {
                            continue;
                        }
                        String scope = variableScope(values, type);
                        boolean persistent = booleanValue(values.get("persist"));
                        String inferred = inferVariableType(document.root(), entry.getKey(), node, values, action);
                        if (!"any".equals(inferred)) {
                            result.computeIfAbsent(new VariableKey(name.toLowerCase(Locale.ROOT), scope, persistent), ignored -> new LinkedHashSet<>()).add(inferred);
                        }
                    }
                } catch (RuntimeException | IOException ignored) {
                }
            }
            return result;
        }

        private Set<String> graphDependencies(OfflineUpgradeSnapshotInput input, GraphSource source,
                                               Map<String, GraphSource> identities) throws IOException {
            GraphDocument document = readGraph(input, source);
            Map<String, Object> nodes = objectOrEmpty(document.root().get("nodes"));
            Set<String> dependencies = new LinkedHashSet<>();
            for (Object raw : nodes.values()) {
                Map<String, Object> node = object(raw, "node");
                String type = text(node.get("type"));
                if (!isScheduleType(type)) {
                    continue;
                }
                Map<String, Object> values = inputValues(node);
                String targetId = referenceId(first(values, "flow_id", "function_id", "targetId", "target_id"));
                if (targetId.isBlank()) {
                    continue;
                }
                String targetType = targetType(values, canonicalScheduleType(type));
                GraphSource target = graphTarget(identities, targetType, targetId);
                if (target != null) {
                    dependencies.add(target.path());
                }
            }
            return Set.copyOf(dependencies);
        }

        private List<GraphSource> discoverGraphs(OfflineUpgradeSnapshotInput input) throws IOException {
            Map<String, GraphDeclaration> declarations = projectDeclarations(input);
            List<GraphSource> result = new ArrayList<>();
            for (ImmutableSnapshotAdapter.Entry entry : input.snapshot().entries()) {
                String path = entry.relativePath();
                if (path.equals("assets/project.json") || path.equals("project.json") || !path.startsWith("assets/")
                    || !path.endsWith(".json") || LegacyFlowGraphSnapshotAdapter.isTypedAutomationBackupPath(path)) {
                    continue;
                }
                Map<String, Object> document;
                try {
                    document = object(CanonicalCodec.decodePermissive(Files.readAllBytes(MigrationPaths.resolveInside(input.root(), path))).toJava(), "graph");
                } catch (RuntimeException | IOException ignored) {
                    continue;
                }
                String id = text(document.get("id"));
                String type = text(document.get("resourceType"));
                GraphDeclaration declaration = declarations.get(path);
                if (type.isBlank() && declaration != null) {
                    type = declaration.type();
                }
                if (type.isBlank() && Boolean.TRUE.equals(document.get("function"))) {
                    type = "function";
                }
                if (!GRAPH_TYPES.contains(type) || id.isBlank() || !document.containsKey("nodes")) {
                    continue;
                }
                result.add(new GraphSource(entry, path, type, id));
            }
            result.sort(Comparator.comparing(GraphSource::path));
            return result;
        }

        private Map<String, GraphDeclaration> projectDeclarations(OfflineUpgradeSnapshotInput input) throws IOException {
            Map<String, Object> project = readProject(input);
            if (project == null || !(project.get("resources") instanceof List<?> values)) {
                return Map.of();
            }
            Map<String, GraphDeclaration> result = new LinkedHashMap<>();
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> resource = object(raw, "project resource");
                String type = text(resource.get("type"));
                String id = text(resource.get("id"));
                String folder = text(resource.get("path"));
                if (!GRAPH_TYPES.contains(type) || id.isBlank() || folder.isBlank()) {
                    continue;
                }
                String normalizedFolder = MigrationPaths.requireRelative(folder);
                String normalized = normalizedFolder.startsWith("assets/") ? normalizedFolder : "assets/" + normalizedFolder;
                String path = normalized.endsWith(".json") ? normalized : normalized + "/" + id + ".json";
                if (LegacyFlowGraphSnapshotAdapter.isTypedAutomationBackupPath(path)) {
                    continue;
                }
                result.put(path, new GraphDeclaration(type, id));
                String typedPath = normalized + "/" + type + "__" + id + ".json";
                if (!LegacyFlowGraphSnapshotAdapter.isTypedAutomationBackupPath(typedPath)) {
                    result.putIfAbsent(typedPath, new GraphDeclaration(type, id));
                }
            }
            return result;
        }

        private Map<String, ExistingResource> existingResources(OfflineUpgradeSnapshotInput input) throws IOException {
            Map<String, ExistingResource> result = new LinkedHashMap<>();
            for (ImmutableSnapshotAdapter.Entry entry : input.snapshot().entries()) {
                if (!entry.relativePath().startsWith("assets/") || !entry.relativePath().endsWith(".json")) {
                    continue;
                }
                Map<String, Object> value;
                try {
                    value = object(CanonicalCodec.decodePermissive(Files.readAllBytes(MigrationPaths.resolveInside(input.root(), entry.relativePath()))).toJava(), "resource");
                } catch (RuntimeException | IOException ignored) {
                    continue;
                }
                String type = text(value.get("resourceType"));
                if (!VARIABLE.equals(type) && !SCHEDULE.equals(type)) {
                    continue;
                }
                String id = text(value.get("id"));
                if (id.isBlank()) {
                    continue;
                }
                String key = type + "\u0000" + id;
                ExistingResource previous = result.putIfAbsent(key, new ExistingResource(entry.relativePath(), value));
                if (previous != null) {
                    result.put(key, new ExistingResource("", Map.of()));
                }
            }
            return result;
        }

        private GraphDocument readGraph(OfflineUpgradeSnapshotInput input, GraphSource source) throws IOException {
            Map<String, Object> value = object(CanonicalCodec.decodePermissive(
                Files.readAllBytes(MigrationPaths.resolveInside(input.root(), source.path()))).toJava(), "graph");
            String id = text(value.get("id"));
            if (!source.id().equals(id)) {
                throw new IllegalArgumentException("Graph identity does not match its source declaration");
            }
            return new GraphDocument(value);
        }

        private boolean onlyContainsSkippedLegacyAutomation(OfflineUpgradeSnapshotInput input, GraphSource source) {
            try {
                Map<String, Object> nodes = objectOrEmpty(readGraph(input, source).root().get("nodes"));
                boolean legacyAutomation = false;
                for (Object raw : nodes.values()) {
                    Map<String, Object> node = object(raw, "node");
                    String type = text(node.get("type"));
                    if (isVariableType(type) || isScheduleType(type) || isCancelType(type)) {
                        legacyAutomation = true;
                        continue;
                    }
                    return false;
                }
                return legacyAutomation;
            } catch (RuntimeException | IOException ignored) {
                return false;
            }
        }

        private Map<String, Object> readProject(OfflineUpgradeSnapshotInput input) throws IOException {
            String path = projectPath(input);
            if (path.isBlank()) {
                return null;
            }
            try {
                return object(CanonicalCodec.decodePermissive(Files.readAllBytes(MigrationPaths.resolveInside(input.root(), path))).toJava(), "project metadata");
            } catch (RuntimeException | IOException ignored) {
                return null;
            }
        }

        private String projectPath(OfflineUpgradeSnapshotInput input) {
            return input.snapshot().entries().stream().map(ImmutableSnapshotAdapter.Entry::relativePath)
                .filter(path -> path.equals("assets/project.json") || path.equals("project.json")).sorted().findFirst().orElse("");
        }

        private NodeResult migrateVariable(Map<String, Object> graph, String nodeId, Map<String, Object> node,
                                           GraphSource source, Map<VariableKey, Set<String>> inferredTypes,
                                           Map<String, ExistingResource> existing, Map<ResourceIdentity, ResourceOutput> generated) {
            if (wired(graph, nodeId, Set.of("name", "scope", "persist"))) {
                return NodeResult.SKIPPED;
            }
            Map<String, Object> values = inputValues(node);
            String action = variableAction(text(node.get("type")), text(values.getOrDefault("mode", "get")));
            if ("list".equals(action)) {
                return NodeResult.SKIPPED;
            }
            String name = text(first(values, "name", "key", "variable", "variable_name")).strip();
            if (name.isBlank()) {
                return NodeResult.SKIPPED;
            }
            String scope = variableScope(values, text(node.get("type")));
            boolean persistent = booleanValue(values.get("persist"));
            String valueType = inferVariableType(graph, nodeId, node, values, action);
            VariableKey identity = new VariableKey(name.toLowerCase(Locale.ROOT), scope, persistent);
            Set<String> indexed = inferredTypes.getOrDefault(identity, Set.of());
            if ("any".equals(valueType)) {
                if (indexed.size() != 1) {
                    return NodeResult.SKIPPED;
                }
                valueType = indexed.iterator().next();
            }
            if (persistent && valueType.contains("any")) {
                return NodeResult.SKIPPED;
            }
            boolean dynamicAction = wired(graph, nodeId, Set.of("mode"));
            if (dynamicAction && countTargetConnections(graph, nodeId, "mode") != 1) {
                return NodeResult.UNRESOLVED;
            }
            String id = definitionId("variable", name, scope, persistent ? "persistent" : "runtime", valueType);
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("id", id);
            definition.put("name", name);
            String description = text(first(values, "description", "variable_description"));
            definition.put("description", description.isBlank() ? "Migrated Variable" : description);
            definition.put("valueType", valueType);
            definition.put("scope", scope);
            definition.put("persistent", persistent);
            Object defaultValue = values.containsKey("defaultValue") ? values.get("defaultValue") : values.get("default");
            if (defaultValue != null) {
                definition.put("defaultValue", deepCopy(defaultValue));
            }
            ResourceOutput output = resourceOutput(VARIABLE, id, definition, source.path(), existing);
            if (output == null) {
                return NodeResult.UNRESOLVED;
            }
            if (!putResource(generated, output)) {
                return NodeResult.UNRESOLVED;
            }
            Map<String, Object> replacement = new LinkedHashMap<>();
            values.keySet().removeAll(Set.of("mode", "scope", "persist", "name", "key", "variable", "variable_name", "player", "default", "defaultValue", "description", "variable_description"));
            replacement.putAll(values);
            replacement.put("variable", reference(VARIABLE, id));
            if (!dynamicAction) {
                replacement.put("action", capitalize(action));
            }
            Object owner = values.get("owner");
            if (owner == null) {
                owner = inputValues(node).get("player");
            }
            if (owner != null) {
                replacement.put("owner", owner);
            }
            node.put("type", "automation.variable");
            node.put("inputValues", replacement);
            node.put("handlerConfig", Map.of("operation", "variable_access"));
            renameTargetPin(graph, nodeId, "player", "owner");
            renameTargetPin(graph, nodeId, "mode", "action");
            return NodeResult.MIGRATED;
        }

        private NodeResult migrateSchedule(Map<String, Object> graph, String nodeId, Map<String, Object> node,
                                            GraphSource source, Map<String, GraphSource> identities,
                                            Map<String, ExistingResource> existing, Map<ResourceIdentity, ResourceOutput> generated) {
            String type = canonicalScheduleType(text(node.get("type")));
            if (wired(graph, nodeId, identityPins(type)) || outgoing(graph, nodeId, Set.of("result"))
                || (outgoing(graph, nodeId, Set.of("task_id")) && !recoverableTaskOutputs(graph, nodeId, type))) {
                return NodeResult.SKIPPED;
            }
            Map<String, Object> values = inputValues(node);
            String targetId = referenceId(first(values, "flow_id", "function_id", "targetId", "target_id"));
            if (targetId.isBlank()) {
                return NodeResult.SKIPPED;
            }
            String targetType = targetType(values, type);
            GraphSource target = graphTarget(identities, targetType, targetId);
            if (target == null) {
                return NodeResult.UNRESOLVED;
            }
            targetType = target.type();
            String definitionId = definitionId("schedule", source.id(), nodeId);
            Map<String, Object> definition = scheduleDefinition(definitionId, type, targetType, targetId, values);
            if (definition == null) {
                return NodeResult.SKIPPED;
            }
            ResourceOutput output = resourceOutput(SCHEDULE, definitionId, definition, source.path(), existing);
            if (output == null) {
                return NodeResult.UNRESOLVED;
            }
            if (!putResource(generated, output)) {
                return NodeResult.UNRESOLVED;
            }
            Map<String, Object> replacement = new LinkedHashMap<>();
            Object flow = values.get("flow");
            if (flow != null) {
                replacement.put("flow", flow);
            }
            replacement.put("schedule", reference(SCHEDULE, definitionId));
            node.put("type", "automation.schedule");
            node.put("inputValues", replacement);
            node.put("handlerConfig", Map.of("operation", "schedule_definition"));
            migrateScheduleSourcePins(graph, nodeId);
            return NodeResult.MIGRATED;
        }

        private NodeResult migrateCancel(Map<String, Object> graph, String nodeId, Map<String, Object> node,
                                         Map<String, String> originalTypes, Map<String, GraphSource> identities) {
            if (!recoverableCancel(graph, nodeId, originalTypes)) {
                return NodeResult.SKIPPED;
            }
            validateAndRewriteCancelConnections(graph, nodeId);
            Map<String, Object> values = inputValues(node);
            Map<String, Object> replacement = new LinkedHashMap<>();
            if (values.get("flow") != null) {
                replacement.put("flow", values.get("flow"));
            }
            replacement.put("action", "Cancel");
            node.put("type", "automation.scheduled_task");
            node.put("inputValues", replacement);
            node.put("handlerConfig", Map.of("operation", "scheduled_task"));
            return NodeResult.MIGRATED;
        }

        private void validateAndRewriteCancelConnections(Map<String, Object> graph, String nodeId) {
            List<Object> connections = listOrEmpty(graph.get("connections"));
            for (int index = 0; index < connections.size(); index++) {
                Map<String, Object> connection = objectOrEmpty(connections.get(index));
                connections.set(index, connection);
                if (nodeId.equals(text(connection.get("targetNodeId")))) {
                    String targetPin = text(connection.get("targetPin"));
                    if ("task_id".equals(targetPin)) {
                        connection.put("targetPin", "task");
                    } else if (!Set.of("flow", "action", "task", "schedule", "owner").contains(targetPin)) {
                        throw new IllegalArgumentException("Unsupported Scheduled Task Input Pin: " + targetPin);
                    }
                }
                if (nodeId.equals(text(connection.get("sourceNodeId")))) {
                    String sourcePin = text(connection.get("sourcePin"));
                    String replacement = cancelOutputPin(sourcePin);
                    connection.put("sourcePin", replacement);
                }
            }
            graph.put("connections", connections);
        }

        private String cancelOutputPin(String sourcePin) {
            return switch (sourcePin) {
                case "flow" -> "inactive";
                case "cancelled" -> "success";
                case "status" -> "state";
                case "task_id" -> "task";
                case "task", "success", "state", "active", "paused", "inactive", "remaining", "next_run", "last_run", "run_count", "last_result", "last_error" -> sourcePin;
                case "result", "failed", "error_code", "message" -> throw new IllegalArgumentException("Unsupported Scheduled Task Output Pin: " + sourcePin);
                default -> throw new IllegalArgumentException("Unknown Scheduled Task Output Pin: " + sourcePin);
            };
        }

        private Map<String, Object> scheduleDefinition(String id, String type, String targetType, String targetId, Map<String, Object> values) {
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("id", id);
            definition.put("name", "Migrated " + targetId);
            definition.put("description", "Migrated Schedule");
            definition.put("targetType", targetType);
            definition.put("targetId", targetId);
            definition.put("scope", "server");
            definition.put("persistent", booleanValue(values.get("persistent")));
            definition.put("overlapPolicy", normalizedOr(values.get("overlapPolicy"), "skip"));
            definition.put("existingTaskPolicy", normalizedOr(values.get("existingTaskPolicy"), "replace"));
            definition.put("failurePolicy", normalizedOr(values.get("failurePolicy"), "continue"));
            definition.put("offlinePolicy", normalizedOr(values.get("offlinePolicy"), "wait"));
            definition.put("missedRunPolicy", normalizedOr(values.get("missedRunPolicy"), "run_once"));
            switch (type) {
                case "schedule.schedule" -> {
                    String time = text(first(values, "time_string", "time"));
                    time = time.isBlank() ? "12:00" : time.strip();
                    String[] parts = time.split(":", -1);
                    if (parts.length < 2 || parts.length > 3 || !integerText(parts[0]) || !integerText(parts[1])
                        || (parts.length == 3 && (!integerText(parts[2]) || integerValue(parts[2]) != 0))) {
                        return null;
                    }
                    definition.put("timingMode", "cron");
                    definition.put("cron", integerValue(parts[1]) + " " + integerValue(parts[0]) + " * * *");
                    definition.put("timeZone", text(first(values, "time_zone", "timeZone")).isBlank() ? "UTC" : text(first(values, "time_zone", "timeZone")));
                }
                case "schedule.schedule_repeating" -> {
                    definition.put("timingMode", "repeating");
                    definition.put("duration", numberOr(values.get("interval_ticks"), 1200));
                    definition.put("unit", "ticks");
                    definition.put("initialDelay", numberOr(values.get("initial_delay_ticks"), 0));
                }
                case "schedule.interval" -> {
                    definition.put("timingMode", "repeating");
                    definition.put("duration", numberOr(values.get("seconds"), 1));
                    definition.put("unit", "seconds");
                    definition.put("initialDelay", numberOr(values.get("initial_delay_seconds"), 0));
                }
                case "schedule.cron" -> {
                    String expression = text(first(values, "expression", "cron", "pattern"));
                    expression = expression.isBlank() ? "0 12 * * *" : expression;
                    definition.put("timingMode", "cron");
                    definition.put("cron", expression);
                    definition.put("timeZone", text(first(values, "time_zone", "timeZone")).isBlank() ? "UTC" : text(first(values, "time_zone", "timeZone")));
                }
                case "schedule.at.time" -> {
                    String time = text(first(values, "time", "date_time", "dateTime"));
                    if (time.isBlank()) {
                        return null;
                    }
                    definition.put("timingMode", "at_time");
                    definition.put("dateTime", time);
                    definition.put("timeZone", text(first(values, "time_zone", "timeZone")).isBlank() ? "UTC" : text(first(values, "time_zone", "timeZone")));
                }
                default -> {
                    return null;
                }
            }
            return definition;
        }

        private ResourceOutput resourceOutput(String type, String id, Map<String, Object> payload, String anchor,
                                              Map<String, ExistingResource> existing) {
            ExistingResource existingValue = existing.get(type + "\u0000" + id);
            if (existingValue != null && existingValue.path().isBlank()) {
                return null;
            }
            if (existingValue != null && !ManagedResourceFileContract.matches(type, id, payload, existingValue.value())) {
                return null;
            }
            String target = existingValue != null ? MigrationPaths.requireRelative(existingValue.path()) : targetPath(type, id);
            return new ResourceOutput(new ResourceIdentity(type, id), payload, anchor, target, existingValue == null ? null : existingValue.path());
        }

        private Map<ResourceIdentity, ResourceOutput> resolveResources(Map<ResourceIdentity, List<ResourceOutput>> candidates,
                                                                       Map<ResourceIdentity, List<ResourceOutput>> resourceUsers,
                                                                       Map<String, GraphSource> graphSources,
                                                                       Set<String> quarantined,
                                                                       List<QuarantineRecord> quarantines,
                                                                       Set<String> recordedQuarantines) {
            Map<ResourceIdentity, ResourceOutput> generated = new LinkedHashMap<>();
            for (Map.Entry<ResourceIdentity, List<ResourceOutput>> entry : candidates.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceIdentity::canonical))).toList()) {
                List<ResourceOutput> values = entry.getValue().stream()
                    .sorted(Comparator.comparing(ResourceOutput::anchorPath)
                        .thenComparing(ResourceOutput::targetPath)
                        .thenComparing(this::resourcePayloadIdentity)).toList();
                Map<String, List<ResourceOutput>> byPayload = new LinkedHashMap<>();
                for (ResourceOutput value : values) {
                    byPayload.computeIfAbsent(resourcePayloadIdentity(value), ignored -> new ArrayList<>()).add(value);
                }
                if (byPayload.size() > 1) {
                    List<String> references = values.stream().map(ResourceOutput::anchorPath).distinct().sorted().toList();
                    for (String anchor : references) {
                        GraphSource graph = graphSources.get(anchor);
                        if (graph != null) {
                            quarantineGraph(graph, quarantined, quarantines, recordedQuarantines,
                                "MIGRATION.TYPED_AUTOMATION_RESOURCE_CONFLICT",
                                "The same typed automation resource identity resolves to different deterministic payloads.",
                                "Resolve every definition of the resource before retrying the migration.", references);
                        }
                    }
                    continue;
                }
                resourceUsers.put(entry.getKey(), List.copyOf(values));
                generated.put(entry.getKey(), values.getFirst());
            }
            return generated;
        }

        private void resolveGeneratedTargets(OfflineUpgradeSnapshotInput input, Map<ResourceIdentity, ResourceOutput> generated,
                                             Map<ResourceIdentity, List<ResourceOutput>> resourceUsers,
                                             Map<String, GraphSource> graphSources, Set<String> quarantined,
                                             List<QuarantineRecord> quarantines, Set<String> recordedQuarantines) {
            Map<String, List<ResourceOutput>> byTarget = new LinkedHashMap<>();
            for (Map.Entry<ResourceIdentity, ResourceOutput> entry : generated.entrySet()) {
                List<ResourceOutput> users = resourceUsers.getOrDefault(entry.getKey(), List.of(entry.getValue()));
                for (ResourceOutput output : users) {
                    byTarget.computeIfAbsent(output.targetPath(), ignored -> new ArrayList<>()).add(output);
                }
            }
            for (Map.Entry<String, List<ResourceOutput>> entry : byTarget.entrySet()) {
                List<ResourceOutput> values = entry.getValue();
                boolean conflicting = values.stream().map(ResourceOutput::identity).distinct().count() > 1;
                String target = entry.getKey();
                for (ResourceOutput output : values) {
                    if (occupiedGeneratedTarget(input, output)) {
                        conflicting = true;
                    }
                }
                if (!conflicting) {
                    continue;
                }
                List<String> references = values.stream().map(ResourceOutput::anchorPath).distinct().sorted().toList();
                for (String anchor : references) {
                    GraphSource graph = graphSources.get(anchor);
                    if (graph != null) {
                        quarantineGraph(graph, quarantined, quarantines, recordedQuarantines,
                            "MIGRATION.TYPED_AUTOMATION_TARGET_CONFLICT",
                            "A generated typed automation resource target is occupied or has conflicting content: " + target,
                            "Repair the target path and every resource declaration that uses it before retrying the migration.", references);
                    }
                }
            }
            List<ResourceOutput> values = generated.values().stream().sorted(Comparator.comparing(ResourceOutput::targetPath)).toList();
            for (int index = 0; index < values.size(); index++) {
                for (int next = index + 1; next < values.size(); next++) {
                    ResourceOutput first = values.get(index);
                    ResourceOutput second = values.get(next);
                    if (!isDescendant(first.targetPath(), second.targetPath())
                        && !isDescendant(second.targetPath(), first.targetPath())) {
                        continue;
                    }
                    List<ResourceOutput> related = new ArrayList<>();
                    related.addAll(resourceUsers.getOrDefault(first.identity(), List.of(first)));
                    related.addAll(resourceUsers.getOrDefault(second.identity(), List.of(second)));
                    List<String> references = related.stream().map(ResourceOutput::anchorPath).distinct().sorted().toList();
                    for (String anchor : references) {
                        GraphSource graph = graphSources.get(anchor);
                        if (graph != null) {
                            quarantineGraph(graph, quarantined, quarantines, recordedQuarantines,
                                "MIGRATION.TYPED_AUTOMATION_TARGET_CONFLICT",
                                "Generated typed automation resource targets have a file and directory prefix collision.",
                                "Give every generated resource a distinct non-overlapping target path.", references);
                        }
                    }
                }
            }
        }

        private void reanchorResources(Map<ResourceIdentity, ResourceOutput> generated,
                                       Map<ResourceIdentity, List<ResourceOutput>> resourceUsers,
                                       Set<String> quarantined) {
            for (ResourceIdentity identity : new ArrayList<>(generated.keySet())) {
                ResourceOutput current = generated.get(identity);
                List<ResourceOutput> surviving = resourceUsers.getOrDefault(identity, List.of(current)).stream()
                    .filter(output -> !quarantined.contains(output.anchorPath()))
                    .sorted(Comparator.comparing(ResourceOutput::anchorPath)
                        .thenComparing(ResourceOutput::targetPath)
                        .thenComparing(this::resourcePayloadIdentity)).toList();
                if (surviving.isEmpty()) {
                    generated.remove(identity);
                    continue;
                }
                ResourceOutput selected = surviving.getFirst();
                if (!selected.anchorPath().equals(current.anchorPath())) {
                    generated.put(identity, new ResourceOutput(current.identity(), current.payload(), selected.anchorPath(),
                        current.targetPath(), current.existingPath()));
                }
            }
        }

        private boolean occupiedGeneratedTarget(OfflineUpgradeSnapshotInput input, ResourceOutput output) {
            if (output.existingPath() != null && output.existingPath().equals(output.targetPath())) {
                return false;
            }
            String target = output.targetPath();
            if (input.snapshot().entries().stream().anyMatch(entry -> entry.relativePath().equals(target))) {
                return true;
            }
            if (input.snapshot().directories().stream().anyMatch(directory -> directory.equals(target))) {
                return true;
            }
            if (input.snapshot().entries().stream().anyMatch(entry -> isDescendant(target, entry.relativePath()))) {
                return true;
            }
            return false;
        }

        private void resolveDependencyClosure(Map<String, Set<String>> dependencies, Map<String, GraphSource> graphSources,
                                              Set<String> quarantined, List<QuarantineRecord> quarantines,
                                              Set<String> recordedQuarantines) {
            boolean changed;
            do {
                changed = false;
                for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
                    if (quarantined.contains(entry.getKey())) {
                        continue;
                    }
                    List<String> invalid = entry.getValue().stream().filter(quarantined::contains).sorted().toList();
                    if (invalid.isEmpty()) {
                        continue;
                    }
                    GraphSource graph = graphSources.get(entry.getKey());
                    if (graph != null) {
                        changed |= !quarantined.contains(entry.getKey());
                        List<String> references = new ArrayList<>();
                        references.add(entry.getKey());
                        references.addAll(invalid);
                        references.sort(String::compareTo);
                        quarantineGraph(graph, quarantined, quarantines, recordedQuarantines,
                            "MIGRATION.TYPED_AUTOMATION_DEPENDENCY_UNRESOLVED",
                            "The graph schedules a graph that was quarantined or removed from the migration transaction.",
                            "Repair the scheduled graph dependency before retrying the migration.",
                            references);
                    }
                }
            } while (changed);
        }

        private void quarantineGraph(GraphSource graph, Set<String> quarantined, List<QuarantineRecord> quarantines,
                                     Set<String> recordedQuarantines, String code, String reason, String action,
                                     List<String> references) {
            quarantined.add(graph.path());
            String key = graph.path() + "\u0000" + code;
            if (recordedQuarantines.add(key)) {
                quarantines.add(quarantine(graph.entry(), code, reason, action, references));
            }
        }

        private String resourcePayloadIdentity(ResourceOutput output) {
            return output.identity().type() + "\u0000" + JsonValue.fromJava(ManagedResourceFileContract.semantic(output.payload())).canonicalText();
        }

        private boolean putResource(Map<ResourceIdentity, ResourceOutput> generated, ResourceOutput output) {
            ResourceIdentity identity = output.identity();
            ResourceOutput previous = generated.putIfAbsent(identity, output);
            return previous == null || sameResource(previous.payload(), output.payload());
        }

        private boolean sameResource(Map<String, Object> left, Map<String, Object> right) {
            return JsonValue.fromJava(ManagedResourceFileContract.semantic(left)).canonicalText()
                .equals(JsonValue.fromJava(ManagedResourceFileContract.semantic(right)).canonicalText());
        }

        private boolean isDescendant(String path, String parent) {
            return !path.equals(parent) && path.startsWith(parent + "/");
        }

        private boolean ensureProjectResource(List<Object> resources, ResourceOutput output) {
            String folder = folder(output.targetPath());
            for (Object raw : resources) {
                if (!(raw instanceof Map<?, ?>)) {
                    continue;
                }
                Map<String, Object> value = object(raw, "project resource");
                if (output.identity().type().equals(text(value.get("type"))) && output.identity().id().equals(text(value.get("id")))) {
                    boolean changed = false;
                    if (!folder.equals(text(value.get("path")))) {
                        value.put("path", folder);
                        changed = true;
                    }
                    if (text(value.get("displayName")).isBlank()) {
                        value.put("displayName", text(output.payload().get("name")));
                        changed = true;
                    }
                    return changed;
                }
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("type", output.identity().type());
            value.put("id", output.identity().id());
            value.put("displayName", text(output.payload().get("name")));
            value.put("path", folder);
            value.put("sortOrder", resources.size());
            resources.add(value);
            return true;
        }

        private List<Object> graphMetadata(List<GraphSource> graphs, Set<String> quarantined) {
            List<Object> values = new ArrayList<>();
            for (GraphSource graph : graphs.stream().filter(value -> !quarantined.contains(value.path())).sorted(Comparator.comparing(GraphSource::path)).toList()) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("type", graph.type());
                value.put("id", graph.id());
                value.put("displayName", graph.id());
                value.put("path", folder(graph.path()));
                value.put("sortOrder", values.size());
                values.add(value);
            }
            return values;
        }

        private String targetPath(String type, String id) {
            return MigrationPaths.requireRelative("assets/" + (VARIABLE.equals(type) ? "Automation/Variables/" : "Automation/Schedules/") + id + ".json");
        }

        private String folder(String path) {
            String normalized = path.replace('\\', '/');
            int slash = normalized.lastIndexOf('/');
            String folder = slash < 0 ? "" : normalized.substring(0, slash);
            return folder.startsWith("assets/") ? folder.substring("assets/".length()) : folder;
        }

        private boolean rewriteConnections(Map<String, Object> graph, Map<String, String> originalTypes,
                                           Map<String, Object> nodes, Map<String, Boolean> authoredPinEligibility,
                                           boolean nodesChanged) {
            boolean authoredGraph = nodes.values().stream().anyMatch(raw -> {
                Map<String, Object> node = objectOrEmpty(raw);
                String type = text(node.get("type"));
                return AUTHORED_AUTOMATION_TYPES.contains(type);
            });
            if (!authoredGraph && !nodesChanged) {
                return false;
            }
            List<Object> connections = listOrEmpty(graph.get("connections"));
            boolean changed = false;
            Map<String, Set<String>> sourcePins = new LinkedHashMap<>();
            Map<String, String> targetConnections = new LinkedHashMap<>();
            Set<String> connectionKeys = new HashSet<>();
            List<ConnectionRewrite> rewrites = new ArrayList<>();
            for (int index = 0; index < connections.size(); index++) {
                Map<String, Object> connection = objectOrEmpty(connections.get(index));
                connections.set(index, connection);
                String sourceId = text(connection.get("sourceNodeId"));
                String targetId = text(connection.get("targetNodeId"));
                String sourcePin = text(connection.get("sourcePin"));
                String targetPin = text(connection.get("targetPin"));
                String sourceType = originalTypes.getOrDefault(sourceId, text(objectOrEmpty(nodes.get(sourceId)).get("type")));
                String targetType = originalTypes.getOrDefault(targetId, text(objectOrEmpty(nodes.get(targetId)).get("type")));
                String currentSourceType = text(objectOrEmpty(nodes.get(sourceId)).get("type"));
                String currentTargetType = text(objectOrEmpty(nodes.get(targetId)).get("type"));
                boolean sourceEligible = authoredPinEligibility.getOrDefault(sourceId, false);
                boolean targetEligible = authoredPinEligibility.getOrDefault(targetId, false);
                String mappedSource = sourceEligible ? authoredOutputPin(currentSourceType, sourcePin) : null;
                if (mappedSource == null && "automation.schedule".equals(currentSourceType)
                    && sourceEligible && !AUTHORED_AUTOMATION_TYPES.contains(sourceType)) {
                    mappedSource = scheduleOutputPin(sourcePin);
                }
                if (mappedSource == null && "automation.scheduled_task".equals(currentSourceType)
                    && sourceEligible && !AUTHORED_AUTOMATION_TYPES.contains(sourceType)) {
                    mappedSource = cancelOutputPin(sourcePin);
                }
                if (mappedSource == null) {
                    mappedSource = sourcePin;
                }
                String mappedTarget = targetPin;
                if (targetEligible && "automation.scheduled_task".equals(currentTargetType) && "task_id".equals(targetPin)) {
                    mappedTarget = "task";
                }
                if (sourceEligible || targetEligible) {
                    String sourceKey = sourceId + "\u0000" + mappedSource;
                    Set<String> originalPins = sourcePins.computeIfAbsent(sourceKey, ignored -> new LinkedHashSet<>());
                    originalPins.add(sourcePin);
                    if (originalPins.size() > 1) {
                        throw new IllegalArgumentException("TYPED_AUTOMATION_OUTPUT_PIN_COLLISION");
                    }
                    String targetKey = targetId + "\u0000" + mappedTarget;
                    String endpoint = sourceId + "\u0000" + sourcePin;
                    String previousEndpoint = targetConnections.putIfAbsent(targetKey, endpoint);
                    if (previousEndpoint != null && !previousEndpoint.equals(endpoint)) {
                        throw new IllegalArgumentException("TYPED_AUTOMATION_CONNECTION_AMBIGUOUS");
                    }
                    String connectionKey = endpoint + "\u0000" + targetKey;
                    if (!connectionKeys.add(connectionKey)) {
                        throw new IllegalArgumentException("TYPED_AUTOMATION_CONNECTION_COLLISION");
                    }
                }
                rewrites.add(new ConnectionRewrite(connection, mappedSource, mappedTarget));
            }
            for (ConnectionRewrite rewrite : rewrites) {
                Map<String, Object> connection = rewrite.connection();
                String sourcePin = text(connection.get("sourcePin"));
                String targetPin = text(connection.get("targetPin"));
                if (!rewrite.sourcePin().equals(sourcePin)) {
                    connection.put("sourcePin", rewrite.sourcePin());
                    changed = true;
                }
                if (!rewrite.targetPin().equals(targetPin)) {
                    connection.put("targetPin", rewrite.targetPin());
                    changed = true;
                }
            }
            graph.put("connections", connections);
            return changed;
        }

        private AuthoredSchemaMigration migrateAuthoredSchemaVersion(Map<String, Object> node, String originalType) {
            String type = text(node.get("type"));
            if (!AUTHORED_AUTOMATION_TYPES.contains(type)) {
                return AuthoredSchemaMigration.INELIGIBLE;
            }
            int target = authoredSchemaVersion(type);
            if (!AUTHORED_AUTOMATION_TYPES.contains(originalType)) {
                boolean changed = !versionEquals(node.get("version"), target);
                if (changed) {
                    node.put("version", target);
                }
                return new AuthoredSchemaMigration(true, changed);
            }
            int current = schemaVersion(node.get("version"));
            if (current == authoredSourceSchemaVersion(type)) {
                node.put("version", target);
                return new AuthoredSchemaMigration(true, current != target);
            }
            if (current == target) {
                return AuthoredSchemaMigration.INELIGIBLE;
            }
            throw new IllegalArgumentException("TYPED_AUTOMATION_SCHEMA_VERSION_SOURCE_MISMATCH");
        }

        private boolean versionEquals(Object value, int expected) {
            if (!(value instanceof Number number)) {
                return false;
            }
            try {
                return new BigDecimal(number.toString()).intValueExact() == expected;
            } catch (ArithmeticException exception) {
                return false;
            }
        }

        private int authoredSourceSchemaVersion(String type) {
            return switch (type) {
                case "automation.timer" -> 2;
                case "automation.variable", "automation.schedule", "automation.scheduled_task",
                     "event.variable.changed", "event.timer", "event.scheduled_task", "event.schedule" -> 1;
                default -> throw new IllegalArgumentException("Unknown typed automation node: " + type);
            };
        }

        private int authoredSchemaVersion(String type) {
            return switch (type) {
                case "automation.variable" -> 2;
                case "automation.timer" -> 3;
                case "automation.schedule", "automation.scheduled_task", "event.variable.changed", "event.timer",
                     "event.scheduled_task", "event.schedule" -> 2;
                default -> throw new IllegalArgumentException("Unknown typed automation node: " + type);
            };
        }

        private int schemaVersion(Object value) {
            if (value == null) {
                return 1;
            }
            if (!(value instanceof Number number)) {
                throw new IllegalArgumentException("TYPED_AUTOMATION_SCHEMA_VERSION_INVALID");
            }
            try {
                return new BigDecimal(number.toString()).intValueExact();
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("TYPED_AUTOMATION_SCHEMA_VERSION_INVALID", exception);
            }
        }

        private String authoredOutputPin(String type, String pin) {
            return switch (type) {
                case "automation.variable" -> switch (pin) {
                    case "flow" -> "output_flow";
                    case "variable" -> "output_variable";
                    case "value" -> "output_value";
                    default -> null;
                };
                case "automation.timer" -> switch (pin) {
                    case "timer" -> "output_timer";
                    case "duration" -> "output_duration";
                    default -> null;
                };
                case "automation.schedule" -> "schedule".equals(pin) ? "output_schedule" : null;
                case "automation.scheduled_task" -> switch (pin) {
                    case "task", "task_id" -> "output_task";
                    default -> null;
                };
                case "event.variable.changed" -> switch (pin) {
                    case "variable", "event.variable" -> "output_variable";
                    default -> null;
                };
                case "event.timer" -> switch (pin) {
                    case "timer", "event.timer" -> "output_timer";
                    case "duration", "event.duration" -> "output_duration";
                    default -> null;
                };
                case "event.scheduled_task", "event.schedule" -> switch (pin) {
                    case "schedule", "event.schedule" -> "output_schedule";
                    default -> null;
                };
                default -> null;
            };
        }

        private boolean recoverableTaskOutputs(Map<String, Object> graph, String nodeId, String type) {
            List<Object> connections = listOrEmpty(graph.get("connections"));
            List<Map<String, Object>> outputs = connections.stream().map(value -> objectOrEmpty(value))
                .filter(value -> nodeId.equals(text(value.get("sourceNodeId"))) && "task_id".equals(text(value.get("sourcePin")))).toList();
            return !outputs.isEmpty() && outputs.stream().allMatch(connection -> {
                Map<String, Object> target = objectOrEmpty(objectOrEmpty(graph.get("nodes")).get(text(connection.get("targetNodeId"))));
                String targetType = text(target.get("type"));
                return (isCancelType(targetType) || "automation.scheduled_task".equals(targetType))
                    && Set.of("task_id", "task").contains(text(connection.get("targetPin")));
            });
        }

        private boolean recoverableCancel(Map<String, Object> graph, String nodeId, Map<String, String> originalTypes) {
            List<Object> connections = listOrEmpty(graph.get("connections"));
            List<Map<String, Object>> inputs = connections.stream().map(value -> objectOrEmpty(value))
                .filter(value -> nodeId.equals(text(value.get("targetNodeId"))) && Set.of("task_id", "task").contains(text(value.get("targetPin")))).toList();
            return inputs.size() == 1 && isScheduleType(originalTypes.getOrDefault(text(inputs.getFirst().get("sourceNodeId")), ""));
        }

        private Set<String> identityPins(String type) {
            return switch (type) {
                case "schedule.schedule" -> Set.of("flow_id", "time_string", "time_zone");
                case "schedule.schedule_repeating" -> Set.of("flow_id", "interval_ticks");
                case "schedule.interval" -> Set.of("flow_id", "seconds");
                case "schedule.cron" -> Set.of("flow_id", "expression", "time_zone");
                case "schedule.at.time" -> Set.of("flow_id", "time", "time_zone");
                default -> Set.of("flow_id", "function_id", "targetId", "target_id");
            };
        }

        private void migrateScheduleSourcePins(Map<String, Object> graph, String nodeId) {
            List<Object> connections = listOrEmpty(graph.get("connections"));
            for (int index = 0; index < connections.size(); index++) {
                Map<String, Object> connection = objectOrEmpty(connections.get(index));
                connections.set(index, connection);
                if (!nodeId.equals(text(connection.get("sourceNodeId")))) {
                    continue;
                }
                String pin = text(connection.get("sourcePin"));
                connection.put("sourcePin", scheduleOutputPin(pin));
            }
            graph.put("connections", connections);
        }

        private String scheduleOutputPin(String sourcePin) {
            return switch (sourcePin) {
                case "flow" -> "scheduled";
                case "scheduled" -> "success";
                case "task_id" -> "task";
                case "failed", "task", "success", "message", "schedule" -> sourcePin;
                case "result", "error_code" -> throw new IllegalArgumentException("Unsupported Schedule Output Pin: " + sourcePin);
                default -> throw new IllegalArgumentException("Unknown Schedule Output Pin: " + sourcePin);
            };
        }

        private void renameTargetPin(Map<String, Object> graph, String nodeId, String oldPin, String newPin) {
            List<Object> connections = listOrEmpty(graph.get("connections"));
            for (int index = 0; index < connections.size(); index++) {
                Map<String, Object> connection = objectOrEmpty(connections.get(index));
                connections.set(index, connection);
                if (nodeId.equals(text(connection.get("targetNodeId"))) && oldPin.equals(text(connection.get("targetPin")))) {
                    connection.put("targetPin", newPin);
                }
            }
            graph.put("connections", connections);
        }

        private boolean wired(Map<String, Object> graph, String nodeId, Set<String> pins) {
            return listOrEmpty(graph.get("connections")).stream().anyMatch(raw -> {
                Map<String, Object> connection = objectOrEmpty(raw);
                return nodeId.equals(text(connection.get("targetNodeId"))) && pins.contains(text(connection.get("targetPin")));
            });
        }

        private int countTargetConnections(Map<String, Object> graph, String nodeId, String pin) {
            int count = 0;
            for (Object raw : listOrEmpty(graph.get("connections"))) {
                Map<String, Object> connection = objectOrEmpty(raw);
                if (nodeId.equals(text(connection.get("targetNodeId"))) && pin.equals(text(connection.get("targetPin")))) {
                    count++;
                }
            }
            return count;
        }

        private boolean outgoing(Map<String, Object> graph, String nodeId, Set<String> pins) {
            return listOrEmpty(graph.get("connections")).stream().anyMatch(raw -> {
                Map<String, Object> connection = objectOrEmpty(raw);
                return nodeId.equals(text(connection.get("sourceNodeId"))) && pins.contains(text(connection.get("sourcePin")));
            });
        }

        private String inferVariableType(Map<String, Object> graph, String nodeId, Map<String, Object> node,
                                         Map<String, Object> values, String action) {
            if (Set.of("increment", "decrement", "multiply", "divide").contains(action)) {
                return "number";
            }
            Object value = values.get("value");
            if (value instanceof Number) {
                return "number";
            }
            if (value instanceof Boolean) {
                return "boolean";
            }
            if (value instanceof List<?>) {
                return "list<any>";
            }
            if (value instanceof Map<?, ?>) {
                return "map<string,any>";
            }
            if (value instanceof String) {
                return "string";
            }
            String declared = text(first(values, "valueType", "type"));
            if (!declared.isBlank() && !declared.equalsIgnoreCase("any")) {
                return declared;
            }
            for (Object raw : listOrEmpty(graph.get("connections"))) {
                Map<String, Object> connection = objectOrEmpty(raw);
                if (!nodeId.equals(text(connection.get("targetNodeId"))) || !"value".equals(text(connection.get("targetPin")))) {
                    continue;
                }
                Map<String, Object> source = objectOrEmpty(objectOrEmpty(graph.get("nodes")).get(text(connection.get("sourceNodeId"))));
                Object types = source.get("outputTypes");
                if (types instanceof Map<?, ?> outputTypes) {
                    String type = text(outputTypes.get(text(connection.get("sourcePin"))));
                    if (!type.isBlank() && !type.equalsIgnoreCase("any")) {
                        return type;
                    }
                }
                Object outputs = source.get("outputs");
                if (outputs instanceof List<?> list) {
                    for (Object output : list) {
                        Map<String, Object> valueOutput = objectOrEmpty(output);
                        if (text(connection.get("sourcePin")).equals(text(valueOutput.get("name")))) {
                            String type = text(first(valueOutput, "type", "typeRef", "dataType"));
                            if (!type.isBlank() && !type.equalsIgnoreCase("any")) {
                                return type;
                            }
                        }
                    }
                }
            }
            return "any";
        }

        private String variableAction(String type, String configured) {
            String action = configured == null || configured.isBlank() ? "get" : configured.toLowerCase(Locale.ROOT);
            return switch (type) {
                case "variable_set_global", "variable.variable_set_global" -> "set";
                case "variable_set_local", "variable.variable_set_local" -> "set";
                case "variable_set_player", "variable.variable_set_player" -> "set";
                case "variable_get_global", "variable_get_local", "variable_get_player",
                    "variable.variable_get_global", "variable.variable_get_local", "variable.variable_get_player" -> "get";
                case "variable_delete", "variable.variable_delete_global", "variable.variable_delete_local",
                    "variable.variable_clear_global", "variable.variable_clear_local" -> "delete";
                case "variable_exists", "variable.variable_exists_global", "variable.variable_exists_local" -> "exists";
                case "variable_list_all", "variable.variable_list_global", "variable.variable_list_local" -> "list";
                case "variable_increment" -> "increment";
                case "variable_decrement" -> "decrement";
                case "variable_multiply" -> "multiply";
                case "variable_divide" -> "divide";
                default -> action;
            };
        }

        private String variableScope(Map<String, Object> values, String type) {
            String configured = text(values.get("scope")).toLowerCase(Locale.ROOT);
            if (type.endsWith("_global") || "global".equals(configured)) {
                return "server";
            }
            if (type.endsWith("_player") || "player".equals(configured)) {
                return "player";
            }
            return "flow";
        }

        private String targetType(Map<String, Object> values, String type) {
            String configured = text(first(values, "targetType", "target_type")).toLowerCase(Locale.ROOT);
            if (configured.equals("function") || configured.equals("flow")) {
                return configured;
            }
            return text(values.get("function_id")).isBlank() ? "flow" : "function";
        }

        private GraphSource graphTarget(Map<String, GraphSource> identities, String targetType, String targetId) {
            GraphSource target = identities.get(targetType + "\u0000" + targetId);
            if (target == null && "flow".equals(targetType)) {
                target = identities.get("function\u0000" + targetId);
            }
            return target;
        }

        private String canonicalScheduleType(String type) {
            return switch (type) {
                case "schedule" -> "schedule.schedule";
                case "schedule_at_time" -> "schedule.at.time";
                case "schedule_repeating", "schedule_interval" -> "schedule_interval".equals(type) ? "schedule.interval" : "schedule.schedule_repeating";
                case "schedule_cron" -> "schedule.cron";
                default -> type;
            };
        }

        private String referenceId(Object value) {
            if (value instanceof String text) {
                return text.strip();
            }
            if (value instanceof Map<?, ?> map) {
                return text(map.get("id"));
            }
            return "";
        }

        private String definitionId(String prefix, String... parts) {
            List<String> values = new ArrayList<>();
            values.add(prefix);
            for (String part : parts) {
                String normalized = text(part).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_").replaceAll("^_+|_+$", "");
                if (!normalized.isBlank()) {
                    values.add(normalized);
                }
            }
            return "migrated." + String.join(".", values);
        }

        private String normalizedOr(Object value, String fallback) {
            String text = text(value).strip().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            return text.isBlank() ? fallback : text;
        }

        private boolean integerText(String value) {
            try {
                int parsed = Integer.parseInt(value);
                return parsed >= 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }

        private int integerValue(String value) {
            return Integer.parseInt(value);
        }

        private Object numberOr(Object value, Number fallback) {
            return value instanceof Number number ? number : fallback;
        }

        private boolean booleanValue(Object value) {
            return value instanceof Boolean bool ? bool : Boolean.parseBoolean(text(value));
        }

        private boolean isVariableType(String type) {
            return VARIABLE_TYPES.contains(type) || type.startsWith("variable_") && !type.startsWith("variable.");
        }

        private boolean isScheduleType(String type) {
            return SCHEDULE_TYPES.contains(type);
        }

        private boolean isCancelType(String type) {
            return CANCEL_TYPES.contains(type);
        }

        private static Map<String, Object> inputValues(Map<String, Object> node) {
            return objectOrEmpty(node.get("inputValues"));
        }

        private static Object first(Map<String, Object> values, String... names) {
            for (String name : names) {
                if (values.containsKey(name) && values.get(name) != null) {
                    return values.get(name);
                }
            }
            return null;
        }

        private static String capitalize(String value) {
            return value == null || value.isBlank() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
        }

        private static Map<String, Object> reference(String type, String id) {
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put("kind", type);
            reference.put("id", id);
            reference.put("owner", "server");
            reference.put("available", true);
            reference.put("metadata", Map.of());
            return reference;
        }

        private static Map<String, Object> object(Object value, String field) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("Expected " + field + " object");
            }
            return deepObject(map);
        }

        private static Map<String, Object> objectOrEmpty(Object value) {
            return value == null ? new LinkedHashMap<>() : object(value, "object");
        }

        private static List<Object> listOrEmpty(Object value) {
            if (value == null) {
                return new ArrayList<>();
            }
            if (!(value instanceof List<?> list)) {
                throw new IllegalArgumentException("Expected array");
            }
            return list.stream().map(TypedAutomationPhase::deepCopy).collect(Collectors.toCollection(ArrayList::new));
        }

        private static Map<String, Object> deepObject(Object value) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("Expected object");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Object keys must be strings");
                }
                result.put(key, deepCopy(entry.getValue()));
            }
            return result;
        }

        private static Object deepCopy(Object value) {
            if (value instanceof Map<?, ?>) {
                return deepObject(value);
            }
            if (value instanceof List<?> list) {
                return list.stream().map(TypedAutomationPhase::deepCopy).toList();
            }
            return value;
        }

        private static String text(Object value) {
            return value instanceof String text ? text : "";
        }

        private static QuarantineRecord quarantine(ImmutableSnapshotAdapter.Entry entry, String code, String reason,
                                                   String action, List<String> refs) {
            String digest = sha256((code + "\u0000" + entry.relativePath() + "\u0000" + entry.sha256() + "\u0000" + reason
                + "\u0000" + String.join("\u0000", refs == null ? List.of() : refs)).getBytes(StandardCharsets.UTF_8));
            return new QuarantineRecord("typed-automation-" + digest.substring(0, 24), code, entry.relativePath(), reason,
                refs == null ? List.of() : refs, action, entry.sha256());
        }

        private static String reason(Exception exception) {
            String message = exception.getMessage();
            return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
        }

        private static String sha256(byte[] bytes) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }

        private enum NodeResult {
            MIGRATED,
            SKIPPED,
            UNCHANGED,
            UNRESOLVED
        }

        private record GraphSource(ImmutableSnapshotAdapter.Entry entry, String path, String type, String id) {
        }

        private record GraphDeclaration(String type, String id) {
        }

        private record GraphDocument(Map<String, Object> root) {
        }

        private record ConnectionRewrite(Map<String, Object> connection, String sourcePin, String targetPin) {
        }

        private record AuthoredSchemaMigration(boolean pinEligible, boolean changed) {
            private static final AuthoredSchemaMigration INELIGIBLE = new AuthoredSchemaMigration(false, false);
        }

        private record MetadataIssue(ImmutableSnapshotAdapter.Entry entry, String reason) {
        }

        private record VariableKey(String name, String scope, boolean persistent) {
        }

        private record ResourceIdentity(String type, String id) {
            private String canonical() {
                return type + "\u0000" + id;
            }
        }

        private record ExistingResource(String path, Map<String, Object> value) {
        }

        private record ResourceOutput(ResourceIdentity identity, Map<String, Object> payload, String anchorPath,
                                      String targetPath, String existingPath) {
        }

        private record BackupPath(String migrationId, String graphId, boolean validShape) {
        }

        private record BackupCandidate(String migrationId, String graphId, String path, Map<String, Object> envelope,
                                       boolean valid, String reason, long revision, String fingerprint) {
        }

        private record BackupGroup(String migrationId, List<BackupCandidate> candidates, long revision) {
            private BackupGroup {
                candidates = List.copyOf(candidates);
            }
        }

        private enum BackupStatus {
            RECOVER,
            INVALID,
            MISMATCH,
            AMBIGUOUS
        }

        private record BackupDecision(BackupStatus status, BackupCandidate candidate, String reason,
                                      List<String> references) {
            private BackupDecision {
                references = List.copyOf(references);
            }
        }

        private record Result(List<OfflineUpgradeSnapshotAdapter.FileTransform> files, Set<String> claimedPaths,
                              List<QuarantineRecord> quarantines, Set<String> quarantinedGraphPaths,
                              Map<String, Set<String>> graphDependencies,
                              Map<String, Set<String>> generatedResourceContributors,
                              List<GraphSource> graphs) {
            private Result {
                files = List.copyOf(files == null ? List.of() : files);
                claimedPaths = Set.copyOf(claimedPaths == null ? Set.of() : claimedPaths);
                quarantines = List.copyOf(quarantines == null ? List.of() : quarantines);
                quarantinedGraphPaths = Set.copyOf(quarantinedGraphPaths == null ? Set.of() : quarantinedGraphPaths);
                graphDependencies = graphDependencies == null ? Map.of() : graphDependencies.entrySet().stream()
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, value -> Set.copyOf(value.getValue())));
                generatedResourceContributors = generatedResourceContributors == null ? Map.of() : generatedResourceContributors.entrySet().stream()
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, value -> Set.copyOf(value.getValue())));
                graphs = List.copyOf(graphs == null ? List.of() : graphs);
            }

            private static Result empty() {
                return new Result(List.of(), Set.of(), List.of(), Set.of(), Map.of(), Map.of(), List.of());
            }
        }
    }

    private record ReanchoredTypedFiles(List<OfflineUpgradeSnapshotAdapter.FileTransform> files,
                                        Set<String> quarantinedGeneratedTargets) {
        private ReanchoredTypedFiles {
            files = List.copyOf(files);
            quarantinedGeneratedTargets = Set.copyOf(quarantinedGeneratedTargets);
        }
    }

    private record ReanchoredManagedFiles(List<OfflineUpgradeSnapshotAdapter.FileTransform> files) {
        private ReanchoredManagedFiles {
            files = List.copyOf(files);
        }
    }

    private record Overlay(OfflineUpgradeSnapshotInput input, Path parent) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            deleteTree(parent);
        }
    }
}
