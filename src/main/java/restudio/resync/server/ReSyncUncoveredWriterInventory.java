package restudio.resync.server;

import restudio.resync.migration.ManagedFlowFileStoreContract;
import restudio.resync.migration.MigrationReportsPersistenceParticipant;
import restudio.resync.migration.PersistenceExternalInput;
import restudio.resync.migration.PersistenceRootReadiness;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.flow.cache.CatalogPublicationReceiptStore;
import restudio.resync.flow.runtime.DurableRuntimeReceiptStore;
import restudio.resync.flow.PersistentVariableStore;
import restudio.resync.flow.handler.generic.RegionPersistenceParticipant;
import restudio.resync.network.paper.ReSyncNetworkAgentConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

public final class ReSyncUncoveredWriterInventory {
    private static final String CONFIGURATION_REASON = "ConfigLoader and FlowStorage persist config.properties, and no single owner exposes a coordinated flush, quiesce, resume, root rebind, and health-check lifecycle";
    private static final String INSTALL_IDENTITY_REASON = "The server identity, install-generation signal, and their canonical durable authority must be admitted as one exact persistence participant with coordinated flush, quiesce, resume, root rebind, and health-check lifecycle";
    private static final String EXTENSIONS_REASON = "ReSyncExtensionManager exposes extensions/<pluginId> storage to extension code but has no coordinated write fence, flush, root rebind, or health-check lifecycle; extension-owned writers cannot be inferred as safe";
    private static final String NETWORK_REASON = "Network persistence is not one fenced owner: NetworkResourceManifestStore and NetworkPathSynchronizer write network/resource-manifest.json and network/path-manifest-<id>.json; NetworkSnapshotOutbox writes network/snapshot-outbox/*.snapshot; NetworkPlayerStateReconciler writes network/reconciliation-backups/** and mutates world playerdata/*.dat; ReSyncNetworkAgentConfig writes network/node.credential; and enabled path sync can write externally rooted server paths such as plugins/LuckPerms/**. These writers do not share one coordinated flush, quiesce, resume, root rebind, and health-check lifecycle";
    public static final String LUCKPERMS_BACKEND_REASON = "The external LuckPerms backend is not owned by ReSync and has no coordinated snapshot, quiesce, or atomic rebind participant";
    private static final String LUCKPERMS_BACKEND_OWNER = "resync.runtime.luckperms-backend";
    private static final String LUCKPERMS_BACKEND_PATH = "runtime/luckperms-backend";
    private static final String LUCKPERMS_BACKEND_AUTHORITY = "LuckPerms backend persistence authority";
    private static final Set<String> KNOWN_RUNTIME_ENTRIES = Set.of(
        "automation-tasks.json",
        "automation-tasks.json.previous",
        "luckperms-operations.json",
        "luckperms-backend",
        "player-npcs.json",
        DurableRuntimeReceiptStore.FILE_NAME,
        "resource-mutations.db",
        "resource-mutations.db-wal",
        "resource-mutations.db-shm",
        ".quarantine"
    );
    private static final String STRUCTURES_REASON = "StructureLibrary writes structures directly and its lifecycle ownership is not yet proven";
    private static final String TRIGGERS_REASON = "TriggerRegistry owns the root-level triggers.json file, but file-root topology and its lifecycle ownership are not yet admitted by the coordinated participant registry";
    private static final String WORLD_MANAGEMENT_REASON = "World-management lifecycle is proven by its participant, but production registration must be established before this writer can be considered covered";
    private static final String PLAYER_DOSSIERS_REASON = "PlayerTrackingManager writes player dossiers atomically but has no flush, quiesce, resume, restore rebind, or health-check lifecycle";
    private static final String FLOW_FILES_REASON = "Managed Flow File storage owns flow-files and its SQLite sidecars; if legacy physical FileHandler bytes are present, they require an explicit manifest-backed offline migration and runtime startup never scans or deletes arbitrary legacy bytes";
    private static final String CATALOG_PUBLICATION_RECEIPTS_REASON = "Catalog publication receipts require one coordinated exact-file owner with flush, quiesce, resume, atomic root rebind, health-check, and shutdown lifecycle";
    private static final String MIGRATION_REPORTS_REASON = "Migration reports and recoverable legacy backups are owned only by the exact admitted resync.migration-reports participant at dataRoot/.migrations";
    private static final List<WriterDefinition> DEFINITIONS = List.of(
        new WriterDefinition(ServerIdentityStore.OWNER, "server-id", INSTALL_IDENTITY_REASON),
        new WriterDefinition(CatalogPublicationReceiptStore.OWNER, CatalogPublicationReceiptStore.FILE_NAME, CATALOG_PUBLICATION_RECEIPTS_REASON),
        new WriterDefinition(MigrationReportsPersistenceParticipant.OWNER, MigrationReportsPersistenceParticipant.DIRECTORY, MIGRATION_REPORTS_REASON),
        new WriterDefinition("resync.configuration", "config.properties", CONFIGURATION_REASON),
        new WriterDefinition("resync.extensions", "extensions", EXTENSIONS_REASON),
        new WriterDefinition(ManagedFlowFileStoreContract.OWNER, "flow-files", FLOW_FILES_REASON),
        new WriterDefinition(PersistentVariableStore.OWNER, PersistentVariableStore.FILE_NAME,
            "Persistent flow variables are owned by the exact resync.flow-variables persistence participant"),
        new WriterDefinition(RegionPersistenceParticipant.OWNER, RegionPersistenceParticipant.DIRECTORY,
            "Flow region clipboard files are owned by the exact resync.flow-regions persistence participant"),
        new WriterDefinition("resync.network", "network", NETWORK_REASON),
        new WriterDefinition("resync.player-dossiers", "player-dossiers", PLAYER_DOSSIERS_REASON),
        new WriterDefinition("resync.structures", "structures", STRUCTURES_REASON),
        new WriterDefinition(ProductionPersistenceOwners.TRIGGERS, "triggers.json", TRIGGERS_REASON),
        new WriterDefinition("resync.world-management", "world-management", WORLD_MANAGEMENT_REASON)
    );

