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
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlowStorageCoreDuplicateCandidateTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("a".repeat(64)),
        new ContentHash("b".repeat(64)));
    private static final Set<String> INTERNAL_ASSET_DIRECTORIES = Set.of(
        ".asset-coordinator", ".transactions", ".snapshots", ".quarantine", ".durability", ".tombstones",
        ".migrations", "migration-backups");

    @TempDir
    Path tempDir;

    @Test
    void rejectsIdOnlyAndLegacyNamedCoreCandidatesBeforeSaveAndDelete() throws Exception {
        String id = "duplicate-filename";
        try (AssetTransactionCoordinator coordinator = transactions()) {
            FlowStorage storage = storage(coordinator);
            storage.saveCoreGraph(graph(id, 1), ResourceActivationState.ACTIVE,
                mutation("22222222-2222-4222-8222-222222222222"), 0L);

            Path canonical = tempDir.resolve("assets/Blueprints/Flows").resolve(id + ".json");
            Path legacyNamed = canonical.getParent().resolve("flow__" + id + ".json");
            Files.copy(canonical, legacyNamed);
            Map<String, byte[]> before = snapshot(tempDir.resolve("assets"));

            IllegalStateException saveFailure = assertThrows(IllegalStateException.class,
                () -> storage.saveCoreGraph(graph(id, 2), ResourceActivationState.ACTIVE,
                    mutation("33333333-3333-4333-8333-333333333333"), 1L));
            assertEquals("Core graph has duplicate live payloads: flow:" + id, saveFailure.getMessage());
            assertUnchanged(before, snapshot(tempDir.resolve("assets")));

            IllegalStateException deleteFailure = assertThrows(IllegalStateException.class,
                () -> storage.deleteCoreGraph("flow", id,
                    mutation("44444444-4444-4444-8444-444444444444"), 1L));
            assertEquals("Core graph has duplicate live payloads: flow:" + id, deleteFailure.getMessage());
            assertUnchanged(before, snapshot(tempDir.resolve("assets")));
        }
    }

    @Test
    void rejectsConflictingProjectPathsBeforeSaveAndDelete() throws Exception {
        String id = "duplicate-project-path";
        Path assets = tempDir.resolve("assets");
        Path first = assets.resolve("Blueprints/Flows").resolve(id + ".json");
        Path second = assets.resolve("Archive/Flows").resolve(id + ".json");
        Files.createDirectories(first.getParent());
        Files.createDirectories(second.getParent());
        byte[] encoded = encodedGraph(id, 1, mutation("55555555-5555-4555-8555-555555555555"));
        Files.write(first, encoded);
        Files.write(second, encoded);
        Files.writeString(assets.resolve("project.json"), "{\"resources\":["
            + "{\"type\":\"flow\",\"id\":\"" + id + "\",\"path\":\"Blueprints/Flows\"},"
            + "{\"type\":\"flow\",\"id\":\"" + id + "\",\"path\":\"Archive/Flows\"}]}");

        try (AssetTransactionCoordinator coordinator = projectMetadataTransactions(id, encoded)) {
            FlowStorage storage = storage(coordinator);
            Map<String, byte[]> before = snapshot(assets);

            IllegalStateException saveFailure = assertThrows(IllegalStateException.class,
                () -> storage.saveCoreGraph(graph(id, 2), ResourceActivationState.ACTIVE,
                    mutation("66666666-6666-4666-8666-666666666666"), 1L));
            assertEquals("Core graph has duplicate live payloads: flow:" + id, saveFailure.getMessage());
            assertUnchanged(before, snapshot(assets));

            IllegalStateException deleteFailure = assertThrows(IllegalStateException.class,
                () -> storage.deleteCoreGraph("flow", id,
                    mutation("77777777-7777-4777-8777-777777777777"), 1L));
            assertEquals("Core graph has duplicate live payloads: flow:" + id, deleteFailure.getMessage());
            assertUnchanged(before, snapshot(assets));
        }
    }

    @Test
    void rejectsAppliedLegacyDuplicateCoordinatorPathBeforeSaveAndDelete() throws Exception {
        String id = "duplicate-coordinator-path";
        Path assets = tempDir.resolve("assets");
        Path canonical = assets.resolve("Blueprints/Flows").resolve(id + ".json");
        Path shadow = assets.resolve("Archive/Flows").resolve(id + ".json");
        Files.createDirectories(canonical.getParent());
        Files.createDirectories(shadow.getParent());
        byte[] encoded = encodedGraph(id, 1, mutation("88888888-8888-4888-8888-888888888888"));
        Files.write(canonical, encoded);
        Files.write(shadow, encoded);
        Files.writeString(assets.resolve("project.json"), "{\"resources\":["
            + "{\"type\":\"flow\",\"id\":\"" + id + "\",\"path\":\"Blueprints/Flows\"}]}");

        try (AssetTransactionCoordinator coordinator = adoptedTransactions(id, encoded,
            "88888888-8888-4888-8888-888888888888")) {
            FlowStorage storage = storage(coordinator);
            Map<String, byte[]> before = snapshot(assets);

            IllegalStateException saveFailure = assertThrows(IllegalStateException.class,
                () -> storage.saveCoreGraph(graph(id, 2), ResourceActivationState.ACTIVE,
                    mutation("99999999-9999-4999-8999-999999999999"), 1L));
            assertEquals("Core graph has duplicate live payloads: flow:" + id, saveFailure.getMessage());
            assertUnchanged(before, snapshot(assets));

            IllegalStateException deleteFailure = assertThrows(IllegalStateException.class,
                () -> storage.deleteCoreGraph("flow", id,
                    mutation("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), 1L));
            assertEquals("Core graph has duplicate live payloads: flow:" + id, deleteFailure.getMessage());
            assertUnchanged(before, snapshot(assets));
        }
    }

    private FlowStorage storage(AssetTransactionCoordinator coordinator) {
        return new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.runtime(tempDir),
            new AssetPersistenceGate(tempDir), SERVER, coordinator);
    }

    private AssetTransactionCoordinator transactions() {
        try {
            return AssetTransactionCoordinator.open(tempDir.resolve("assets"), new Gson());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to open asset transaction coordinator", exception);
        }
    }

    private AssetTransactionCoordinator projectMetadataTransactions(String id, byte[] encoded) {
        return adoptedTransactions(id, encoded, "55555555-5555-4555-8555-555555555555", "project-shadow:flow");
    }

    private AssetTransactionCoordinator adoptedTransactions(String id, byte[] encoded, String mutationId) {
        return adoptedTransactions(id, encoded, mutationId, "legacy-duplicate:flow");
    }

    private AssetTransactionCoordinator adoptedTransactions(String id, byte[] encoded, String mutationId,
                                                              String duplicateType) {
        try {
            Path assets = tempDir.resolve("assets");
            String firstPath = "Blueprints/Flows/" + id + ".json";
            String secondPath = "Archive/Flows/" + id + ".json";
            return AssetTransactionCoordinator.adoptExisting(assets, new Gson(),
                new AssetTransactionCoordinator.AdoptionInventory("flow-storage-core-duplicate-candidate-test",
                    Files.readString(assets.resolve("project.json")), List.of(
                        adopted("flow", id, firstPath,
                            mutationId, encoded),
                        adopted(duplicateType, secondPath, secondPath,
                            mutationId, encoded))));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to adopt test asset coordinator state", exception);
        }
    }

    private AssetTransactionCoordinator.AdoptedAsset adopted(String type, String id, String path,
                                                              String mutationId, byte[] content) {
        return new AssetTransactionCoordinator.AdoptedAsset(new AssetTransactionCoordinator.AssetKey(type, id),
            Path.of(path), new AssetTransactionCoordinator.Live(1L, StorageSafety.sha256(content)),
            new AssetTransactionCoordinator.AssetMutationId(mutationId), content, null);
    }

    private static GraphDocument graph(String id, long revision) {
        return new GraphDocument(new CatalogVersion(1, 0), resource(id), revision, BINDING, Set.of(), List.of(),
            List.of(), List.of(), List.of(), OpaqueData.of(Map.of("future", "preserve")));
    }

    private static byte[] encodedGraph(String id, long revision, UUID mutationId) {
        ServerResourceLocator resource = resource(id);
        return new CoreGraphStorageBoundary().encode(graph(id, revision),
            new CoreGraphStorageBoundary.AssetMetadata("flow", revision, mutationId), resource);
    }

    private static ServerResourceLocator resource(String id) {
        return new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("flow")), id);
    }

    private static UUID mutation(String value) {
        return UUID.fromString(value);
    }

    private static Map<String, byte[]> snapshot(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return Map.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> !isInternalAssetPath(root, path))
                .sorted()
                .collect(Collectors.toMap(path -> root.relativize(path).toString(), path -> read(path),
                    (first, second) -> first, LinkedHashMap::new));
        }
    }

    private static boolean isInternalAssetPath(Path root, Path path) {
        Path relative = root.relativize(path);
        return relative.getNameCount() == 0 || relative.toString().equals("project.json")
            || INTERNAL_ASSET_DIRECTORIES.contains(relative.getName(0).toString());
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read snapshot file: " + path, exception);
        }
    }

    private static void assertUnchanged(Map<String, byte[]> before, Map<String, byte[]> after) {
        assertEquals(before.keySet(), after.keySet());
        before.forEach((path, content) -> assertArrayEquals(content, after.get(path), path));
    }
}
