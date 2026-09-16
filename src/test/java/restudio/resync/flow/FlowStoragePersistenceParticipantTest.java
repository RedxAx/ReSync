package restudio.resync.flow;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.GuiDefinition;
import restudio.flow.data.ScoreboardDefinition;
import restudio.flow.data.TabDefinition;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.resources.AssetFileFormat;
import restudio.resync.server.ConfigurationPersistenceParticipant;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowStoragePersistenceParticipantTest {
    @TempDir
    Path tempDir;
    private final List<AssetTransactionCoordinator> coordinators = new ArrayList<>();

    @AfterEach
    void closeCoordinators() throws Exception {
        for (AssetTransactionCoordinator coordinator : coordinators.reversed()) {
            coordinator.close();
        }
        coordinators.clear();
    }

    @Test
    void quiesceClosesFlowStorageMutationAdmissionAndResumeReopensIt() throws Exception {
        AssetTransactionCoordinator coordinator = coordinator(tempDir.resolve("assets"));
        FlowStorage storage = new FlowStorage(tempDir.toFile(), coordinator);
        FlowStoragePersistenceParticipant participant = new FlowStoragePersistenceParticipant(tempDir, storage, coordinator);
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry(tempDir);
        participants.register(participant);

        storage.saveGraph(graph("before-quiesce"));
        participants.quiesceAll();

        assertThrows(IllegalStateException.class, () -> storage.saveGraph(graph("blocked")));

        participants.resumeAll();
        storage.saveGraph(graph("after-resume"));

        assertEquals("before-quiesce", storage.getGraph("before-quiesce").getId());
        assertEquals("after-resume", storage.getGraph("after-resume").getId());
    }

    @Test
    void invalidReplacementRootFailsClosedAndRestoresThePreviousAssetRoot() throws Exception {
        AssetTransactionCoordinator activeCoordinator = coordinator(tempDir.resolve("assets"));
        FlowStorage storage = new FlowStorage(tempDir.toFile(), activeCoordinator);
        Path replacementRoot = Files.createDirectory(tempDir.resolve("replacement-invalid"));
        Path replacementAssets = replacementRoot.resolve("assets");
        AssetTransactionCoordinator candidateCoordinator = coordinator(replacementAssets);
        Files.writeString(replacementAssets.resolve("broken.json"), "{not-json");
        FlowStoragePersistenceParticipant participant = new FlowStoragePersistenceParticipant(tempDir, storage,
            candidateRoot -> candidateRoot.equals(activeCoordinator.canonicalRoot())
                ? activeCoordinator : candidateCoordinator);
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry(tempDir);
        participants.register(participant);
        Path previousRoot = storage.getAssetsPath();

        participants.quiesceAll();

        assertThrows(MigrationException.class, () -> participants.rebindAll(replacementRoot));

        assertEquals(previousRoot, storage.getAssetsPath());
        assertEquals(restudio.resync.migration.PersistenceRebindStatus.State.ROLLED_BACK,
            participants.rebindStatus().state());
        participants.resumeAll();
        assertNull(storage.getGraph("broken"));
    }

    @Test
    void quiesceWaitsForAnInFlightFlowMutationLease() throws Exception {
        AssetPersistenceGate gate = new AssetPersistenceGate(tempDir);
        AssetTransactionCoordinator coordinator = coordinator(tempDir.resolve("assets"));
        FlowStorage storage = new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.runtime(tempDir), gate,
            coordinator);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        storage.setGraphValidator(graph -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return new restudio.resync.flow.validation.FlowGraphValidationResult(List.of());
        });

        CompletableFuture<Void> save = CompletableFuture.runAsync(() -> storage.saveGraph(graph("in-flight")));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        CompletableFuture<Void> quiesce = CompletableFuture.runAsync(storage::quiescePersistence);
        try {
            quiesce.get(100, TimeUnit.MILLISECONDS);
            throw new AssertionError("Quiesce completed before the mutation released its lease");
        } catch (TimeoutException expected) {
        }
        release.countDown();
        save.get(5, TimeUnit.SECONDS);
        quiesce.get(5, TimeUnit.SECONDS);

        assertThrows(IllegalStateException.class, () -> storage.saveGraph(graph("blocked")));
    }

    @Test
    void flowDefaultsUseTheSharedConfigurationOwnerAndHonorItsFence() throws Exception {
        ConfigurationPersistenceParticipant configuration = new ConfigurationPersistenceParticipant(tempDir);
        AssetTransactionCoordinator coordinator = coordinator(tempDir.resolve("assets"));
        FlowStorage storage = new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.runtime(tempDir),
            new AssetPersistenceGate(tempDir), configuration, coordinator);

        storage.setDefaultScoreboard("main", true);
        assertEquals("main", configuration.loadProperties().getProperty("flow.default-scoreboard.id"));

        configuration.quiesce();

        assertThrows(IllegalStateException.class, () -> storage.setDefaultTab("sidebar", true));
        assertEquals("main", configuration.loadProperties().getProperty("flow.default-scoreboard.id"));
    }

    @Test
    void graphChangeCallbacksRunAfterTheMutationLeaseCloses() throws Exception {
        AssetTransactionCoordinator coordinator = coordinator(tempDir.resolve("assets"));
        FlowStorage storage = new FlowStorage(tempDir.toFile(), coordinator);
        List<String> changed = new ArrayList<>();
        storage.setGraphChangeListener(change -> {
            changed.add(change.type() + ":" + change.id());
            storage.quiescePersistence();
        });

        assertTimeout(Duration.ofSeconds(5), () -> storage.saveGraph(graph("callback-quiesce")));

        assertEquals(List.of("flow:callback-quiesce"), changed);
        assertThrows(IllegalStateException.class, () -> storage.saveGraph(graph("blocked-by-callback")));
        storage.resumePersistence();
        storage.setGraphChangeListener(null);
        storage.saveGraph(graph("after-callback"));
        assertEquals("after-callback", storage.getGraph("after-callback").getId());
    }

    @Test
    void legacyDeleteLeavesTheSourceInPlaceWhenTheCoordinatorRejectsTheTransaction() throws Exception {
        AssetTransactionCoordinator coordinator = coordinator(tempDir.resolve("assets"));
        FlowStorage storage = new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.compatibility(tempDir), coordinator);
        Path legacy = tempDir.resolve("flows").resolve("legacy-delete-failure.json");
        Files.createDirectories(legacy.getParent());
        String legacyJson = "{\"id\":\"legacy-delete-failure\",\"version\":2,\"nodes\":{},\"connections\":[],\"localVariables\":[]}";
        Files.writeString(legacy, legacyJson);
        Files.writeString(tempDir.resolve("assets").resolve("project.json"), "{not-json");

        assertThrows(IllegalStateException.class, () -> storage.deleteGraph("flow", "legacy-delete-failure",
            UUID.randomUUID(), 0L));

        assertEquals(legacyJson, Files.readString(legacy));
    }

    @Test
    void graphDeleteReplayRetriesEvidenceQuarantineAfterTheDurableDeleteCommits() throws Exception {
        AssetTransactionCoordinator coordinator = coordinator(tempDir.resolve("assets"));
        FlowStorage storage = new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.compatibility(tempDir), coordinator);
        storage.saveGraph(graph("legacy-graph"));
        Path legacy = tempDir.resolve("flows").resolve("legacy-graph.json");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "{\"id\":\"legacy-graph\",\"version\":2,\"nodes\":{},\"connections\":[],\"localVariables\":[]}");
        Files.createFile(tempDir.resolve("legacy-quarantine"));
        UUID mutationId = UUID.randomUUID();

        storage.deleteGraph("flow", "legacy-graph", mutationId, 1L);
        assertTrue(Files.isRegularFile(legacy));

        Files.delete(tempDir.resolve("legacy-quarantine"));
        storage.deleteGraph("flow", "legacy-graph", mutationId, 1L);

        assertTrue(Files.notExists(legacy));
    }

    @Test
    void genericLegacyDeleteRetriesEvidenceQuarantineAfterTheDurableDeleteCommits() throws Exception {
        AssetTransactionCoordinator coordinator = coordinator(tempDir.resolve("assets"));
        FlowStorage storage = new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.compatibility(tempDir), coordinator);
        storage.saveGui(new GuiDefinition("legacy-gui", "Original", 3));
        Path legacy = tempDir.resolve("guis").resolve("legacy-gui.json");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "legacy-gui-evidence");
        Files.createFile(tempDir.resolve("legacy-quarantine"));

        storage.deleteGui("legacy-gui");
        assertTrue(Files.isRegularFile(legacy));

        Files.delete(tempDir.resolve("legacy-quarantine"));
        storage.deleteGui("legacy-gui");

        assertTrue(Files.notExists(legacy));
    }

    @Test
    void graphStateRejectsAPhysicalLiveAndTombstoneSiblingPair() throws Exception {
        AssetTransactionCoordinator coordinator = coordinator(tempDir.resolve("assets"));
        FlowStorage storage = new FlowStorage(tempDir.toFile(), coordinator);
        storage.saveGraph(graph("sibling-conflict"));
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("flow", "sibling-conflict");
        Path live = coordinator.read(snapshot -> snapshot.path(key).orElseThrow());
        byte[] original = Files.readAllBytes(live);
        storage.deleteGraph("flow", "sibling-conflict", UUID.randomUUID(), 1L);
        Files.write(live, original);

        assertThrows(IllegalStateException.class, () -> storage.readGraphIdentity("flow", "sibling-conflict"));
    }

    @Test
    void graphStateRejectsCoordinatorTombstoneLineageThatDoesNotMatchTheExpectedSibling() throws Exception {
        AssetTransactionCoordinator coordinator = coordinator(tempDir.resolve("assets"));
        FlowStorage storage = new FlowStorage(tempDir.toFile(), coordinator);
        storage.saveGraph(graph("lineage-conflict"));
        AssetTransactionCoordinator.AssetKey tombstoneKey = new AssetTransactionCoordinator.AssetKey(
            "tombstone:flow", "lineage-conflict");
        AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
        UUID mutationId = UUID.randomUUID();
        Path alternate = tempDir.resolve("assets").resolve(".tombstones").resolve("flow")
            .resolve("lineage-conflict-alternate.json");
        coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(mutationId, snapshot.project(),
            List.of(AssetTransactionCoordinator.AssetDelta.write(tombstoneKey, alternate,
                AssetTransactionCoordinator.Missing.INSTANCE,
                "lineage".getBytes(StandardCharsets.UTF_8))), List.of()));

        assertThrows(IllegalStateException.class, () -> storage.readGraphIdentity("flow", "lineage-conflict"));
    }

    @Test
    void missingGraphRejectsRetainedCoordinatorTombstoneLineage() throws Exception {
        AssetTransactionCoordinator coordinator = coordinator(tempDir.resolve("assets"));
        FlowStorage storage = new FlowStorage(tempDir.toFile(), coordinator);
        AssetTransactionCoordinator.AssetKey tombstoneKey = new AssetTransactionCoordinator.AssetKey(
            "tombstone:flow", "missing-lineage");
        AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
        UUID mutationId = UUID.randomUUID();
        Path alternate = tempDir.resolve("assets").resolve(".tombstones").resolve("flow")
            .resolve("missing-lineage-alternate.json");
        coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(mutationId, snapshot.project(),
            List.of(AssetTransactionCoordinator.AssetDelta.write(tombstoneKey, alternate,
                AssetTransactionCoordinator.Missing.INSTANCE,
                "lineage".getBytes(StandardCharsets.UTF_8))), List.of()));

        assertThrows(IllegalStateException.class, () -> storage.readGraphIdentity("flow", "missing-lineage"));
    }

    @Test
    void postCommitGraphListenerFailureDoesNotFailTheMutationAndSeesThePublishedCache() throws Exception {
        AssetTransactionCoordinator coordinator = coordinator(tempDir.resolve("assets"));
        FlowStorage storage = new FlowStorage(tempDir.toFile(), coordinator);
        AtomicBoolean cachePublished = new AtomicBoolean();
        storage.setGraphChangeListener(id -> {
            cachePublished.set(storage.getGraphCache().keySet().stream()
                .anyMatch(key -> key.endsWith("\nlistener-failure")));
            throw new IllegalStateException("listener failure");
        });

        assertTimeout(Duration.ofSeconds(5), () -> storage.saveGraph(graph("listener-failure")));

        assertTrue(cachePublished.get());
        assertEquals("listener-failure", storage.getGraph("listener-failure").getId());
    }

    @Test
    void guiScoreboardAndTabCacheHitsRejectCoordinatorFileChanges() throws Exception {
        AssetTransactionCoordinator coordinator = coordinator(tempDir.resolve("assets"));
        FlowStorage storage = new FlowStorage(tempDir.toFile(), coordinator);
        storage.saveGui(new GuiDefinition("cache-gui", "Original", 3));
        ScoreboardDefinition scoreboard = new ScoreboardDefinition("cache-scoreboard", "Original");
        scoreboard.setLines(new ArrayList<>(List.of("one")));
        storage.saveScoreboard(scoreboard);
        TabDefinition tab = new TabDefinition("cache-tab");
        tab.setHeader("Original");
        storage.saveTab(tab);

        tamperResource(coordinator, "gui", "cache-gui", "title", "Changed");
        tamperResource(coordinator, "scoreboard", "cache-scoreboard", "title", "Changed");
        tamperResource(coordinator, "tab", "cache-tab", "header", "Changed");

        assertThrows(IllegalStateException.class, () -> storage.getGui("cache-gui"));
        assertThrows(IllegalStateException.class, () -> storage.getScoreboard("cache-scoreboard"));
        assertThrows(IllegalStateException.class, () -> storage.getTab("cache-tab"));
    }

    @Test
    void samePathCoordinatorReplacementInvalidatesTheCachedGuiIdentity() throws Exception {
        AssetTransactionCoordinator coordinator = coordinator(tempDir.resolve("assets"));
        FlowStorage storage = new FlowStorage(tempDir.toFile(), coordinator);
        storage.saveGui(new GuiDefinition("same-path", "Original", 3));
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("gui", "same-path");
        AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
        AssetTransactionCoordinator.Live live = (AssetTransactionCoordinator.Live) snapshot.state(key).orElseThrow();
        Path file = snapshot.path(key).orElseThrow();
        UUID mutationId = UUID.randomUUID();
        String replacement = AssetFileFormat.withResourceIdentity(
            FlowSerializer.serializeGui(new GuiDefinition("same-path", "Replacement", 3)), "gui",
            live.revision() + 1L, mutationId.toString());
        coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(mutationId, snapshot.project(),
            List.of(AssetTransactionCoordinator.AssetDelta.write(key, file, live,
                replacement.getBytes(StandardCharsets.UTF_8))), List.of()));

        assertEquals("Replacement", storage.getGui("same-path").getTitle());
    }

    @Test
    void graphIdentityRejectsAFileThatNoLongerMatchesTheSharedCoordinator() throws Exception {
        AssetTransactionCoordinator coordinator = coordinator(tempDir.resolve("assets"));
        FlowStorage storage = new FlowStorage(tempDir.toFile(), coordinator);
        storage.saveGraph(graph("identity-authority"));
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("flow", "identity-authority");
        Path file = coordinator.read(snapshot -> snapshot.path(key).orElseThrow());
        var value = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        value.addProperty(AssetFileFormat.REVISION, value.get(AssetFileFormat.REVISION).getAsLong() + 1L);
        Files.writeString(file, value.toString());

        assertThrows(IllegalStateException.class, () -> storage.readGraphIdentity("flow", "identity-authority"));
    }

    @Test
    void reloadRetriesWhenValidationPublishesAReplacementGraph() throws Exception {
        AssetTransactionCoordinator coordinator = coordinator(tempDir.resolve("assets"));
        FlowStorage storage = new FlowStorage(tempDir.toFile(), coordinator);
        storage.saveGraph(graph("reload-race"));
        AtomicBoolean replaced = new AtomicBoolean();
        AtomicInteger notifications = new AtomicInteger();
        storage.setGraphChangeListener(id -> notifications.incrementAndGet());
        storage.setGraphValidator(candidate -> {
            if (replaced.compareAndSet(false, true)) {
                storage.setGraphValidator((Function<FlowGraph, restudio.resync.flow.validation.FlowGraphValidationResult>) null);
                storage.saveGraph(graph("reload-race"));
            }
            return new restudio.resync.flow.validation.FlowGraphValidationResult(List.of());
        });

        FlowGraph reloaded = storage.reloadGraph("flow", "reload-race");

        assertTrue(replaced.get());
        assertEquals(2L, reloaded.getResourceRevision());
        assertEquals(2, notifications.get());
    }

    @Test
    void constructorRejectsAnAssetGateScopedOutsideTheFlowDataRoot() throws Exception {
        Path wrongScope = Files.createDirectory(tempDir.resolve("wrong-scope"));
        AssetPersistenceGate gate = new AssetPersistenceGate(wrongScope);
        AssetTransactionCoordinator coordinator = coordinator(tempDir.resolve("assets"));

        assertThrows(IllegalArgumentException.class, () -> new FlowStorage(tempDir.toFile(),
            LegacyRuntimeActivationGate.runtime(tempDir), gate, coordinator));
    }

    @Test
    void coordinatorValidationRejectsAReboundGateOutsideTheActiveAssetRoot() throws Exception {
        AssetPersistenceGate gate = new AssetPersistenceGate(tempDir);
        AssetTransactionCoordinator coordinator = coordinator(tempDir.resolve("assets"));
        FlowStorage storage = new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.runtime(tempDir), gate,
            coordinator);
        Path wrongScope = Files.createDirectory(tempDir.resolve("wrong-validation-scope"));
        storage.quiescePersistence();
        gate.rebind(wrongScope);

        assertThrows(IOException.class, () -> storage.validateActiveCoordinator(coordinator));
    }

    @Test
    void guiScoreboardAndTabSnapshotsDoNotExposeMutableCacheValues() throws Exception {
        AssetTransactionCoordinator coordinator = coordinator(tempDir.resolve("assets"));
        FlowStorage storage = new FlowStorage(tempDir.toFile(), coordinator);

        GuiDefinition gui = new GuiDefinition("menu", "Original", 3);
        ScoreboardDefinition scoreboard = new ScoreboardDefinition("board", "Original");
        scoreboard.setLines(new ArrayList<>(List.of("one")));
        TabDefinition tab = new TabDefinition("tab");
        tab.setHeader("Original");
        storage.saveGui(gui);
        storage.saveScoreboard(scoreboard);
        storage.saveTab(tab);
        gui.setTitle("caller-mutated");
        scoreboard.getLines().add("caller-mutated");
        tab.setHeader("caller-mutated");

        storage.getGui("menu").setTitle("changed");
        storage.getScoreboard("board").getLines().add("changed");
        storage.getTab("tab").setHeader("changed");
        storage.getGuiCache().get("menu").setTitle("cache-changed");
        storage.getScoreboardCache().get("board").getLines().clear();
        storage.getTabCache().get("tab").setHeader("cache-changed");

        assertEquals("Original", storage.getGui("menu").getTitle());
        assertEquals(List.of("one"), storage.getScoreboard("board").getLines());
        assertEquals("Original", storage.getTab("tab").getHeader());
    }

    private AssetTransactionCoordinator coordinator(Path assetsRoot) throws Exception {
        AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(assetsRoot, new Gson());
        coordinators.add(coordinator);
        return coordinator;
    }

    private FlowGraph graph(String id) {
        return FlowSerializer.deserialize("{\"id\":\"" + id + "\",\"version\":2,\"nodes\":{},\"connections\":[],\"localVariables\":[]}");
    }

    private void tamperResource(AssetTransactionCoordinator coordinator, String type, String id,
                                String field, String value) throws Exception {
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey(type, id);
        Path file = coordinator.read(snapshot -> snapshot.path(key).orElseThrow());
        var object = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        object.addProperty(field, value);
        Files.writeString(file, object.toString());
    }
}