    private ReSyncUncoveredWriterInventory() {
    }

    public static List<PersistenceRootReadiness.UncoveredWriter> forDataRoot(Path dataRoot) {
        return forDataRoot(dataRoot, null);
    }

    public static List<PersistenceRootReadiness.UncoveredWriter> forDataRoot(Path dataRoot,
                                                                                ReSyncNetworkAgentConfig networkConfig) {
        return forDataRoot(dataRoot, networkConfig, false);
    }

    public static List<PersistenceRootReadiness.UncoveredWriter> forDataRoot(Path dataRoot,
                                                                                ReSyncNetworkAgentConfig networkConfig,
                                                                                boolean playerStateEnabled) {
        return forDataRoot(dataRoot, networkConfig, playerStateEnabled, List.of());
    }

    public static List<PersistenceRootReadiness.UncoveredWriter> forDataRoot(Path dataRoot,
                                                                                ReSyncNetworkAgentConfig networkConfig,
                                                                                boolean playerStateEnabled,
                                                                                Collection<Path> npcWorldRoots) {
        Path root = requireRoot(dataRoot);
        return forDataRoot(root, root, networkConfig, playerStateEnabled, npcWorldRoots);
    }

    public static List<PersistenceRootReadiness.UncoveredWriter> forDataRoot(Path dataRoot,
                                                                                Path operatorDataRoot,
                                                                                ReSyncNetworkAgentConfig networkConfig,
                                                                                boolean playerStateEnabled,
                                                                                Collection<Path> npcWorldRoots) {
        Path root = requireRoot(dataRoot);
        Path operatorRoot = requireRoot(operatorDataRoot);
        List<PersistenceRootReadiness.UncoveredWriter> known = DEFINITIONS.stream()
            .filter(definition -> networkConfig == null || !definition.id().equals("resync.network"))
            .map(definition -> PersistenceRootReadiness.UncoveredWriter.local(
                definition.id(), root.resolve(definition.relativePath()), definition.reason()))
            .toList();
        PersistenceRootReadiness.UncoveredWriter luckPermsBackend = PersistenceRootReadiness.UncoveredWriter.externalAffected(
            LUCKPERMS_BACKEND_OWNER, root.resolve(LUCKPERMS_BACKEND_PATH), LUCKPERMS_BACKEND_REASON,
            LUCKPERMS_BACKEND_AUTHORITY);
        List<PersistenceRootReadiness.UncoveredWriter> networkGaps = networkConfig == null
            ? List.of()
            : networkExternalWriters(operatorRoot, networkConfig, playerStateEnabled);
        return Stream.concat(Stream.concat(Stream.concat(Stream.concat(known.stream(), Stream.of(luckPermsBackend)), networkGaps.stream()),
                npcExternalWriters(npcWorldRoots).stream()), unknownRuntimeWriters(root).stream())
            .sorted(Comparator.comparing(PersistenceRootReadiness.UncoveredWriter::id))
            .toList();
    }

