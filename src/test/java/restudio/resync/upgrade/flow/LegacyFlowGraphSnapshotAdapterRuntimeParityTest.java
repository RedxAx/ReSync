package restudio.resync.upgrade.flow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowGraph;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.ProductionSnapshotMetadataManifest;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotManifest;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotState;
import restudio.resync.migration.SnapshotVerification;
import restudio.resync.resources.AssetFileFormat;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.StorageSafety;
import restudio.resync.upgrade.ImmutableSnapshotAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotInput;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyFlowGraphSnapshotAdapterRuntimeParityTest {
    private static final String LEGACY_GRAPH = """
        {
          "id":"parity",
          "version":1,
          "function":true,
          "functionInputs":[{"name":"items","type":"list"}],
          "localVariables":[{"name":"value","type":"string","initialValue":""}],
          "nodes":{
            "loop":{"type":"loop_while","version":1,"inputValues":{}},
            "format":{"type":"misc.time_format","version":1,"inputValues":{"timestamp_ms":1000,"format_pattern":"uuuu-MM-dd"}},
            "particle":{"type":"particle_circle","version":1,"inputValues":{"particle_type":"FLAME","center_location":"legacy","points":32}},
            "target":{"type":"test.target","version":1,"inputValues":{}}
          },
          "connections":[
            {"sourceNodeId":"loop","sourcePin":"completed","targetNodeId":"target","targetPin":"flow"},
            {"sourceNodeId":"format","sourcePin":"formatted_string","targetNodeId":"target","targetPin":"value"},
            {"sourceNodeId":"particle","sourcePin":"next","targetNodeId":"target","targetPin":"flow"}
          ],
          "future":{"preserve":{"nested":[true,null,{"value":7}]}}
        }
        """;

    @Test
    void standalonePreservesFrozenRuntimeOracleForLegacyGraphShapes() {
        byte[] standaloneBytes = new LegacyFlowGraphSnapshotAdapter().transformGraph(LEGACY_GRAPH.getBytes(), "flow");
        Map<?, ?> standalone = root(standaloneBytes);
        assertEquals(2, integer(standalone.get("version")));
        assertEquals("server", standalone.get("functionOwner"));
        assertEquals("local", standalone.get("functionNamespace"));
        assertEquals(List.of(Map.of(
            "name", "value",
            "type", "string",
            "initialValue", "",
            "scope", "local",
            "lifetime", "execution",
            "owner", "graph",
            "absencePolicy", "use_default",
            "concurrencyPolicy", "isolated")), standalone.get("localVariables"));
        Map<?, ?> functionInput = map(((List<?>) standalone.get("functionInputs")).getFirst());
        assertEquals("items", functionInput.get("name"));
        assertEquals(Map.of("typeId", "list", "arguments", List.of(
            Map.of("typeId", "any", "arguments", List.of()))), functionInput.get("typeRef"));
        Map<?, ?> nodes = map(standalone.get("nodes"));
        Map<?, ?> loop = map(nodes.get("loop"));
        Map<?, ?> format = map(nodes.get("format"));
        Map<?, ?> particle = map(nodes.get("particle"));
        assertEquals("loop_while", loop.get("type"));
        assertEquals("time_format", format.get("type"));
        assertEquals("particle.apply", particle.get("type"));
        assertEquals(Map.of("operation", "particle_apply"), particle.get("handlerConfig"));
        assertEquals(1000, integer(map(format.get("inputValues")).get("time")));
        assertEquals("uuuu-MM-dd", map(format.get("inputValues")).get("format"));
        assertEquals("circle", map(particle.get("inputValues")).get("mode"));
        assertEquals("FLAME", map(particle.get("inputValues")).get("particle"));
        assertEquals("legacy", map(particle.get("inputValues")).get("location"));
        assertEquals(32, integer(map(particle.get("inputValues")).get("count")));
        assertEquals("done", map(((List<?>) standalone.get("connections")).get(0)).get("sourcePin"));
        assertEquals("string", map(((List<?>) standalone.get("connections")).get(1)).get("sourcePin"));
        assertEquals("output_flow", map(((List<?>) standalone.get("connections")).get(2)).get("sourcePin"));
        Map<?, ?> future = map(standalone.get("future"));
        Map<?, ?> nested = map(future.get("preserve"));
        List<?> futureValues = (List<?>) nested.get("nested");
        assertEquals(Boolean.TRUE, futureValues.getFirst());
        assertEquals(null, futureValues.get(1));
        assertEquals(7, integer(map(futureValues.get(2)).get("value")));
        assertEquals("flow", standalone.get("resourceType"));
        assertTrue(standalone.containsKey("assetRevision"));
        assertTrue(standalone.containsKey("assetHash"));
    }

    @Test
    void standalonePreservesFrozenLegacyAliasPinExpectations() throws Exception {
        JsonObject fixture = JsonParser.parseString(Files.readString(Path.of(
            "src/test/resources/fixtures/node-replacement/migration/legacy-graph-alias-pins.json"))).getAsJsonObject();
        Map<?, ?> transformed = root(new LegacyFlowGraphSnapshotAdapter().transformGraph(
            fixture.toString().getBytes(StandardCharsets.UTF_8), "flow"));
        JsonObject expected = fixture.getAsJsonObject("currentExpectation");
        Map<?, ?> nodes = map(transformed.get("nodes"));
        List<?> connections = (List<?>) transformed.get("connections");
        assertEquals(expected.get("loopType").getAsString(), map(nodes.get("loop")).get("type"));
        assertEquals(expected.get("firstSourcePin").getAsString(), map(connections.getFirst()).get("sourcePin"));
        assertEquals(expected.get("secondSourcePin").getAsString(), map(connections.get(1)).get("sourcePin"));
        assertEquals(expected.get("secondTargetPin").getAsString(), map(connections.get(1)).get("targetPin"));
        assertTrue(transformed.containsKey("assetHash"));
    }

    @Test
    void transformedAssetIsAcceptedByProductionSerializerAndStorage(@TempDir Path temporary) throws Exception {
        byte[] transformed = new LegacyFlowGraphSnapshotAdapter().transformGraph(LEGACY_GRAPH.getBytes(), "function");
        Path asset = temporary.resolve("assets/Blueprints/Functions/parity.json");
        Files.createDirectories(asset.getParent());
        Files.write(asset, transformed);
        String serialized = Files.readString(asset);
        assertTrue(AssetFileFormat.verify(asset), serialized);

        try (AssetTransactionCoordinator coordinator = adoptedCoordinator(
            temporary.resolve("assets"), "function", "parity")) {
            FlowStorage storage = new FlowStorage(temporary.toFile(), LegacyRuntimeActivationGate.compatibility(temporary), coordinator);
            FlowGraph loaded = storage.getGraph("function", "parity");
            assertNotNull(loaded);
            assertEquals("function", loaded.getResourceType());
            assertTrue(loaded.getResourceRevision() > 0L);
            assertFalse(loaded.getResourceMutationId().isBlank());
        }
    }

    @Test
    void snapshotAdapterOutputIsBoundToProductionMetadataAndLoads(@TempDir Path temporary) throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        Path graph = Files.createDirectories(source.resolve("assets/Blueprints/Functions")).resolve("parity.json");
        Files.writeString(graph, LEGACY_GRAPH, StandardCharsets.UTF_8);
        SnapshotMetadata metadata = new SnapshotMetadata(1, "flow-parity-snapshot", Instant.EPOCH,
            LegacyFlowGraphSnapshotAdapter.SOURCE_BUILD, "a".repeat(64), Map.of());
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(participant(ProductionPersistenceOwners.FLOW_ASSETS, source.resolve("assets")));
        SnapshotManifest manifest = SnapshotManifest.scan(source, metadata, participants);
        Path manifestPath = source.resolveSibling(source.getFileName() + ".manifest");
        manifest.write(manifestPath);
        ProductionSnapshotMetadataManifest.write(source, manifest);
        SnapshotVerification verification = manifest.verify(source);
        verification.requireVerified();
        Snapshot snapshot = new Snapshot(source, manifestPath, source.resolveSibling(source.getFileName() + ".state"), metadata,
            manifest, SnapshotState.VERIFIED, verification);

        OfflineUpgradeSnapshotAdapter.SnapshotTransform transformed = new LegacyFlowGraphSnapshotAdapter().transform(
            new OfflineUpgradeSnapshotInput(source, ImmutableSnapshotAdapter.adapt(snapshot)));
        assertTrue(transformed.quarantines().isEmpty());
        byte[] emitted = transformed.files().stream()
            .filter(file -> file.targetPath().equals("assets/Blueprints/Functions/parity.json"))
            .findFirst()
            .orElseThrow()
            .bytes();
        Path dataFolder = Files.createDirectories(temporary.resolve("runtime"));
        Path runtimeFile = Files.createDirectories(dataFolder.resolve("assets/Blueprints/Functions")).resolve("parity.json");
        Files.write(runtimeFile, emitted);
        assertTrue(AssetFileFormat.verify(runtimeFile));
        try (AssetTransactionCoordinator coordinator = adoptedCoordinator(
            dataFolder.resolve("assets"), "function", "parity")) {
            FlowStorage storage = new FlowStorage(dataFolder.toFile(), LegacyRuntimeActivationGate.compatibility(dataFolder), coordinator);
            FlowGraph loaded = storage.getGraph("function", "parity");
            assertNotNull(loaded);
            assertEquals("function", loaded.getResourceType());
        }
    }

    private AssetTransactionCoordinator adoptedCoordinator(Path assets, String type, String id) throws Exception {
        Path file = assets.resolve("Blueprints/Functions").resolve(id + ".json");
        byte[] content = Files.readAllBytes(file);
        return AssetTransactionCoordinator.adoptExisting(assets, new Gson(),
            new AssetTransactionCoordinator.AdoptionInventory("legacy-flow-graph-runtime-parity-test", "{}",
                List.of(new AssetTransactionCoordinator.AdoptedAsset(
                    new AssetTransactionCoordinator.AssetKey(type, id),
                    Path.of("Blueprints/Functions").resolve(id + ".json"),
                    new AssetTransactionCoordinator.Live(AssetFileFormat.readRevision(file),
                        StorageSafety.sha256(content)),
                    new AssetTransactionCoordinator.AssetMutationId(AssetFileFormat.readMutationId(file)), content,
                    null))));
    }

    private PersistenceParticipant participant(String owner, Path root) {
        return new PersistenceParticipant() {
            @Override
            public String owner() {
                return owner;
            }

            @Override
            public Path root() {
                return root;
            }
        };
    }

    private static Map<?, ?> root(byte[] bytes) {
        return ((restudio.resync.contract.canonical.JsonValue.JsonObject) CanonicalCodec.decodePermissive(bytes)).toJava() instanceof Map<?, ?> map
            ? map
            : Map.of();
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static int integer(Object value) {
        return ((Number) value).intValue();
    }
}
