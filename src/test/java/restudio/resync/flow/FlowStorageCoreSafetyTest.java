package restudio.resync.flow;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FlowStorageCoreSafetyTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("a".repeat(64)),
        new ContentHash("b".repeat(64)));

    @TempDir
    Path tempDir;

    @Test
    void malformedProjectMetadataFailsClosedForCoreSaveWithoutOverwrite() throws Exception {
        FlowStorage storage = storage();
        storage.saveCoreGraph(graph("metadata-seed", 1), ResourceActivationState.ACTIVE,
            mutation("11111111-1111-4111-8111-111111111111"), 0L);
        assertTrue(storage.getProjectMetadata("project") != null);
        Path project = tempDir.resolve("assets/project.json");
        String malformed = "{\"resources\":[";
        Files.writeString(project, malformed);

        assertThrows(IllegalStateException.class, () -> storage.saveCoreGraph(graph("save-malformed", 1),
            ResourceActivationState.ACTIVE, mutation("22222222-2222-4222-8222-222222222222"), 0L));

        assertEquals(malformed, Files.readString(project));
        assertFalse(Files.exists(tempDir.resolve("assets/Blueprints/Flows/save-malformed.json")));
    }

    @Test
    void malformedProjectMetadataFailsClosedForCoreDeleteWithoutIgnoringLiveState() throws Exception {
        FlowStorage storage = storage();
        storage.saveCoreGraph(graph("delete-malformed", 1), ResourceActivationState.ACTIVE,
            mutation("33333333-3333-4333-8333-333333333333"), 0L);
        Path live = tempDir.resolve("assets/Blueprints/Flows/delete-malformed.json");
        byte[] liveBytes = Files.readAllBytes(live);
        Path project = tempDir.resolve("assets/project.json");
        String malformed = "not-json";
        Files.writeString(project, malformed);

        assertThrows(IllegalStateException.class, () -> storage.deleteCoreGraph("flow", "delete-malformed",
            mutation("44444444-4444-4444-8444-444444444444"), 1L));

        assertEquals(malformed, Files.readString(project));
        assertArrayEquals(liveBytes, Files.readAllBytes(live));
        assertFalse(Files.exists(tempDir.resolve("assets/.tombstones/flow/delete-malformed.json")));
    }

    @Test
    void rejectsSymlinkedProjectMetadataBeforeCoreSave() throws Exception {
        FlowStorage storage = storage();
        Path target = tempDir.resolve("outside-project.json");
        Files.writeString(target, "{\"resources\":[]}");
        Path project = tempDir.resolve("assets/project.json");
        Files.createDirectories(project.getParent());
        Files.deleteIfExists(project);
        createSymlink(project, target);

        assertThrows(IllegalStateException.class, () -> storage.saveCoreGraph(graph("symlink-project", 1),
            ResourceActivationState.ACTIVE, mutation("55555555-5555-4555-8555-555555555555"), 0L));

        assertEquals("{\"resources\":[]}", Files.readString(target));
        assertFalse(Files.exists(tempDir.resolve("assets/Blueprints/Flows/symlink-project.json")));
    }

    @Test
    void rejectsSymlinkedLiveAssetBeforeCoreDelete() throws Exception {
        FlowStorage storage = storage();
        storage.saveCoreGraph(graph("symlink-live", 1), ResourceActivationState.ACTIVE,
            mutation("66666666-6666-4666-8666-666666666666"), 0L);
        Path live = tempDir.resolve("assets/Blueprints/Flows/symlink-live.json");
        Path outside = tempDir.resolve("outside-live.json");
        Files.writeString(outside, "outside-live");
        Files.delete(live);
        createSymlink(live, outside);

        assertThrows(IllegalStateException.class, () -> storage.deleteCoreGraph("flow", "symlink-live",
            mutation("77777777-7777-4777-8777-777777777777"), 1L));

        assertEquals("outside-live", Files.readString(outside));
        assertTrue(Files.isSymbolicLink(live));
        assertFalse(Files.exists(tempDir.resolve("assets/.tombstones/flow/symlink-live.json")));
    }

    @Test
    void rejectsSymlinkedTombstoneBeforeCoreSave() throws Exception {
        FlowStorage storage = storage();
        storage.saveCoreGraph(graph("symlink-tombstone", 1), ResourceActivationState.ACTIVE,
            mutation("88888888-8888-4888-8888-888888888888"), 0L);
        Path tombstone = tempDir.resolve("assets/.tombstones/flow/symlink-tombstone.json");
        Path outside = tempDir.resolve("outside-tombstone.json");
        Files.writeString(outside, "outside-tombstone");
        Files.createDirectories(tombstone.getParent());
        createSymlink(tombstone, outside);

        assertThrows(IllegalStateException.class, () -> storage.saveCoreGraph(graph("symlink-tombstone", 2),
            ResourceActivationState.ACTIVE, mutation("99999999-9999-4999-8999-999999999999"), 1L));

        assertEquals("outside-tombstone", Files.readString(outside));
        assertTrue(Files.isSymbolicLink(tombstone));
    }

    @Test
    void rejectsUnsafeLegacyGraphCandidateAncestorsBeforeCoreSave() throws Exception {
        Path outside = tempDir.resolve("outside-flows");
        Files.createDirectories(outside);
        Path legacy = outside.resolve("unsafe-legacy.json");
        Files.writeString(legacy, "legacy");
        Path flowDirectory = tempDir.resolve("flows");
        createSymlink(flowDirectory, outside);
        FlowStorage storage = storage();

        assertThrows(IllegalStateException.class, () -> storage.saveCoreGraph(graph("unsafe-legacy", 1),
            ResourceActivationState.ACTIVE, mutation("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), 0L));

        assertEquals("legacy", Files.readString(legacy));
        assertFalse(Files.exists(tempDir.resolve("assets/Blueprints/Flows/unsafe-legacy.json")));
    }

    @Test
    void rejectsSymlinkedAssetsRootDuringCoreStorageInitialization() throws Exception {
        Path outside = tempDir.resolve("outside-assets");
        Files.createDirectories(outside);
        Path assets = tempDir.resolve("assets");
        createSymlink(assets, outside);

        assertThrows(IllegalArgumentException.class, this::storage);
        assertTrue(Files.isDirectory(outside));
    }

    private FlowStorage storage() {
        return new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.runtime(tempDir),
            new AssetPersistenceGate(tempDir), SERVER, transactions());
    }

    private AssetTransactionCoordinator transactions() {
        try {
            return new AssetTransactionCoordinator(tempDir.resolve("assets"), new Gson());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to open asset transaction coordinator", exception);
        }
    }

    private static GraphDocument graph(String id, long revision) {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("flow")), id);
        return new GraphDocument(new CatalogVersion(1, 0), resource, revision, BINDING, Set.of(), List.of(),
            List.of(), List.of(), List.of(), OpaqueData.of(Map.of("future", "preserve")));
    }

    private static UUID mutation(String value) {
        return UUID.fromString(value);
    }

    private static void createSymlink(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "Symbolic links are unavailable");
        } catch (IOException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }
    }
}