    public static List<PersistenceRootReadiness.UncoveredWriter> forDataRoot(Path dataRoot,
                                                                                Path operatorDataRoot,
                                                                                ReSyncNetworkAgentConfig networkConfig,
                                                                                boolean playerStateEnabled) {
        return forDataRoot(dataRoot, operatorDataRoot, networkConfig, playerStateEnabled, List.of());
    }

    public static List<PersistenceExternalInput.Input> externalInputs(Path dataRoot) {
        return PersistenceExternalInput.forDataRoot(requireRoot(dataRoot));
    }

    private static List<PersistenceRootReadiness.UncoveredWriter> networkExternalWriters(Path operatorDataRoot,
                                                                                            ReSyncNetworkAgentConfig config,
                                                                                            boolean playerStateEnabled) {
        if (config == null || !config.enabled()) {
            return List.of();
        }
        Path serverRoot = operatorDataRoot.getParent() == null || operatorDataRoot.getParent().getParent() == null
            ? operatorDataRoot : operatorDataRoot.getParent().getParent();
        List<PersistenceRootReadiness.UncoveredWriter> writers = new ArrayList<>();
        for (ReSyncNetworkAgentConfig.PathPolicy policy : config.pathSyncs()) {
            if (!policy.enabled()) {
                continue;
            }
            for (String entry : policy.entries()) {
                Path external = serverRoot.resolve(entry).toAbsolutePath().normalize();
                writers.add(PersistenceRootReadiness.UncoveredWriter.externalAffected(
                    "resync.network.path." + policy.id() + "." + safePathId(entry),
                    external,
                    "Network path synchronization writes an externally rooted server path without an atomic ReSync restore participant: " + external,
                    "ReSync network path synchronization"));
            }
        }
        Path worldPlayerData = serverRoot.resolve("world").resolve("playerdata").toAbsolutePath().normalize();
        if (playerStateEnabled || Files.isDirectory(worldPlayerData, LinkOption.NOFOLLOW_LINKS)
            || config.pathSyncs().stream().anyMatch(policy -> policy.enabled() && policy.entries().stream().anyMatch(entry -> entry.endsWith("playerdata")))) {
            writers.add(PersistenceRootReadiness.UncoveredWriter.externalAffected(
                "resync.network.world-playerdata", worldPlayerData,
                "Network player-state reconciliation mutates world playerdata outside the ReSync root without an atomic restore participant",
                "ReSync network player-state reconciliation"));
        }
        return List.copyOf(writers);
    }

