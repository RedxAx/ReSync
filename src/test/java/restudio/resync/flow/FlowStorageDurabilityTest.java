package restudio.resync.flow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.resources.AssetFileFormat;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowStorageDurabilityTest {
    @TempDir
    Path tempDir;
    private AssetTransactionCoordinator coordinator;

    @AfterEach
    void closeCoordinator() {
        releaseCoordinator();
    }

    private void releaseCoordinator() {
        if (coordinator != null) {
            try {
                coordinator.close();
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to close the test asset coordinator", exception);
            }
            coordinator = null;
        }
    }

    @Test
    void graphSaveCarriesVerifiedRevisionAndTransactionJournal() throws Exception {
        FlowStorage storage = newStorage();
        FlowGraph graph = FlowSerializer.deserialize("""
            {"id":"durable","version":2,"nodes":{},"connections":[],"localVariables":[]}
            """);

        storage.saveGraph(graph);

        Path asset = activeAsset("durable.json");
        assertEquals(1L, graph.getResourceRevision());
        assertEquals("flow", graph.getResourceType());
        assertTrue(AssetFileFormat.verify(asset));
        assertEquals(graph.getResourceHash(), AssetFileFormat.readContentHash(asset));
        try (var paths = Files.walk(tempDir.resolve("assets").resolve(".transactions"))) {
            assertTrue(paths.filter(path -> path.getFileName().toString().equals("journal.json"))
                .map(this::read)
                .anyMatch(json -> json.contains("\"state\":\"COMMITTED\"")));
        }
    }

    @Test
    void staleGraphCannotOverwriteNewerRevision() {
        FlowStorage storage = newStorage();
        FlowGraph graph = FlowSerializer.deserialize("""
            {"id":"shared","version":2,"nodes":{},"connections":[],"localVariables":[]}
            """);
        storage.saveGraph(graph);
        FlowGraph firstEditor = FlowSerializer.deserialize(FlowSerializer.serialize(graph));
        FlowGraph staleEditor = FlowSerializer.deserialize(FlowSerializer.serialize(graph));

        firstEditor.setEnabled(false);
        storage.saveGraph(firstEditor);
        staleEditor.setVersion(3);

        assertThrows(ResourceRevisionConflictException.class, () -> storage.saveGraph(staleEditor));
        assertEquals(2L, storage.getGraph("shared").getResourceRevision());
    }

    @Test
    void staleEquivalentGraphConvergesToCurrentRevision() {
        FlowStorage storage = newStorage();
        FlowGraph graph = FlowSerializer.deserialize("""
            {"id":"shared","version":2,"nodes":{},"connections":[],"localVariables":[]}
            """);
        storage.saveGraph(graph);
        FlowGraph firstEditor = FlowSerializer.deserialize(FlowSerializer.serialize(graph));
        FlowGraph staleEditor = FlowSerializer.deserialize(FlowSerializer.serialize(graph));

        firstEditor.setEnabled(false);
        staleEditor.setEnabled(false);
        storage.saveGraph(firstEditor);
        storage.saveGraph(staleEditor);

        assertEquals(2L, staleEditor.getResourceRevision());
        assertEquals(firstEditor.getResourceMutationId(), staleEditor.getResourceMutationId());
        assertEquals(firstEditor.getResourceHash(), staleEditor.getResourceHash());
    }

    @Test
    void unchangedGraphDoesNotAdvanceRevision() {
        FlowStorage storage = newStorage();
        FlowGraph graph = FlowSerializer.deserialize("""
            {"id":"shared","version":2,"nodes":{},"connections":[],"localVariables":[]}
            """);
        storage.saveGraph(graph);
        String mutationId = graph.getResourceMutationId();
        String hash = graph.getResourceHash();

        storage.saveGraph(FlowSerializer.deserialize(FlowSerializer.serialize(graph)));

        FlowGraph stored = storage.getGraph("shared");
        assertEquals(1L, stored.getResourceRevision());
        assertEquals(mutationId, stored.getResourceMutationId());
        assertEquals(hash, stored.getResourceHash());
    }

    @Test
    void functionSaveUndoSequenceUsesTheCurrentRevision() {
        FlowStorage storage = newStorage();
        FlowGraph original = FlowSerializer.deserialize("""
            {"id":"request_message","version":2,"function":true,"resourceType":"function","functionDescription":"Original",
             "nodes":{},"connections":[],"localVariables":[]}
            """);
        storage.saveGraph(original);
        FlowGraph unchanged = FlowSerializer.deserialize(FlowSerializer.serialize(original));
        storage.saveGraph(unchanged);
        assertEquals(1L, unchanged.getResourceRevision());

        FlowGraph changed = FlowSerializer.deserialize(FlowSerializer.serialize(unchanged));
        changed.setFunctionDescription("Changed");
        storage.saveGraph(changed);
        assertEquals(2L, changed.getResourceRevision());

        FlowGraph undone = FlowSerializer.deserialize(FlowSerializer.serialize(original));
        undone.setResourceRevision(changed.getResourceRevision());
        undone.setResourceHash(changed.getResourceHash());
        undone.setResourceMutationId(changed.getResourceMutationId());
        storage.saveGraph(undone);

        assertEquals(3L, undone.getResourceRevision());
        assertEquals("Original", storage.getGraph("function", "request_message").getFunctionDescription());
    }

    @Test
    void untypedSaveRejectsCarriedLineageUntilGraphIsExplicitlyReclassified() {
        FlowStorage storage = newStorage();
        FlowGraph graph = FlowSerializer.deserialize("""
            {"id":"identity","version":2,"function":true,"nodes":{},"connections":[],"localVariables":[]}
            """);
        storage.saveGraph(graph);
        FlowGraph carried = FlowSerializer.deserialize(FlowSerializer.serialize(graph));
        carried.setFunction(false);
        carried.setResourceType("flow");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> storage.saveGraph(carried));

        assertEquals("Graph lineage belongs to function:identity; use reclassifyGraph to change it to flow",
            failure.getMessage());
        assertEquals("function", storage.getGraphResourceType("identity"));
        assertTrue(graph.isFunction());
        assertNull(storage.getGraph("flow", "identity"));

        storage.reclassifyGraph(graph, "flow");

        assertEquals("flow", storage.getGraphResourceType("identity"));
        assertFalse(graph.isFunction());
        assertNull(storage.getGraph("function", "identity"));
    }

    @Test
    void unknownGraphPropertiesSurviveRoundTrips() {
        FlowGraph graph = FlowSerializer.deserialize("""
            {"id":"future","version":2,"nodes":{},"connections":[],"localVariables":[],"futureContract":{"mode":"safe","revision":7}}
            """);

        String serialized = FlowSerializer.serialize(graph);

        assertTrue(serialized.contains("\"futureContract\""));
        assertEquals(7, FlowSerializer.deserialize(serialized).getOpaqueProperties().get("futureContract").getAsJsonObject().get("revision").getAsInt());
    }

    @Test
    void deleteRetainsTheAssetSnapshotAndPublishesATombstone() throws Exception {
        FlowStorage storage = newStorage();
        FlowGraph graph = FlowSerializer.deserialize("""
            {"id":"recoverable-delete","version":2,"nodes":{},"connections":[],"localVariables":[]}
            """);
        storage.saveGraph(graph);

        storage.deleteGraph(graph.getId());

        assertTrue(storage.listFlowIds().isEmpty());
        try (var paths = Files.walk(tempDir.resolve("assets").resolve(".snapshots"))) {
            assertTrue(paths.filter(Files::isRegularFile).anyMatch(path -> path.getFileName().toString().equals("recoverable-delete.json")));
        }
        assertTrue(Files.isRegularFile(
            tempDir.resolve("assets").resolve(".tombstones").resolve("flow").resolve("recoverable-delete.json")));
    }

    @Test
    void adoptedCanonicalResourceWinsOverDuplicatePresentationCopies() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path canonical = assets.resolve("Blueprints").resolve("Commands").resolve("shared.json");
        Path stale = assets.resolve("Blueprints").resolve("Flows").resolve("shared.json");
        Files.createDirectories(canonical.getParent());
        Files.createDirectories(stale.getParent());
        Files.writeString(canonical, AssetFileFormat.withResourceIdentity(
            "{\"id\":\"shared\",\"version\":2,\"nodes\":{\"canonical\":{\"type\":\"event.server.start\",\"x\":0,\"y\":0,\"inputValues\":{}}},\"connections\":[],\"localVariables\":[]}",
            "command", 1L, "canonical"));
        Files.writeString(stale, AssetFileFormat.withResourceIdentity(
            "{\"id\":\"shared\",\"version\":2,\"nodes\":{},\"connections\":[],\"localVariables\":[]}",
            "command", 1L, "stale"));
        Files.writeString(assets.resolve("project.json"), """
            {"resources":[{"type":"command","id":"shared","path":"Blueprints/Commands"}]}
            """);

        FlowStorage storage = newStorage();

        assertEquals("canonical", storage.getGraph("shared").getResourceMutationId());
    }

    @Test
    void typedGraphLookupSeparatesMatchingIds() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path flow = assets.resolve("Blueprints").resolve("Flows").resolve("shared.json");
        Path function = assets.resolve("Blueprints").resolve("Functions").resolve("shared.json");
        Files.createDirectories(flow.getParent());
        Files.createDirectories(function.getParent());
        Files.writeString(flow, AssetFileFormat.withResourceIdentity(
            "{\"id\":\"shared\",\"version\":2,\"nodes\":{},\"connections\":[],\"localVariables\":[],\"function\":false}",
            "flow", 1L, "flow"));
        Files.writeString(function, AssetFileFormat.withResourceIdentity(
            "{\"id\":\"shared\",\"version\":2,\"nodes\":{},\"connections\":[],\"localVariables\":[],\"function\":true}",
            "function", 1L, "function"));
        FlowStorage storage = newStorage();
        storage.clearCache();

        assertEquals("flow", storage.getGraph("flow", "shared").getResourceMutationId());
        assertEquals("function", storage.getGraph("function", "shared").getResourceMutationId());
        assertFalse(storage.getGraph("flow", "shared").isFunction());
        assertTrue(storage.getGraph("function", "shared").isFunction());

        FlowGraph functionGraph = storage.getGraph("function", "shared");
        storage.saveGraph(functionGraph);

        assertEquals("flow", storage.getGraph("flow", "shared").getResourceMutationId());
        assertEquals("function", storage.getGraph("function", "shared").getResourceMutationId());

        storage.deleteGraph("function", "shared");

        assertEquals("flow", storage.getGraph("flow", "shared").getResourceMutationId());
        assertNull(storage.getGraph("function", "shared"));
    }

    @Test
    void typedGraphDeleteRemovesOnlyItsProjectEntry() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path flowFile = assets.resolve("Blueprints").resolve("Flows").resolve("shared.json");
        Path functionFile = assets.resolve("Blueprints").resolve("Functions").resolve("shared.json");
        Files.createDirectories(flowFile.getParent());
        Files.createDirectories(functionFile.getParent());
        Files.writeString(flowFile, AssetFileFormat.withResourceIdentity(
            "{\"id\":\"shared\",\"version\":2,\"nodes\":{},\"connections\":[],\"localVariables\":[],\"function\":false,\"resourceType\":\"flow\"}",
            "flow", 1L, "flow-initial"));
        Files.writeString(functionFile, AssetFileFormat.withResourceIdentity(
            "{\"id\":\"shared\",\"version\":2,\"nodes\":{},\"connections\":[],\"localVariables\":[],\"function\":true,\"resourceType\":\"function\"}",
            "function", 1L, "function-initial"));
        Files.writeString(assets.resolve("project.json"), """
            {
              "serverId":"project",
              "folders":[],
              "resources":[
                {"type":"flow","id":"shared","displayName":"shared","path":"Blueprints/Flows","sortOrder":0},
                {"type":"function","id":"shared","displayName":"shared","path":"Blueprints/Functions","sortOrder":1}
              ]
            }
            """);
        FlowStorage storage = newStorage();
        storage.saveProjectMetadata(Files.readString(assets.resolve("project.json")),
            UUID.nameUUIDFromBytes("typed-delete-project-lineage".getBytes(StandardCharsets.UTF_8)), 0L);

        assertTrue(storage.listGraphIds("flow").contains("shared"));
        assertTrue(storage.listGraphIds("function").contains("shared"));

        storage.deleteGraph("flow", "shared");

        FlowStorage reopened = reopenStorage();
        FlowGraph deletedFlow = reopened.getGraph("flow", "shared");
        assertNull(deletedFlow, deletedFlow != null ? deletedFlow.getResourceType() + " " + deletedFlow.getResourceMutationId() : "");
        assertNotNull(reopened.getGraph("function", "shared"));
        assertFalse(reopened.listGraphIds("flow").contains("shared"));
        assertTrue(reopened.listGraphIds("function").contains("shared"));
        assertFalse(hasProjectResource(reopened.getProjectMetadata("project"), "flow", "shared"));
        assertTrue(hasProjectResource(reopened.getProjectMetadata("project"), "function", "shared"));
    }

    @Test
    void callerMutationIdentitySurvivesLiveGraphReopen() {
        FlowStorage storage = newStorage();
        FlowGraph graph = FlowSerializer.deserialize("""
            {"id":"caller-live","version":2,"nodes":{},"connections":[],"localVariables":[]}
            """);
         UUID mutationId = UUID.randomUUID();

         storage.saveGraph(graph, mutationId, 0L);
         FlowStorage.GraphIdentity savedIdentity = storage.readGraphIdentity("flow", "caller-live");
         assertNotNull(savedIdentity);

         FlowStorage reopened = reopenStorage();
         FlowStorage.GraphIdentity identity = reopened.readGraphIdentity("flow", "caller-live");
         assertNotNull(identity);
         assertEquals("flow", identity.type());
         assertEquals("caller-live", identity.id());
         assertEquals(1L, identity.revision());
         assertEquals(mutationId.toString(), identity.mutationId());
         assertEquals(savedIdentity.payloadHash(), identity.payloadHash());
         assertTrue(identity.payloadHash().matches("[0-9a-f]{64}"));
         assertFalse(identity.deleted());
    }

    @Test
    void callerMutationIdentitySurvivesTombstoneReopen() {
        FlowStorage storage = newStorage();
         FlowGraph graph = FlowSerializer.deserialize("""
             {"id":"caller-delete","version":2,"nodes":{},"connections":[],"localVariables":[]}
             """);
         storage.saveGraph(graph, UUID.randomUUID(), 0L);
         FlowStorage.GraphIdentity liveIdentity = storage.readGraphIdentity("flow", "caller-delete");
         assertNotNull(liveIdentity);
         UUID mutationId = UUID.randomUUID();

        storage.deleteGraph("flow", "caller-delete", mutationId, 1L);

        FlowStorage reopened = reopenStorage();
        FlowStorage.GraphIdentity identity = reopened.readGraphIdentity("flow", "caller-delete");
        assertNotNull(identity);
         assertEquals(2L, identity.revision());
         assertEquals(mutationId.toString(), identity.mutationId());
         assertEquals(liveIdentity.payloadHash(), identity.payloadHash());
         assertTrue(identity.payloadHash().matches("[0-9a-f]{64}"));
         assertTrue(identity.deleted());
    }

    @Test
    void callerExpectedRevisionConflictsWithDurableGraphState() {
        FlowStorage storage = newStorage();
        FlowGraph graph = FlowSerializer.deserialize("""
            {"id":"caller-conflict","version":2,"nodes":{},"connections":[],"localVariables":[]}
            """);
        UUID firstMutation = UUID.randomUUID();
        storage.saveGraph(graph, firstMutation, 0L);
        FlowGraph changed = storage.getGraph("flow", "caller-conflict");
        changed.setVersion(3);
        UUID secondMutation = UUID.randomUUID();
        storage.saveGraph(changed, secondMutation, 1L);

        FlowGraph stale = storage.getGraph("flow", "caller-conflict");
        stale.setVersion(4);
        ResourceRevisionConflictException conflict = assertThrows(ResourceRevisionConflictException.class,
            () -> storage.saveGraph(stale, UUID.randomUUID(), 1L));

        assertEquals(1L, conflict.getExpectedRevision());
        assertEquals(2L, conflict.getCurrentRevision());
        FlowStorage.GraphIdentity identity = storage.readGraphIdentity("flow", "caller-conflict");
        assertEquals(secondMutation.toString(), identity.mutationId());
        assertEquals(2L, identity.revision());
    }

    @Test
    void callerMutationIdIsIdempotentOnlyForTheSameGraphPayload() {
        FlowStorage storage = newStorage();
        FlowGraph graph = FlowSerializer.deserialize("""
            {"id":"caller-idempotent","version":2,"nodes":{},"connections":[],"localVariables":[]}
            """);
        UUID mutationId = UUID.randomUUID();

        storage.saveGraph(graph, mutationId, 0L);
        FlowGraph retry = FlowSerializer.deserialize(FlowSerializer.serialize(graph));
        storage.saveGraph(retry, mutationId, 0L);

        assertEquals(1L, retry.getResourceRevision());
        assertEquals(mutationId.toString(), retry.getResourceMutationId());

        FlowGraph changed = FlowSerializer.deserialize(FlowSerializer.serialize(graph));
        changed.setVersion(3);
        assertThrows(IllegalStateException.class, () -> storage.saveGraph(changed, mutationId, 0L));
        assertEquals(1L, storage.readGraphIdentity("flow", "caller-idempotent").revision());
    }

    @Test
    void callerMutationIdCannotCrossSaveAndDeleteOperations() {
        FlowStorage storage = newStorage();
        FlowGraph graph = FlowSerializer.deserialize("""
            {"id":"caller-cross-operation","version":2,"nodes":{},"connections":[],"localVariables":[]}
            """);
        UUID saveMutation = UUID.randomUUID();

        storage.saveGraph(graph, saveMutation, 0L);

        assertThrows(IllegalStateException.class,
            () -> storage.deleteGraph("flow", "caller-cross-operation", saveMutation, 1L));
        assertFalse(storage.readGraphIdentity("flow", "caller-cross-operation").deleted());

        UUID deleteMutation = UUID.randomUUID();
        storage.deleteGraph("flow", "caller-cross-operation", deleteMutation, 1L);
        FlowGraph replacement = FlowSerializer.deserialize(FlowSerializer.serialize(graph));
        assertThrows(IllegalStateException.class,
            () -> storage.saveGraph(replacement, deleteMutation, 2L));
        FlowStorage.GraphIdentity identity = storage.readGraphIdentity("flow", "caller-cross-operation");
        assertTrue(identity.deleted());
        assertEquals(deleteMutation.toString(), identity.mutationId());
    }

    @Test
    void untypedGraphOperationsRejectCrossTypeDuplicateIds() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path flow = assets.resolve("Blueprints").resolve("Flows").resolve("canonical-delete.json");
        Path function = assets.resolve("Blueprints").resolve("Functions").resolve("canonical-delete.json");
        Files.createDirectories(flow.getParent());
        Files.createDirectories(function.getParent());
        Files.writeString(flow, AssetFileFormat.withResourceIdentity(
            "{\"id\":\"canonical-delete\",\"version\":2,\"nodes\":{},\"connections\":[],\"localVariables\":[]}",
            "flow", 1L, UUID.randomUUID().toString()));
        Files.writeString(function, AssetFileFormat.withResourceIdentity(
            "{\"id\":\"canonical-delete\",\"version\":2,\"function\":true,\"nodes\":{},\"connections\":[],\"localVariables\":[]}",
            "function", 2L, UUID.randomUUID().toString()));
        FlowStorage storage = newStorage();
        storage.clearCache();

        assertThrows(IllegalArgumentException.class, () -> storage.getGraph("canonical-delete"));
        assertThrows(IllegalArgumentException.class, () -> storage.getGraphResourceType("canonical-delete"));
        assertThrows(IllegalArgumentException.class, () -> storage.deleteGraph("canonical-delete"));
        assertNotNull(storage.getGraph("flow", "canonical-delete"));
        assertNotNull(storage.getGraph("function", "canonical-delete"));
    }

    @Test
    void untypedDeleteRejectsMultipleDurableTombstones() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path flow = assets.resolve("Blueprints").resolve("Flows").resolve("ambiguous-delete.json");
        Path function = assets.resolve("Blueprints").resolve("Functions").resolve("ambiguous-delete.json");
        Files.createDirectories(flow.getParent());
        Files.createDirectories(function.getParent());
        Files.writeString(flow, AssetFileFormat.withResourceIdentity(
            "{\"id\":\"ambiguous-delete\",\"version\":2,\"nodes\":{},\"connections\":[],\"localVariables\":[]}",
            "flow", 1L, UUID.randomUUID().toString()));
        Files.writeString(function, AssetFileFormat.withResourceIdentity(
            "{\"id\":\"ambiguous-delete\",\"version\":2,\"function\":true,\"nodes\":{},\"connections\":[],\"localVariables\":[]}",
            "function", 1L, UUID.randomUUID().toString()));
        FlowStorage storage = newStorage();
        storage.clearCache();

        storage.deleteGraph("flow", "ambiguous-delete");
        storage.deleteGraph("function", "ambiguous-delete");

        assertThrows(IllegalArgumentException.class, () -> storage.deleteGraph("ambiguous-delete"));
    }

    @Test
    void committedTombstoneBlocksLegacyResurrection() throws Exception {
        FlowStorage storage = new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.compatibility(tempDir), coordinator());
        Path legacy = tempDir.resolve("flows").resolve("legacy-delete.json");
        Files.createDirectories(legacy.getParent());
        String legacyJson = "{\"id\":\"legacy-delete\",\"version\":2,\"nodes\":{},\"connections\":[],\"localVariables\":[]}";
        Files.writeString(legacy, legacyJson);
        UUID mutationId = UUID.randomUUID();

        storage.deleteGraph("flow", "legacy-delete", mutationId, 0L);

        assertFalse(Files.exists(legacy));
        Files.writeString(legacy, legacyJson);
        FlowStorage reopened = reopenStorage();
        assertNull(reopened.getGraph("legacy-delete"));
        FlowStorage.GraphIdentity identity = reopened.readGraphIdentity("flow", "legacy-delete");
        assertNotNull(identity);
        assertTrue(identity.deleted());
        assertEquals(mutationId.toString(), identity.mutationId());
    }

    @Test
    void invalidTombstoneIdentityIsNotReadable() throws Exception {
        FlowStorage storage = newStorage();
        Path tombstone = tempDir.resolve("assets").resolve(".tombstones").resolve("flow").resolve("invalid.json");
        Files.createDirectories(tombstone.getParent());
        String mutationId = UUID.randomUUID().toString();
        String otherMutationId = UUID.randomUUID().toString();
        for (String json : List.of(
            "{\"type\":\"flow\",\"id\":\"invalid\",\"revision\":1,\"deleted\":false,\"mutationId\":\"" + mutationId + "\"}",
            "{\"type\":\"function\",\"id\":\"invalid\",\"revision\":1,\"deleted\":true,\"mutationId\":\"" + mutationId + "\"}",
            "{\"type\":\"flow\",\"id\":\"other\",\"revision\":1,\"deleted\":true,\"mutationId\":\"" + mutationId + "\"}",
            "{\"type\":\"flow\",\"id\":\"invalid\",\"revision\":0,\"deleted\":true,\"mutationId\":\"" + mutationId + "\"}",
            "{\"type\":\"flow\",\"id\":\"invalid\",\"revision\":1,\"deleted\":true,\"mutationId\":\"not-a-uuid\"}",
            "{\"type\":\"flow\",\"id\":\"invalid\",\"revision\":1,\"deleted\":true,\"mutationId\":\"" + mutationId + "\",\"assetMutationId\":\"" + otherMutationId + "\"}",
            "{\"type\":\"flow\",\"id\":\"invalid\",\"revision\":1,\"mutationId\":\"" + mutationId + "\"}"
        )) {
            Files.writeString(tombstone, json);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> storage.readGraphIdentity("flow", "invalid"));
            assertEquals("Invalid graph tombstone: flow:invalid", failure.getMessage());
        }
    }

    private FlowStorage newStorage() {
        return new FlowStorage(tempDir.toFile(), coordinator());
    }

    private FlowStorage reopenStorage() {
        releaseCoordinator();
        return newStorage();
    }

    private AssetTransactionCoordinator coordinator() {
        if (coordinator != null) {
            return coordinator;
        }
        try {
            Path assets = tempDir.resolve("assets");
            Path genesis = assets.resolve(".asset-coordinator").resolve("genesis.json");
            coordinator = Files.isRegularFile(genesis)
                ? AssetTransactionCoordinator.open(assets, new Gson())
                : requiresAdoption(assets)
                    ? AssetTransactionCoordinator.adoptExisting(assets, new Gson(), adoptionInventory(assets))
                    : AssetTransactionCoordinator.open(assets, new Gson());
            return coordinator;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create the test asset coordinator", exception);
        }
    }

    private boolean requiresAdoption(Path assets) throws IOException {
        return Files.isRegularFile(assets.resolve("project.json")) || !assetFiles(assets).isEmpty();
    }

    private List<Path> assetFiles(Path assets) throws IOException {
        if (!Files.isDirectory(assets)) {
            return List.of();
        }
        try (var paths = Files.walk(assets)) {
            return paths.filter(Files::isRegularFile)
                .filter(path -> !isCoordinatorPath(assets, path))
                .sorted()
                .toList();
        }
    }

    private boolean isCoordinatorPath(Path assets, Path file) {
        Path relative = assets.relativize(file);
        if (relative.getNameCount() == 0 || relative.toString().equals("project.json")) {
            return true;
        }
        return Set.of(".asset-coordinator", ".transactions", ".snapshots", ".quarantine", ".migrations")
            .contains(relative.getName(0).toString());
    }

    private AssetTransactionCoordinator.AdoptionInventory adoptionInventory(Path assets) throws IOException {
        Path projectFile = assets.resolve("project.json");
        String project = Files.isRegularFile(projectFile) ? Files.readString(projectFile) : "{}";
        List<AssetTransactionCoordinator.AdoptedAsset> adopted = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Path file : assetFiles(assets)) {
            byte[] content = Files.readAllBytes(file);
            Path relative = assets.relativize(file);
            JsonObject object = parseAsset(content);
            String id = object != null && object.has("id") && object.get("id").isJsonPrimitive()
                ? object.get("id").getAsString()
                : AssetFileFormat.idFromIdOnlyFileName(relative.getFileName().toString());
            String type = assetType(relative, object);
            String canonical = new AssetTransactionCoordinator.AssetKey(type, id).canonical();
            if (!keys.add(canonical)) {
                type = "legacy-duplicate:" + type;
                id = relative.toString().replace('\\', '/');
                keys.add(new AssetTransactionCoordinator.AssetKey(type, id).canonical());
            }
            String mutation = AssetFileFormat.readMutationId(file);
            if (mutation.isBlank()) {
                mutation = UUID.nameUUIDFromBytes(relative.toString().replace('\\', '/')
                    .getBytes(StandardCharsets.UTF_8)).toString();
            }
            adopted.add(new AssetTransactionCoordinator.AdoptedAsset(
                new AssetTransactionCoordinator.AssetKey(type, id), relative,
                new AssetTransactionCoordinator.Live(AssetFileFormat.readRevision(file), StorageSafety.sha256(content)),
                new AssetTransactionCoordinator.AssetMutationId(mutation), content, null));
        }
        List<AssetTransactionCoordinator.AdoptionEvidence> evidence = evidenceFiles(assets).stream().map(file -> {
            try {
                byte[] content = Files.readAllBytes(file);
                return new AssetTransactionCoordinator.AdoptionEvidence(
                    assets.relativize(file).toString().replace('\\', '/'), StorageSafety.sha256(content), content.length);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to bind asset adoption evidence", exception);
            }
        }).toList();
        return new AssetTransactionCoordinator.AdoptionInventory(
            "flow-storage-durability-test", project, adopted, List.of(), evidence);
    }

    private List<Path> evidenceFiles(Path assets) throws IOException {
        if (!Files.isDirectory(assets)) {
            return List.of();
        }
        try (var paths = Files.walk(assets)) {
            return paths.filter(Files::isRegularFile)
                .filter(path -> path.startsWith(assets.resolve(".tombstones"))
                    || path.startsWith(assets.resolve(".quarantine"))
                    || path.startsWith(assets.resolve(".migrations")))
                .sorted()
                .toList();
        }
    }

    private JsonObject parseAsset(byte[] content) {
        try {
            var parsed = JsonParser.parseString(new String(content, StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String assetType(Path relative, JsonObject object) {
        if (relative.getNameCount() >= 3 && ".tombstones".equals(relative.getName(0).toString())) {
            return "tombstone:" + relative.getName(1);
        }
        if (object != null && object.has(AssetFileFormat.RESOURCE_TYPE)
            && object.get(AssetFileFormat.RESOURCE_TYPE).isJsonPrimitive()) {
            return object.get(AssetFileFormat.RESOURCE_TYPE).getAsString();
        }
        if (object != null && object.has("function") && object.get("function").isJsonPrimitive()
            && object.get("function").getAsBoolean()) {
            return "function";
        }
        String path = relative.toString().replace('\\', '/');
        if (path.startsWith("Blueprints/Functions/")) {
            return "function";
        }
        if (path.startsWith("Blueprints/Commands/")) {
            return "command";
        }
        return "flow";
    }

    private boolean hasProjectResource(String json, String type, String id) {
        JsonObject project = JsonParser.parseString(json).getAsJsonObject();
        return project.has("resources") && project.getAsJsonArray("resources").asList().stream()
            .filter(element -> element != null && element.isJsonObject())
            .map(element -> element.getAsJsonObject())
            .anyMatch(resource -> resource.has("type") && type.equals(resource.get("type").getAsString())
                && resource.has("id") && id.equals(resource.get("id").getAsString()));
    }

    private Path activeAsset(String fileName) throws Exception {
        try (var paths = Files.walk(tempDir.resolve("assets"))) {
            return paths.filter(Files::isRegularFile)
                .filter(path -> !path.toString().contains(".transactions") && !path.toString().contains(".snapshots") && !path.toString().contains(".quarantine"))
                .filter(path -> path.getFileName().toString().equals(fileName))
                .findFirst()
                .orElseThrow();
        }
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
