package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.resync.resources.AssetFileFormat;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowStorageDurabilityTest {
    @TempDir
    Path tempDir;

    @Test
    void graphSaveCarriesVerifiedRevisionAndTransactionJournal() throws Exception {
        FlowStorage storage = new FlowStorage(tempDir.toFile());
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
        FlowStorage storage = new FlowStorage(tempDir.toFile());
        FlowGraph graph = FlowSerializer.deserialize("""
            {"id":"shared","version":2,"nodes":{},"connections":[],"localVariables":[]}
            """);
        storage.saveGraph(graph);
        FlowGraph firstEditor = FlowSerializer.deserialize(FlowSerializer.serialize(graph));
        FlowGraph staleEditor = FlowSerializer.deserialize(FlowSerializer.serialize(graph));

        firstEditor.setResourceMutationId("");
        storage.saveGraph(firstEditor);

        assertThrows(ResourceRevisionConflictException.class, () -> storage.saveGraph(staleEditor));
        assertEquals(2L, storage.getGraph("shared").getResourceRevision());
    }

    @Test
    void normalSaveCannotImplicitlyReclassifyAnExistingGraph() {
        FlowStorage storage = new FlowStorage(tempDir.toFile());
        FlowGraph graph = FlowSerializer.deserialize("""
            {"id":"identity","version":2,"function":true,"nodes":{},"connections":[],"localVariables":[]}
            """);
        storage.saveGraph(graph);
        String mutation = graph.getResourceMutationId();
        graph.setFunction(false);
        graph.setResourceType("flow");

        storage.saveGraph(graph);

        assertEquals("function", storage.getGraphResourceType("identity"));
        assertTrue(graph.isFunction());
        assertNotEquals(mutation, graph.getResourceMutationId());
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
    void deleteQuarantinesTheAssetForRecovery() throws Exception {
        FlowStorage storage = new FlowStorage(tempDir.toFile());
        FlowGraph graph = FlowSerializer.deserialize("""
            {"id":"recoverable-delete","version":2,"nodes":{},"connections":[],"localVariables":[]}
            """);
        storage.saveGraph(graph);

        storage.deleteGraph(graph.getId());

        assertTrue(storage.listFlowIds().isEmpty());
        try (var paths = Files.walk(tempDir.resolve("assets").resolve(".quarantine").resolve("deletes"))) {
            assertTrue(paths.filter(Files::isRegularFile).anyMatch(path -> path.getFileName().toString().equals("recoverable-delete.json")));
        }
    }

    @Test
    void projectMetadataSelectsTheCanonicalDuplicate() throws Exception {
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

        FlowStorage storage = new FlowStorage(tempDir.toFile());

        assertEquals("canonical", storage.getGraph("shared").getResourceMutationId());
    }

    @Test
    void typedGraphLookupSeparatesMatchingIds() throws Exception {
        FlowStorage storage = new FlowStorage(tempDir.toFile());
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
        storage.clearCache();

        assertEquals("flow", storage.getGraph("flow", "shared").getResourceMutationId());
        assertEquals("function", storage.getGraph("function", "shared").getResourceMutationId());
        assertFalse(storage.getGraph("flow", "shared").isFunction());
        assertTrue(storage.getGraph("function", "shared").isFunction());

        FlowGraph functionGraph = storage.getGraph("function", "shared");
        storage.saveGraph(functionGraph);

        assertEquals("flow", storage.getGraph("flow", "shared").getResourceMutationId());
        assertNotEquals("function", storage.getGraph("function", "shared").getResourceMutationId());

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
        Files.writeString(flowFile, "{\"id\":\"shared\",\"version\":2,\"nodes\":{},\"connections\":[],\"localVariables\":[],\"function\":false,\"resourceType\":\"flow\"}");
        Files.writeString(functionFile, "{\"id\":\"shared\",\"version\":2,\"nodes\":{},\"connections\":[],\"localVariables\":[],\"function\":true,\"resourceType\":\"function\"}");
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
        FlowStorage storage = new FlowStorage(tempDir.toFile());

        assertTrue(storage.listGraphIds("flow").contains("shared"));
        assertTrue(storage.listGraphIds("function").contains("shared"));

        storage.deleteGraph("flow", "shared");

        FlowStorage reopened = new FlowStorage(tempDir.toFile());
        FlowGraph deletedFlow = reopened.getGraph("flow", "shared");
        assertNull(deletedFlow, deletedFlow != null ? deletedFlow.getResourceType() + " " + deletedFlow.getResourceMutationId() : "");
        assertNotNull(reopened.getGraph("function", "shared"));
        assertFalse(reopened.listGraphIds("flow").contains("shared"));
        assertTrue(reopened.listGraphIds("function").contains("shared"));
        assertFalse(reopened.getProjectMetadata("project").contains("\"type\":\"flow\",\"id\":\"shared\""));
        assertTrue(reopened.getProjectMetadata("project").contains("\"type\":\"function\",\"id\":\"shared\""));
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