    private static List<PersistenceRootReadiness.UncoveredWriter> npcExternalWriters(Collection<Path> worldRoots) {
        if (worldRoots == null || worldRoots.isEmpty()) {
            return List.of();
        }
        List<Path> candidates = worldRoots.stream()
            .filter(Objects::nonNull)
            .map(ReSyncUncoveredWriterInventory::requireRoot)
            .sorted(Comparator.comparing(Path::toString))
            .toList();
        LinkedHashSet<Path> disjointWorldRoots = new LinkedHashSet<>();
        for (Path candidate : candidates) {
            if (disjointWorldRoots.stream().anyMatch(existing -> candidate.startsWith(existing))) {
                continue;
            }
            disjointWorldRoots.removeIf(existing -> existing.startsWith(candidate));
            disjointWorldRoots.add(candidate);
        }
        List<PersistenceRootReadiness.UncoveredWriter> writers = new ArrayList<>();
        for (Path worldRoot : disjointWorldRoots) {
            String suffix = safePathId(worldRoot.toString());
            Path entitiesRoot = worldRoot.resolve("entities").toAbsolutePath().normalize();
            Path worldDataRoot = worldRoot.resolve("data").toAbsolutePath().normalize();
            writers.add(PersistenceRootReadiness.UncoveredWriter.externalAffected(
                "resync.external.npc-entities." + suffix,
                entitiesRoot,
                "External affected root " + entitiesRoot + " is written by the Bukkit/Paper world persistence authority; ReSync has no atomic snapshot, restore, or rebind authority for NPC entity chunks",
                "Bukkit/Paper world persistence authority"));
            writers.add(PersistenceRootReadiness.UncoveredWriter.externalAffected(
                "resync.external.npc-world-data." + suffix,
                worldDataRoot,
                "External affected root " + worldDataRoot + " is written by the Bukkit/Paper world persistence authority; ReSync has no atomic snapshot, restore, or rebind authority for NPC PersistentDataContainer values",
                "Bukkit/Paper world persistence authority"));
        }
        return List.copyOf(writers);
    }

    private static String safePathId(String path) {
        String source = path == null ? "entry" : path;
        String normalized = source.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (normalized.isBlank()) {
            return "entry";
        }
        return source.equals(normalized) ? normalized : normalized + "-" + Integer.toUnsignedString(source.hashCode(), 16);
    }

    public static PersistenceRootReadiness.UncoveredWriter playerDossiers(Path dataRoot) {
        return forDataRoot(dataRoot).stream()
            .filter(writer -> writer.id().equals("resync.player-dossiers"))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Player dossier writer inventory is missing"));
    }

    private static Path requireRoot(Path dataRoot) {
        Objects.requireNonNull(dataRoot, "dataRoot");
        return dataRoot.toAbsolutePath().normalize();
    }

    private record WriterDefinition(String id, String relativePath, String reason) {
        private WriterDefinition {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Writer ID Must Not Be Blank");
            }
            if (relativePath == null || relativePath.isBlank() || Path.of(relativePath).isAbsolute()) {
                throw new IllegalArgumentException("Writer Path Must Be Relative: " + id);
            }
            for (Path segment : Path.of(relativePath)) {
                if (segment.toString().equals("..") || segment.toString().isBlank()) {
                    throw new IllegalArgumentException("Writer Path Contains An Unsafe Segment: " + id);
                }
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Writer Reason Must Not Be Blank: " + id);
            }
        }
    }

    private static List<PersistenceRootReadiness.UncoveredWriter> unknownRuntimeWriters(Path dataRoot) {
        Path runtime = dataRoot.resolve("runtime").toAbsolutePath().normalize();
        if (Files.notExists(runtime, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        if (Files.isSymbolicLink(runtime) || !Files.isDirectory(runtime, LinkOption.NOFOLLOW_LINKS)) {
            return List.of(PersistenceRootReadiness.UncoveredWriter.of(
                "resync.runtime.unknown-root", runtime,
                "The runtime persistence root is not a regular non-symbolic-link directory"));
        }
        try (Stream<Path> entries = Files.list(runtime)) {
            return entries
                .filter(entry -> !KNOWN_RUNTIME_ENTRIES.contains(entry.getFileName().toString()))
                .map(entry -> PersistenceRootReadiness.UncoveredWriter.of(
                    unknownId(entry), entry,
                    "Runtime path " + entry.getFileName() + " has no registered persistence participant and is fail-closed"))
                .toList();
        } catch (IOException | SecurityException exception) {
            return List.of(PersistenceRootReadiness.UncoveredWriter.of(
                "resync.runtime.unknown-root", runtime,
                "Runtime persistence paths could not be enumerated: " + exception.getClass().getSimpleName()));
        }
    }

    private static String unknownId(Path entry) {
        String name = entry.getFileName() == null ? "entry" : entry.getFileName().toString();
        String normalized = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (normalized.isBlank()) {
            normalized = "entry";
        }
        if (!normalized.equals(name)) {
            normalized += "-" + Integer.toUnsignedString(name.hashCode(), 16);
        }
        return "resync.runtime.unknown." + normalized;
    }
}
