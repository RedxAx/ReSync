package restudio.resync.server.coverage;

import restudio.resync.migration.ManagedFlowFileStoreContract;
import restudio.resync.migration.MigrationReportsPersistenceParticipant;
import restudio.resync.migration.PersistenceCoverageContract;
import restudio.resync.migration.PersistenceExternalInput;
import restudio.resync.migration.PersistenceRootReadiness;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.flow.PersistentVariableStore;
import restudio.resync.server.ReSyncUncoveredWriterInventory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class PersistenceWriterCoverageBoundary {
    private static final Pattern WRITER_ID = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final List<LifecycleStep> REQUIRED_STEPS = List.of(
        LifecycleStep.FLUSH,
        LifecycleStep.QUIESCE,
        LifecycleStep.RESUME,
        LifecycleStep.REBIND,
        LifecycleStep.HEALTH_CHECK
    );
    private static final List<Family> FAMILIES = List.of(
        unresolved(
            "resync.configuration",
            "config.properties",
            List.of("config.properties"),
            List.of("src/main/java/restudio/resync/server/ConfigLoader.java"),
            "ConfigLoader and FlowStorage persist config.properties, and no owner exposes flush, quiesce, resume, root rebind, and health checks for this configuration path"
        ),
        proven(
            "resync.install-identity",
            "server-id",
            List.of(
                "server-id",
                ".resync-install.signal",
                ".resync-install-authority.db",
                ".resync-install-authority.db-wal",
                ".resync-install-authority.db-shm",
                ".resync-install-authority.db-journal"
            ),
            List.of(
                "src/main/java/restudio/resync/server/ServerIdentityStore.java",
                "src/test/java/restudio/resync/server/ServerIdentityPersistenceParticipantTest.java"
            ),
            "restudio.resync.server.ServerIdentityStore",
            "wave60.install-identity-participant-lifecycle"
        ),
        proven(
            "resync.catalog-publication-receipts",
            "catalog-publication-receipts.json",
            List.of("catalog-publication-receipts.json"),
            List.of(
                "ReSyncCore/src/main/java/restudio/resync/flow/cache/CatalogPublicationReceiptStore.java",
                "ReSyncCore/src/test/java/restudio/resync/flow/cache/CatalogPublicationReceiptStoreTest.java",
                "ReSyncCore/src/test/java/restudio/resync/flow/cache/CatalogPublicationReceiptPersistenceParticipantTest.java"
            ),
            "restudio.resync.flow.cache.CatalogPublicationReceiptStore",
            "wave61.catalog-publication-receipt-participant-lifecycle"
        ),
        proven(
            MigrationReportsPersistenceParticipant.OWNER,
            MigrationReportsPersistenceParticipant.DIRECTORY,
            List.of(MigrationReportsPersistenceParticipant.DIRECTORY,
                MigrationReportsPersistenceParticipant.DIRECTORY + "/" + MigrationReportsPersistenceParticipant.RECIPE_REPORT_FILE),
            List.of(
                "src/main/java/restudio/resync/migration/MigrationReportsPersistenceParticipant.java",
                "src/main/java/restudio/resync/customization/ReSyncJsonResourceStorage.java",
                "src/test/java/restudio/resync/migration/MigrationReportsPersistenceParticipantTest.java",
                "src/test/java/restudio/resync/customcontent/CustomBlocksPersistenceParticipantTest.java",
                "src/test/java/restudio/resync/world/WorldAuditPersistenceParticipantTest.java"
            ),
            "restudio.resync.migration.MigrationReportsPersistenceParticipant",
            "migration-reports.participant-lifecycle"
        ),
        unresolved(
            "resync.extensions",
            "extensions",
            List.of("extensions"),
            List.of("src/main/java/restudio/resync/api/ReSyncExtensionManager.java"),
            "ReSyncExtensionManager exposes extensions/<pluginId> storage to extension code but has no coordinated write fence, flush, root rebind, or health-check lifecycle; extension-owned writers cannot be inferred as safe"
        ),
        proven(
            ManagedFlowFileStoreContract.OWNER,
            "flow-files",
            List.of("flow-files", "flow-files/managed-files.db", "flow-files/managed-files.db-wal", "flow-files/managed-files.db-shm", "flow-files/managed-files.db-journal"),
            List.of(
                "src/main/java/restudio/resync/flow/handler/generic/ManagedFlowFilePersistenceParticipant.java",
                "src/main/java/restudio/resync/flow/handler/generic/SqliteManagedFlowFileCapability.java",
                "src/test/java/restudio/resync/flow/handler/generic/ManagedFlowFilePersistenceParticipantTest.java"
            ),
            "restudio.resync.flow.handler.generic.ManagedFlowFilePersistenceParticipant",
            "resync.flow.files.lifecycle"
        ),
        proven(
            PersistentVariableStore.OWNER,
            PersistentVariableStore.FILE_NAME,
            List.of(
                PersistentVariableStore.FILE_NAME,
                PersistentVariableStore.FILE_NAME + ".previous",
                ".quarantine/journals"
            ),
            List.of(
                "src/main/java/restudio/resync/flow/PersistentVariableStore.java",
                "src/main/java/restudio/resync/flow/automation/VariableService.java",
                "src/main/java/restudio/resync/flow/handler/generic/VariableScopeHandler.java",
                "src/main/java/restudio/resync/modules/FlowRuntimeModule.java",
                "src/main/java/restudio/resync/server/ReSyncServer.java",
                "src/test/java/restudio/resync/flow/PersistentVariableStoreTest.java"
            ),
            "restudio.resync.flow.PersistentVariableStore",
            "wave64.flow-variable-participant-lifecycle"
        ),
        proven(
            "resync.jobs.automation-tasks",
            "runtime/automation-tasks.json",
            List.of("runtime/automation-tasks.json", "runtime/automation-tasks.json.previous", "runtime/.quarantine/journals"),
            List.of(
                "src/main/java/restudio/resync/flow/automation/AutomationTaskPersistenceParticipant.java",
                "src/main/java/restudio/resync/flow/automation/AutomationTaskService.java",
                "src/main/java/restudio/resync/flow/automation/AutomationTaskStore.java",
                "src/test/java/restudio/resync/flow/automation/AutomationTaskPersistenceParticipantTest.java"
            ),
            "restudio.resync.flow.automation.AutomationTaskPersistenceParticipant",
            "wave56.automation-task-participant-lifecycle"
        ),
        unresolved(
            "resync.network",
            "network",
            List.of("network"),
            List.of("src/main/java/restudio/resync/network/paper/NetworkResourceManifestStore.java", "src/main/java/restudio/resync/network/paper/state/NetworkPlayerStateCoordinator.java"),
            "Network resource and player-state stores write beneath network without a common coordinated flush, quiesce, resume, root-rebind, and health-check lifecycle"
        ),
        proven(
            "resync.player-dossiers",
            "player-dossiers",
            List.of("player-dossiers"),
            List.of("src/main/java/restudio/resync/player/PlayerTrackingManager.java"),
            "restudio.resync.player.PlayerTrackingPersistenceParticipant",
            "wave54.player-dossier-participant-lifecycle"
        ),
        proven(
            "resync.runtime.luckperms-operations",
            "runtime/luckperms-operations.json",
            List.of("runtime/luckperms-operations.json"),
            List.of(
                "src/main/java/restudio/resync/permissions/LuckPermsManagementService.java",
                "src/main/java/restudio/resync/permissions/LuckPermsOperationPersistenceParticipant.java",
                "src/test/java/restudio/resync/permissions/LuckPermsManagementServiceTest.java",
                "src/test/java/restudio/resync/permissions/LuckPermsOperationPersistenceParticipantTest.java"
            ),
            "restudio.resync.permissions.LuckPermsOperationPersistenceParticipant",
            "wave59.luckperms-operation-participant-lifecycle"
        ),
        proven(
            "resync.runtime.player-npcs",
            "runtime/player-npcs.json",
            List.of("runtime/player-npcs.json"),
            List.of(
                "src/main/java/restudio/resync/runtime/NpcService.java",
                "src/main/java/restudio/resync/runtime/PlayerNpcInstanceStorage.java",
                "src/main/java/restudio/resync/runtime/PlayerNpcPersistenceParticipant.java",
                "src/test/java/restudio/resync/runtime/PlayerNpcInstanceStorageTest.java",
                "src/test/java/restudio/resync/runtime/NpcServiceTest.java"
            ),
            "restudio.resync.runtime.PlayerNpcPersistenceParticipant",
            "wave59.player-npc-participant-lifecycle"
        ),
        proven(
            "resync.runtime.resource-mutations",
            "runtime/resource-mutations.db",
            List.of("runtime/resource-mutations.db", "runtime/resource-mutations.db-wal", "runtime/resource-mutations.db-shm"),
            List.of(
                "src/main/java/restudio/resync/server/SqliteProtocolResourceMutationAuthority.java",
                "src/main/java/restudio/resync/server/SqliteProtocolResourceMutationPersistenceParticipant.java",
                "src/test/java/restudio/resync/server/SqliteProtocolResourceMutationPersistenceParticipantTest.java"
            ),
            "restudio.resync.server.SqliteProtocolResourceMutationPersistenceParticipant",
            "wave58.sqlite-resource-participant-lifecycle"
        ),
        unresolved(
            "resync.runtime.npc-entities",
            "runtime/npc-entities",
            List.of("runtime/npc-entities"),
            List.of("src/main/java/restudio/resync/runtime/NpcService.java"),
            "Bukkit NPC entity chunks and world persistent-data storage are external to the ReSync root and have no atomic restore participant"
        ),
        unresolved(
            "resync.runtime.luckperms-backend",
            "runtime/luckperms-backend",
            List.of("runtime/luckperms-backend"),
            List.of("src/main/java/restudio/resync/permissions/LuckPermsManagementService.java"),
            "The external LuckPerms backend is not owned by ReSync and has no coordinated snapshot, quiesce, or atomic rebind participant"
        ),
        proven(
            "resync.structures",
            "structures",
            List.of("structures"),
            List.of("src/main/java/restudio/resync/structure/StructureLibrary.java"),
            "restudio.resync.structure.StructurePersistenceParticipant",
            "wave54.structure-participant-lifecycle"
        ),
        proven(
            "resync.custom-blocks",
            "custom-blocks.json",
            List.of("custom-blocks.json"),
            List.of(
                "src/main/java/restudio/resync/customcontent/VanillaContentProvider.java",
                "src/main/java/restudio/resync/customcontent/CustomBlocksPersistenceParticipant.java",
                "src/test/java/restudio/resync/customcontent/CustomBlocksPersistenceParticipantTest.java"
            ),
            "restudio.resync.customcontent.CustomBlocksPersistenceParticipant",
            "wave62.custom-blocks-participant-lifecycle"
        ),
        proven(
            ProductionPersistenceOwners.TRIGGERS,
            "triggers.json",
            List.of("triggers.json"),
            List.of("src/main/java/restudio/resync/flow/triggers/TriggerRegistry.java"),
            "restudio.resync.flow.triggers.TriggerPersistenceParticipant",
            "wave54.trigger-file-participant-lifecycle"
        ),
        proven(
            "resync.world-management",
            "world-management",
            List.of("world-management"),
            List.of(
                "src/main/java/restudio/resync/world/WorldManagementPersistenceParticipant.java",
                "src/main/java/restudio/resync/world/WorldManagementManager.java",
                "src/main/java/restudio/resync/world/WorldStateStorage.java"
            ),
            "restudio.resync.world.WorldManagementPersistenceParticipant",
            "wave22.world-management-participant-lifecycle"
        ),
        proven(
            "resync.world-audit",
            "world-audit.json",
            List.of("world-audit.json"),
            List.of(
                "src/main/java/restudio/resync/world/WorldOperationSafetyService.java",
                "src/main/java/restudio/resync/world/WorldAuditPersistenceParticipant.java",
                "src/test/java/restudio/resync/world/WorldAuditPersistenceParticipantTest.java"
            ),
            "restudio.resync.world.WorldAuditPersistenceParticipant",
            "wave62.world-audit-participant-lifecycle"
        )
    );
    private static final List<EphemeralFamily> EPHEMERAL_FAMILIES = List.of(
        new EphemeralFamily(
            "resync.jobs.ephemeral",
            List.of(
                "src/main/java/restudio/resync/flow/jobs/FlowJobRegistry.java",
                "src/main/java/restudio/resync/jobs/JobManager.java",
                "src/main/java/restudio/resync/modules/FlowJobModule.java",
                "ReSyncCore/src/main/java/restudio/resync/migration/EphemeralLifecycleParticipant.java",
                "ReSyncCore/src/main/java/restudio/resync/migration/ReSyncPersistenceCoordinator.java"
            ),
            "wave63.generic-jobs-ephemeral-lifecycle"
        )
    );

    private PersistenceWriterCoverageBoundary() {
    }

    public static Boundary current() {
        List<Family> families = sortedFamilies(FAMILIES);
        return new Boundary(families, lifecycleCoverage(families), EPHEMERAL_FAMILIES);
    }

    private static Family unresolved(String writerId, String inventoryRelativePath, List<String> observedRelativePaths,
                                     List<String> sourceEvidence, String reason) {
        return new Family(writerId, inventoryRelativePath, observedRelativePaths, sourceEvidence,
            LifecycleCoverage.UNRESOLVED, "", List.of(), "", reason);
    }

    private static Family proven(String writerId, String inventoryRelativePath, List<String> observedRelativePaths,
                                 List<String> sourceEvidence, String participantType, String evidenceId) {
        return new Family(writerId, inventoryRelativePath, observedRelativePaths, sourceEvidence,
            LifecycleCoverage.PROVEN, participantType, REQUIRED_STEPS, evidenceId, "");
    }

    private static PersistenceCoverageContract.Report lifecycleCoverage(List<Family> families) {
        return new PersistenceCoverageContract.Report(families.stream()
            .map(family -> new PersistenceCoverageContract.Row(
                new PersistenceCoverageContract.Requirement(family.writerId(), PersistenceCoverageContract.Proof.PARTICIPANT_LIFECYCLE),
                family.lifecycleCoverage() == LifecycleCoverage.PROVEN
                    ? PersistenceCoverageContract.State.PROVEN
                    : PersistenceCoverageContract.State.MISSING,
                family.evidenceId().isBlank() ? List.of() : List.of(family.evidenceId()),
                family.reason()))
            .toList());
    }

    public enum LifecycleCoverage {
        PROVEN,
        UNRESOLVED
    }

    public enum LifecycleStep {
        FLUSH,
        QUIESCE,
        RESUME,
        REBIND,
        HEALTH_CHECK
    }

    public record Family(
        String writerId,
        String inventoryRelativePath,
        List<String> observedRelativePaths,
        List<String> sourceEvidence,
        LifecycleCoverage lifecycleCoverage,
        String participantType,
        List<LifecycleStep> verifiedSteps,
        String evidenceId,
        String reason
    ) {
        public Family {
            writerId = requireWriterId(writerId);
            inventoryRelativePath = requireRelativePath(inventoryRelativePath, "inventoryRelativePath");
            observedRelativePaths = normalizedPaths(observedRelativePaths, "observedRelativePaths");
            sourceEvidence = normalizedText(sourceEvidence, "sourceEvidence");
            lifecycleCoverage = Objects.requireNonNull(lifecycleCoverage, "lifecycleCoverage");
            participantType = trim(participantType);
            verifiedSteps = List.copyOf(verifiedSteps == null ? List.of() : verifiedSteps);
            evidenceId = trim(evidenceId);
            reason = trim(reason);
            if (lifecycleCoverage == LifecycleCoverage.PROVEN) {
                if (participantType.isBlank() || evidenceId.isBlank() || !reason.isBlank() || !verifiedSteps.equals(REQUIRED_STEPS)) {
                    throw new IllegalArgumentException("Proven Writer Lifecycle Evidence Is Incomplete: " + writerId);
                }
            } else if (!participantType.isBlank() || !verifiedSteps.isEmpty() || !evidenceId.isBlank() || reason.isBlank()) {
                throw new IllegalArgumentException("Unresolved Writer Lifecycle Boundary Is Incomplete: " + writerId);
            }
        }
    }

    public record EphemeralFamily(String owner, List<String> sourceEvidence, String evidenceId) {
        public EphemeralFamily {
            owner = requireWriterId(owner);
            sourceEvidence = normalizedText(sourceEvidence, "sourceEvidence");
            evidenceId = trim(evidenceId);
            if (evidenceId.isBlank()) {
                throw new IllegalArgumentException("Ephemeral Lifecycle Evidence ID Must Not Be Blank: " + owner);
            }
        }
    }

    public record Boundary(List<Family> families, PersistenceCoverageContract.Report lifecycleCoverage,
                           List<EphemeralFamily> ephemeralFamilies) {
        public Boundary(List<Family> families, PersistenceCoverageContract.Report lifecycleCoverage) {
            this(families, lifecycleCoverage, List.of());
        }

        public Boundary {
            families = sortedFamilies(families);
            lifecycleCoverage = Objects.requireNonNull(lifecycleCoverage, "lifecycleCoverage");
            ephemeralFamilies = List.copyOf(ephemeralFamilies == null ? List.of() : ephemeralFamilies);
            if (families.size() != lifecycleCoverage.rows().size()) {
                throw new IllegalArgumentException("Writer Families And Lifecycle Coverage Rows Must Match");
            }
            List<String> familyIds = families.stream().map(Family::writerId).toList();
            List<String> coverageIds = lifecycleCoverage.rows().stream().map(row -> row.requirement().writerId()).toList();
            if (!familyIds.equals(coverageIds)) {
                throw new IllegalArgumentException("Writer Families And Lifecycle Coverage IDs Must Match");
            }
        }

        public boolean complete() {
            return lifecycleCoverage.complete();
        }

        public List<Family> unresolvedFamilies() {
            return families.stream().filter(family -> family.lifecycleCoverage() == LifecycleCoverage.UNRESOLVED).toList();
        }

        public List<EphemeralFamily> provenEphemeralFamilies() {
            return ephemeralFamilies;
        }

        public List<PersistenceRootReadiness.UncoveredWriter> uncoveredWriters(Path dataRoot) {
            Path root = requireRoot(dataRoot);
            List<PersistenceRootReadiness.UncoveredWriter> declared = unresolvedFamilies().stream()
                .map(family -> PersistenceRootReadiness.UncoveredWriter.of(
                    family.writerId(), root.resolve(family.inventoryRelativePath()), family.reason()))
                .toList();
            List<PersistenceRootReadiness.UncoveredWriter> unknown = ReSyncUncoveredWriterInventory.forDataRoot(root).stream()
                .filter(writer -> writer.id().startsWith("resync.runtime.unknown."))
                .toList();
            return Stream.concat(declared.stream(), unknown.stream())
                .sorted(Comparator.comparing(PersistenceRootReadiness.UncoveredWriter::id))
                .toList();
        }

        public List<PersistenceExternalInput.Input> externalInputs(Path dataRoot) {
            return PersistenceExternalInput.forDataRoot(requireRoot(dataRoot));
        }

        public Map<String, Object> payload(Path dataRoot) {
            Path root = requireRoot(dataRoot);
            List<Map<String, Object>> entries = families.stream().map(family -> familyPayload(root, family)).toList();
            List<String> uncoveredWriterIds = uncoveredWriters(root).stream()
                .map(PersistenceRootReadiness.UncoveredWriter::id).toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("schemaVersion", 1);
            payload.put("kind", "persistence-writer-coverage-boundary");
            payload.put("familyCount", families.size());
            payload.put("ephemeralLifecycleFamilies", ephemeralFamilies.stream().map(EphemeralFamily::owner).toList());
            payload.put("provenLifecycleFamilies", families.stream()
                .filter(family -> family.lifecycleCoverage() == LifecycleCoverage.PROVEN)
                .map(Family::writerId).toList());
            payload.put("uncoveredWriterIds", uncoveredWriterIds);
            payload.put("externalInputs", externalInputs(root).stream().map(input -> Map.<String, Object>of(
                "id", input.id(),
                "path", input.path().toString(),
                "kind", input.kind().name(),
                "readOnly", input.readOnly(),
                "excludedFromPersistence", input.excludedFromPersistence(),
                "reason", input.reason())).toList());
            payload.put("complete", complete());
            payload.put("families", entries);
            return Map.copyOf(payload);
        }

        private static Map<String, Object> familyPayload(Path root, Family family) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("writerId", family.writerId());
            payload.put("inventoryRelativePath", family.inventoryRelativePath());
            payload.put("inventoryPath", root.resolve(family.inventoryRelativePath()).toString());
            payload.put("observedRelativePaths", family.observedRelativePaths());
            payload.put("sourceEvidence", family.sourceEvidence());
            payload.put("lifecycleCoverage", family.lifecycleCoverage().name());
            payload.put("participantType", family.participantType());
            payload.put("verifiedSteps", family.verifiedSteps().stream().map(Enum::name).toList());
            payload.put("evidenceId", family.evidenceId());
            payload.put("reason", family.reason());
            return Map.copyOf(payload);
        }
    }

    private static List<Family> sortedFamilies(List<Family> families) {
        Map<String, Family> unique = new LinkedHashMap<>();
        for (Family family : families == null ? List.<Family>of() : families) {
            Family value = Objects.requireNonNull(family, "family");
            if (unique.putIfAbsent(value.writerId(), value) != null) {
                throw new IllegalArgumentException("Writer Family Is Ambiguous: " + value.writerId());
            }
        }
        return unique.values().stream().sorted(Comparator.comparing(Family::writerId)).toList();
    }

    private static List<String> normalizedPaths(List<String> values, String name) {
        List<String> paths = new ArrayList<>();
        for (String value : values == null ? List.<String>of() : values) {
            paths.add(requireRelativePath(value, name));
        }
        return List.copyOf(paths);
    }

    private static List<String> normalizedText(List<String> values, String name) {
        List<String> text = new ArrayList<>();
        for (String value : values == null ? List.<String>of() : values) {
            String normalized = trim(value);
            if (normalized.isBlank()) {
                throw new IllegalArgumentException(name + " Must Not Contain Blank Values");
            }
            text.add(normalized);
        }
        if (text.isEmpty()) {
            throw new IllegalArgumentException(name + " Must Not Be Empty");
        }
        return List.copyOf(text);
    }

    private static String requireWriterId(String value) {
        String id = trim(value);
        if (!WRITER_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Writer ID Must Be A Stable Lowercase Identifier");
        }
        return id;
    }

    private static String requireRelativePath(String value, String name) {
        String path = trim(value);
        Path parsed = path.isBlank() ? null : Path.of(path);
        if (parsed == null || parsed.isAbsolute() || parsed.getNameCount() == 0) {
            throw new IllegalArgumentException(name + " Must Be A Relative Path");
        }
        for (Path segment : parsed) {
            if (segment.toString().equals("..")) {
                throw new IllegalArgumentException(name + " Must Not Escape Its Root");
            }
        }
        return path;
    }

    private static Path requireRoot(Path dataRoot) {
        return Objects.requireNonNull(dataRoot, "dataRoot").toAbsolutePath().normalize();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
