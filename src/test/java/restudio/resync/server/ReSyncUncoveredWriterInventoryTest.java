package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.PersistentVariableStore;
import restudio.resync.flow.automation.AutomationTaskPersistenceParticipant;
import restudio.resync.flow.runtime.DurableRuntimeReceiptStore;
import restudio.resync.flow.handler.generic.RegionPersistenceParticipant;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.PersistenceRootReadiness;
import restudio.resync.network.paper.ReSyncNetworkAgentConfig;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncUncoveredWriterInventoryTest {
    @TempDir
    Path temporary;

    @Test
    void exposesPlayerDossiersAsAnExplicitUncoveredWriter() {
        Path dataRoot = temporary.resolve("resync");

        PersistenceRootReadiness.UncoveredWriter writer = ReSyncUncoveredWriterInventory.playerDossiers(dataRoot);

        assertEquals("resync.player-dossiers", writer.id());
        assertEquals(dataRoot.toAbsolutePath().normalize().resolve("player-dossiers"), writer.root());
        assertEquals("PlayerTrackingManager writes player dossiers atomically but has no flush, quiesce, resume, restore rebind, or health-check lifecycle",
            writer.reason());
    }

    @Test
    void inventoryIsStableAndCoversEveryKnownDataRootWriter() {
        Path dataRoot = temporary.resolve("resync");
        List<PersistenceRootReadiness.UncoveredWriter> first = ReSyncUncoveredWriterInventory.forDataRoot(dataRoot);
        List<PersistenceRootReadiness.UncoveredWriter> second = ReSyncUncoveredWriterInventory.forDataRoot(dataRoot);

        assertEquals(first, second);
        assertEquals(List.of(
            "resync.catalog-publication-receipts",
            "resync.configuration",
            "resync.extensions",
            "resync.flow-regions",
            "resync.flow-variables",
            "resync.flow.files",
            "resync.install-identity",
            "resync.migration-reports",
            "resync.network",
            "resync.player-dossiers",
            "resync.runtime.luckperms-backend",
            "resync.structures",
            "resync.triggers",
            "resync.world-management"), first.stream().map(PersistenceRootReadiness.UncoveredWriter::id).toList());
        assertTrue(first.stream().allMatch(writer -> writer.root().startsWith(dataRoot.toAbsolutePath().normalize())));
    }

    @Test
    void flowRegionsInventoryUsesTheRegisteredParticipantOwnerAndRoot() {
        Path dataRoot = temporary.resolve("resync");

        PersistenceRootReadiness.UncoveredWriter writer = ReSyncUncoveredWriterInventory.forDataRoot(dataRoot).stream()
            .filter(candidate -> candidate.id().equals(RegionPersistenceParticipant.OWNER))
            .findFirst()
            .orElseThrow();

        assertEquals(RegionPersistenceParticipant.OWNER, writer.id());
        assertEquals(dataRoot.toAbsolutePath().normalize().resolve(RegionPersistenceParticipant.DIRECTORY), writer.root());
    }

    @Test
    void flowVariablesInventoryUsesTheExactRootFileOwner() {
        Path dataRoot = temporary.resolve("resync");

        PersistenceRootReadiness.UncoveredWriter writer = ReSyncUncoveredWriterInventory.forDataRoot(dataRoot).stream()
            .filter(candidate -> candidate.id().equals(PersistentVariableStore.OWNER))
            .findFirst()
            .orElseThrow();

        assertEquals(PersistentVariableStore.OWNER, writer.id());
        assertEquals(dataRoot.toAbsolutePath().normalize().resolve(PersistentVariableStore.FILE_NAME), writer.root());
        assertTrue(writer.reason().contains("exact resync.flow-variables persistence participant"));
    }

    @Test
    void unsupportedFamiliesCarrySourceSpecificFailClosedReasons() {
        Path dataRoot = temporary.resolve("resync");

        List<PersistenceRootReadiness.UncoveredWriter> writers = ReSyncUncoveredWriterInventory.forDataRoot(dataRoot);

        assertTrue(writers.stream().filter(writer -> writer.id().equals("resync.configuration"))
            .findFirst().orElseThrow().reason().contains("FlowStorage persist config.properties"));
        assertEquals(dataRoot.toAbsolutePath().normalize().resolve("config.properties"), writers.stream()
            .filter(writer -> writer.id().equals("resync.configuration"))
            .findFirst().orElseThrow().root());
        assertTrue(writers.stream().noneMatch(writer -> writer.id().equals("resync.jobs")));
        assertTrue(writers.stream().filter(writer -> writer.id().equals("resync.network"))
            .findFirst().orElseThrow().reason().contains("NetworkResourceManifestStore"));
        String networkReason = writers.stream().filter(writer -> writer.id().equals("resync.network"))
            .findFirst().orElseThrow().reason();
        assertTrue(networkReason.contains("NetworkSnapshotOutbox"));
        assertTrue(networkReason.contains("NetworkPlayerStateReconciler"));
        assertTrue(networkReason.contains("network/node.credential"));
        assertTrue(networkReason.contains("plugins/LuckPerms"));
        assertTrue(networkReason.contains("one coordinated flush, quiesce, resume, root rebind, and health-check lifecycle"));
        assertTrue(writers.stream().noneMatch(writer -> writer.id().equals("resync.runtime.npc-entities")));
        assertTrue(writers.stream().filter(writer -> writer.id().equals("resync.runtime.luckperms-backend"))
            .findFirst().orElseThrow().reason().contains("external LuckPerms"));
        assertTrue(writers.stream().filter(writer -> writer.id().equals("resync.triggers"))
            .findFirst().orElseThrow().reason().contains("root-level triggers.json"));
        assertTrue(writers.stream().filter(writer -> writer.id().equals("resync.flow.files"))
            .findFirst().orElseThrow().reason().contains("manifest-backed offline migration"));
    }

    @Test
    void failClosedReadinessRetainsPlayerDossierGap() {
        Path dataRoot = temporary.resolve("resync");

        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.failClosed(
            dataRoot,
            "Persistence topology registration is pending",
            ReSyncUncoveredWriterInventory.forDataRoot(dataRoot));

        assertFalse(registration.sealed());
        assertEquals(List.of("resync.player-dossiers"), registration.readiness().uncoveredWriterIds().stream()
            .filter(id -> id.equals("resync.player-dossiers")).toList());
        assertFalse(registration.readiness().writerInventoryComplete());
        assertThrows(MigrationException.class, registration.readiness()::requireComplete);
    }

    @Test
    void unknownRuntimeFilesAreExplicitFailClosedWriters() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync"));
        Path runtime = Files.createDirectory(dataRoot.resolve("runtime"));
        Files.writeString(runtime.resolve("unregistered-state.json"), "{}");

        PersistenceRootReadiness.UncoveredWriter writer = ReSyncUncoveredWriterInventory.forDataRoot(dataRoot).stream()
            .filter(candidate -> candidate.id().equals("resync.runtime.unknown.unregistered-state.json"))
            .findFirst().orElseThrow();

        assertEquals(runtime.resolve("unregistered-state.json").toAbsolutePath().normalize(), writer.root());
        assertTrue(writer.reason().contains("no registered persistence participant"));
    }

    @Test
    void runtimeReceiptFileIsKnownAndNotReportedAsAnUnknownWriter() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-runtime-receipts"));
        Path runtime = Files.createDirectory(dataRoot.resolve("runtime"));
        Files.writeString(runtime.resolve(DurableRuntimeReceiptStore.FILE_NAME), "{}");

        assertTrue(ReSyncUncoveredWriterInventory.forDataRoot(dataRoot).stream()
            .noneMatch(writer -> writer.id().equals("resync.runtime.unknown." + DurableRuntimeReceiptStore.FILE_NAME)));
    }

    @Test
    void automationRecoveryJournalIsKnownAndNotReportedAsAnUnknownWriter() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-automation-recovery"));
        Path runtime = Files.createDirectory(dataRoot.resolve("runtime"));
        String recoveryFile = AutomationTaskPersistenceParticipant.FILE_NAME + ".previous";
        Files.writeString(runtime.resolve(recoveryFile), "[]");

        assertTrue(ReSyncUncoveredWriterInventory.forDataRoot(dataRoot).stream()
            .noneMatch(writer -> writer.id().equals("resync.runtime.unknown." + recoveryFile)));
    }

    @Test
    void staleSyntheticNpcRuntimePathsRemainFailClosedAsUnknownWriters() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-known-runtime"));
        Path runtime = Files.createDirectory(dataRoot.resolve("runtime"));
        Files.createDirectory(runtime.resolve("npc-entities"));
        Files.createDirectory(runtime.resolve("luckperms-backend"));

        List<String> ids = ReSyncUncoveredWriterInventory.forDataRoot(dataRoot).stream()
            .map(PersistenceRootReadiness.UncoveredWriter::id)
            .toList();

        assertEquals(0, ids.stream().filter("resync.runtime.npc-entities"::equals).count());
        assertEquals(1, ids.stream().filter("resync.runtime.luckperms-backend"::equals).count());
        assertEquals(1, ids.stream().filter("resync.runtime.unknown.npc-entities"::equals).count());
        assertTrue(ids.stream().noneMatch(id -> id.startsWith("resync.runtime.unknown.luckperms-backend")));
    }

    @Test
    void npcInventoryReportsExternalWorldRootsAndAuthorityWithoutSyntheticRuntimePaths() throws Exception {
        Path dataRoot = temporary.resolve("resync-external-npc");
        Path worldRoot = temporary.resolve("server").resolve("world").toAbsolutePath().normalize();

        List<PersistenceRootReadiness.UncoveredWriter> writers = ReSyncUncoveredWriterInventory.forDataRoot(
            dataRoot, null, false, List.of(worldRoot));

        PersistenceRootReadiness.UncoveredWriter entities = writers.stream()
            .filter(writer -> writer.root().equals(worldRoot.resolve("entities")))
            .findFirst().orElseThrow();
        PersistenceRootReadiness.UncoveredWriter worldData = writers.stream()
            .filter(writer -> writer.root().equals(worldRoot.resolve("data")))
            .findFirst().orElseThrow();
        assertTrue(entities.id().startsWith("resync.external.npc-entities."));
        assertTrue(worldData.id().startsWith("resync.external.npc-world-data."));
        assertTrue(entities.reason().contains("Bukkit/Paper world persistence authority"));
        assertTrue(worldData.reason().contains("no atomic snapshot, restore, or rebind authority"));
        assertTrue(writers.stream().noneMatch(writer -> writer.id().equals("resync.runtime.npc-entities")));
        assertTrue(entities.root().startsWith(worldRoot));
        assertTrue(worldData.root().startsWith(worldRoot));
        assertEquals(2, writers.stream().filter(writer -> writer.id().startsWith("resync.external.npc-"))
            .map(PersistenceRootReadiness.UncoveredWriter::root).distinct().count());
    }

    @Test
    void enabledNetworkInventorySeparatesInternalStateFromExternalWriters() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-network"));
        Path serverRoot = dataRoot.getParent().getParent();
        Path worldPlayerData = Files.createDirectories(serverRoot.resolve("world").resolve("playerdata"));
        ReSyncNetworkAgentConfig config = new ReSyncNetworkAgentConfig(
            true,
            ReSyncNetworkAgentConfig.ChatPolicy.disabled(),
            ReSyncNetworkAgentConfig.ResourcePolicy.disabled(),
            List.of(new ReSyncNetworkAgentConfig.PathPolicy(
                "settings", "Settings", true, Set.of("plugins/LuckPerms"),
                ReSyncNetworkAgentConfig.ResourceConflictPolicy.NETWORK_WINS, List.of())),
            "network", "node", "Backend", "ws://127.0.0.1:25500", "enrollment", "", 0,
            1_048_576, 500_000, 100, 100,
            ReSyncNetworkAgentConfig.Tls.disabled(), dataRoot.resolve("network/node.credential"));

        List<PersistenceRootReadiness.UncoveredWriter> writers = ReSyncUncoveredWriterInventory.forDataRoot(dataRoot, config, true);

        assertTrue(Files.isDirectory(worldPlayerData));
        assertTrue(writers.stream().noneMatch(writer -> writer.id().equals("resync.network")));
        assertTrue(writers.stream().anyMatch(writer -> writer.id().startsWith("resync.network.path.settings.")));
        assertTrue(writers.stream().anyMatch(writer -> writer.id().equals("resync.network.world-playerdata")));
        assertTrue(writers.stream().allMatch(writer -> !writer.id().startsWith("resync.network")
            || !writer.root().startsWith(dataRoot.toAbsolutePath().normalize())));
    }

    @Test
    void enabledNetworkInventoryUsesOperatorServerDirectoryWhenActiveRootIsDetached() throws Exception {
        Path serverRoot = Files.createDirectories(temporary.resolve("server"));
        Path operatorRoot = Files.createDirectories(serverRoot.resolve("plugins").resolve("ReSync"));
        Path activeRoot = Files.createDirectory(temporary.resolve("active-resync"));
        ReSyncNetworkAgentConfig config = new ReSyncNetworkAgentConfig(
            true,
            ReSyncNetworkAgentConfig.ChatPolicy.disabled(),
            ReSyncNetworkAgentConfig.ResourcePolicy.disabled(),
            List.of(new ReSyncNetworkAgentConfig.PathPolicy(
                "settings", "Settings", true, Set.of("plugins/LuckPerms"),
                ReSyncNetworkAgentConfig.ResourceConflictPolicy.NETWORK_WINS, List.of())),
            "network", "node", "Backend", "ws://127.0.0.1:25500", "enrollment", "", 0,
            1_048_576, 500_000, 100, 100,
            ReSyncNetworkAgentConfig.Tls.disabled(), activeRoot.resolve("network/node.credential"));

        List<PersistenceRootReadiness.UncoveredWriter> writers = ReSyncUncoveredWriterInventory.forDataRoot(
            activeRoot, operatorRoot, config, false, List.of());

        PersistenceRootReadiness.UncoveredWriter pathWriter = writers.stream()
            .filter(writer -> writer.id().startsWith("resync.network.path.settings.plugins_LuckPerms-"))
            .findFirst().orElseThrow();
        assertEquals(serverRoot.resolve("plugins/LuckPerms").toAbsolutePath().normalize(), pathWriter.root());
        assertTrue(pathWriter.root().startsWith(serverRoot));
        assertFalse(pathWriter.root().startsWith(activeRoot.toAbsolutePath().normalize()));
    }
}
