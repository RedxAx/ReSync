package restudio.resync.flow;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowGraph;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowStorageCoreReadTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("a".repeat(64)), new ContentHash("b".repeat(64)));

    @TempDir
    Path tempDir;

    @Test
    void readsAnExactIndexedCoreEnvelope() throws Exception {
        writeCore(SERVER, "main");
        try (AssetTransactionCoordinator coordinator = transactions()) {
            FlowStorage storage = new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.runtime(tempDir),
                new AssetPersistenceGate(tempDir), SERVER, coordinator);
            var decoded = storage.getCoreGraph("flow", "main").orElseThrow();
            assertEquals(4, decoded.envelope().assetFormatVersion());
            assertEquals(3, decoded.envelope().assetRevision());
            assertEquals("keep", decoded.graphDocument().unknown().get("future"));
        }
    }

    @Test
    void loadsCanonicalCoreGraphThroughFlowAndPreloadPaths() throws Exception {
        writeCore(SERVER, "main");
        try (AssetTransactionCoordinator coordinator = transactions()) {
            FlowStorage storage = new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.runtime(tempDir),
                new AssetPersistenceGate(tempDir), SERVER, coordinator);
            FlowGraph loaded = storage.getGraph("flow", "main");
            assertNotNull(loaded);
            assertEquals("flow", loaded.getResourceType());
            storage.clearCache();
            storage.preloadAll();
            assertNotNull(storage.getGraphCache().get("flow\nmain"));
        }
    }

    @Test
    void legacyOnlyAndMismatchedIdentityPathsFailClosed() throws Exception {
        writeCore(SERVER, "main");
        try (AssetTransactionCoordinator coordinator = transactions()) {
            FlowStorage legacyOnly = new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.runtime(tempDir),
                new AssetPersistenceGate(tempDir), coordinator);
            assertThrows(IllegalStateException.class, () -> legacyOnly.getCoreGraph("flow", "main"));

            FlowStorage wrongServer = new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.runtime(tempDir),
                new AssetPersistenceGate(tempDir), new ServerId(UUID.fromString("22222222-2222-4222-8222-222222222222")),
                coordinator);
            assertThrows(IllegalStateException.class, () -> wrongServer.getCoreGraph("flow", "main"));

            Files.writeString(tempDir.resolve("assets/Blueprints/Flows/main.json"), "{\"id\":\"main\"}");
            FlowStorage legacy = new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.runtime(tempDir),
                new AssetPersistenceGate(tempDir), SERVER, coordinator);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> legacy.getCoreGraph("flow", "main"));
            assertTrue(failure.getCause() instanceof IOException);
        }
    }

    private AssetTransactionCoordinator transactions() {
        try {
            Path assets = tempDir.resolve("assets");
            Path file = assets.resolve("Blueprints/Flows/main.json");
            byte[] content = Files.readAllBytes(file);
            return AssetTransactionCoordinator.adoptExisting(assets, new Gson(),
                new AssetTransactionCoordinator.AdoptionInventory("flow-storage-core-read-test",
                    Files.readString(assets.resolve("project.json")),
                    List.of(new AssetTransactionCoordinator.AdoptedAsset(
                        new AssetTransactionCoordinator.AssetKey("flow", "main"),
                        Path.of("Blueprints/Flows/main.json"),
                        new AssetTransactionCoordinator.Live(3L, StorageSafety.sha256(content)),
                        new AssetTransactionCoordinator.AssetMutationId(
                            "33333333-3333-4333-8333-333333333333"), content, null))));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to open asset transaction coordinator", exception);
        }
    }

    private void writeCore(ServerId server, String id) throws Exception {
        Path assets = tempDir.resolve("assets");
        Path file = assets.resolve("Blueprints/Flows").resolve(id + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(assets.resolve("project.json"), "{\"resources\":[{\"type\":\"flow\",\"id\":\"" + id
            + "\",\"path\":\"Blueprints/Flows\"}]}");
        ServerResourceLocator locator = new ServerResourceLocator(server,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("flow")), id);
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), locator, 3, BINDING, Set.of(), List.of(),
            List.of(), List.of(), List.of(), OpaqueData.of(Map.of("future", "keep")));
        byte[] encoded = new CoreGraphStorageBoundary().encode(graph,
            new CoreGraphStorageBoundary.AssetMetadata("flow", 3, UUID.fromString("33333333-3333-4333-8333-333333333333")), locator);
        Files.write(file, encoded);
    }
}
