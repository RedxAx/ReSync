package restudio.resync.resources;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.FlowStorage;
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
import restudio.resync.flow.protocol.ResourcePresentationIntent;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.AssetTransactionCoordinator.ProjectDelta;
import restudio.resync.storage.AssetTransactionCoordinator.TransactionRequest;
import restudio.resync.storage.CanonicalProjectMetadataFixture;
import restudio.resync.storage.ProjectMetadataLineage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregateResourceCreateStorageTest {
    private static final Gson GSON = new Gson();
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("a".repeat(64)),
        new ContentHash("b".repeat(64)));

    @TempDir
    Path tempDir;

    @Test
    void genericCreateCommitsExactPayloadPresentationAndMetadataLineageTogether() throws Exception {
        Path assets = tempDir.resolve("assets");
        UUID mutationId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        ResourcePresentationIntent presentation = new ResourcePresentationIntent("Welcome", "Content/Text/welcome.json", 7);
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, GSON)) {
            AssetTransactionCoordinator.Snapshot initial = coordinator.read(snapshot -> snapshot);
            UUID seedMutation = UUID.randomUUID();
            List<ProjectDelta> seedDeltas = List.of(
                ProjectDelta.set(List.of("serverId"), new JsonPrimitive(SERVER.canonicalText())));
            coordinator.transact(new TransactionRequest(seedMutation, initial.project(),
                List.of(ProjectMetadataLineage.writer(assets, GSON).write(initial, seedDeltas, seedMutation)), seedDeltas));
            try (JsonAssetStore<TestResource> store = jsonStore(assets, coordinator)) {
                JsonAssetStore.AggregateCreateResult created = store.create(new TestResource("welcome", "Hello"), Map.of(),
                    mutationId, 0L, presentation);

                assertEquals(1L, created.primaryStamp().revision());
                assertEquals(mutationId, created.primaryStamp().mutationId());
                assertEquals(mutationId, created.projectMetadataStamp().mutationId());
                assertEquals(2L, created.projectMetadataStamp().revision());
                assertEquals("Hello", JsonParser.parseString(created.canonicalPayloadJson()).getAsJsonObject()
                    .get("value").getAsString());
                assertTrue(Files.isRegularFile(assets.resolve("Content/Text/welcome.json")));
                JsonObject metadata = JsonParser.parseString(created.canonicalProjectMetadataJson()).getAsJsonObject();
                JsonObject resource = metadata.getAsJsonArray("resources").get(0).getAsJsonObject();
                assertEquals("text_template", resource.get("type").getAsString());
                assertEquals("welcome", resource.get("id").getAsString());
                assertEquals("Welcome", resource.get("displayName").getAsString());
                assertEquals("Content/Text/welcome.json", resource.get("path").getAsString());
                assertEquals(7, resource.get("sortOrder").getAsInt());
                assertEquals(2L, coordinator.read(snapshot -> snapshot.rootSequence()).longValue());

                JsonAssetStore.AggregateCreateResult replay = store.create(new TestResource("welcome", "Hello"), Map.of(),
                    mutationId, 0L, presentation);
                assertEquals(created.primaryStamp(), replay.primaryStamp());
                assertEquals(created.projectMetadataStamp(), replay.projectMetadataStamp());
                assertEquals(created.canonicalPayloadJson(), replay.canonicalPayloadJson());
                assertEquals(created.canonicalProjectMetadataJson(), replay.canonicalProjectMetadataJson());
                assertTrue(replay.replayed());
                assertEquals(2L, coordinator.read(snapshot -> snapshot.rootSequence()).longValue());
                assertThrows(IllegalStateException.class, () -> store.create(new TestResource("welcome", "Hello"), Map.of(),
                    mutationId, 0L, new ResourcePresentationIntent("Moved", "Content/Text/welcome.json", 7)));
            }
        }
    }

    @Test
    void aggregateCreateClassifiesDeletedPathOwnerConflictBeforeAssetWrite() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path path = Path.of("Customization/Chat/asd.json");
        UUID oldCreate = UUID.fromString("44444444-4444-4444-8444-444444444444");
        UUID oldDelete = UUID.fromString("55555555-5555-4555-8555-555555555555");
        UUID attemptedCreate = UUID.fromString("66666666-6666-4666-8666-666666666666");
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, GSON)) {
            CanonicalProjectMetadataFixture.seed(coordinator);
            AssetTransactionCoordinator.AssetKey oldKey = new AssetTransactionCoordinator.AssetKey("flow", "asd");
            AssetTransactionCoordinator.TransactionResult created = coordinator.transact(
                new AssetTransactionCoordinator.TransactionRequest(oldCreate,
                    coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                    List.of(AssetTransactionCoordinator.AssetDelta.write(oldKey, path,
                        AssetTransactionCoordinator.Missing.INSTANCE, "old".getBytes(StandardCharsets.UTF_8))), List.of()));
            coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(oldDelete,
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                List.of(AssetTransactionCoordinator.AssetDelta.delete(oldKey, path, created.states().get(oldKey))), List.of()));
            long sequence = coordinator.read(AssetTransactionCoordinator.Snapshot::rootSequence);
            try (JsonAssetStore<TestResource> store = jsonStore(assets, coordinator)) {
                JsonAssetStore.PreCommitConflictException failure = assertThrows(JsonAssetStore.PreCommitConflictException.class,
                    () -> store.create(new TestResource("asd", "new"), Map.of(), attemptedCreate, 0L,
                        new ResourcePresentationIntent("Chat", "Customization/Chat/asd.json", 1)));

                assertTrue(failure.getCause() instanceof AssetTransactionCoordinator.MutationConflictException);
                assertEquals("Asset path is already bound to a different key: Customization/Chat/asd.json",
                    failure.getMessage());
                assertEquals(sequence, coordinator.read(AssetTransactionCoordinator.Snapshot::rootSequence));
                assertFalse(Files.exists(assets.resolve(path)));
                assertTrue(coordinator.read(snapshot -> snapshot.state(oldKey).orElseThrow()
                    instanceof AssetTransactionCoordinator.Deleted).booleanValue());
            }
        }
    }

    @Test
    void aggregateCreateDoesNotClassifyCommittedMutationIdCollisionAsPathConflict() throws Exception {
        Path assets = tempDir.resolve("assets");
        UUID mutationId = UUID.fromString("77777777-7777-4777-8777-777777777777");
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, GSON)) {
            AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("text_template", "first");
            coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(mutationId,
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                List.of(AssetTransactionCoordinator.AssetDelta.write(key, Path.of("Text/first.json"),
                    AssetTransactionCoordinator.Missing.INSTANCE, "first".getBytes(StandardCharsets.UTF_8))), List.of()));

            AssetTransactionCoordinator.MutationConflictException failure = assertThrows(
                AssetTransactionCoordinator.MutationConflictException.class,
                () -> coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(mutationId,
                    coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                    List.of(AssetTransactionCoordinator.AssetDelta.write(key, Path.of("Text/second.json"),
                        AssetTransactionCoordinator.Missing.INSTANCE, "second".getBytes(StandardCharsets.UTF_8))), List.of())));

            assertFalse(failure instanceof AssetTransactionCoordinator.AssetPathConflictException);
            assertEquals("Mutation ID was already committed with a different asset transaction: " + mutationId,
                failure.getMessage());
            assertFalse(Files.exists(assets.resolve("Text/second.json")));
        }
    }

    @Test
    void coreCreateDefersPublicationAndReturnsSameTransactionMetadata() {
        FlowStorage storage = flowStorage();
        FlowStorage.ResourceIdentity metadataBefore = storage.readProjectMetadataIdentity(
            storage.projectMetadataResourceId());
        long expectedMetadataRevision = metadataBefore == null ? 1L : metadataBefore.revision() + 1L;
        AtomicInteger publications = new AtomicInteger();
        storage.setGraphChangeListener(ignored -> publications.incrementAndGet());
        UUID mutationId = UUID.fromString("33333333-3333-4333-8333-333333333333");
        ResourcePresentationIntent presentation = new ResourcePresentationIntent("Main Flow",
            "Blueprints/Custom/main.json", 4);

        FlowStorage.CoreAggregateCreate created = storage.createCoreGraph(graph("flow", "main"),
            ResourceActivationState.ACTIVE, mutationId, 0L, presentation);

        assertEquals(0, publications.get());
        assertEquals(mutationId.toString(), created.primary().envelope().assetMutationId());
        assertEquals(mutationId.toString(), created.projectMetadataIdentity().mutationId());
        assertEquals(expectedMetadataRevision, created.projectMetadataIdentity().revision());
        assertEquals(1L, created.primary().envelope().assetRevision());
        assertTrue(Files.isRegularFile(tempDir.resolve("assets/Blueprints/Custom/main.json")));
        JsonObject metadata = JsonParser.parseString(created.canonicalProjectMetadataJson()).getAsJsonObject();
        JsonObject resource = metadata.getAsJsonArray("resources").asList().stream()
            .map(element -> element.getAsJsonObject()).filter(element -> "flow".equals(element.get("type").getAsString())
                && "main".equals(element.get("id").getAsString())).findFirst().orElseThrow();
        assertEquals("Main Flow", resource.get("displayName").getAsString());
        assertEquals("Blueprints/Custom/main.json", resource.get("path").getAsString());
        assertEquals(4, resource.get("sortOrder").getAsInt());

        FlowStorage.CoreAggregateCreate replay = storage.createCoreGraph(graph("flow", "main"),
            ResourceActivationState.ACTIVE, mutationId, 0L, presentation);
        assertTrue(replay.replayed());
        assertEquals(created.primary().envelope(), replay.primary().envelope());
        assertEquals(created.projectMetadataIdentity(), replay.projectMetadataIdentity());
        assertEquals(0, publications.get());
        storage.publishCoreGraph("main");
        assertEquals(1, publications.get());
        assertFalse(created.projectMetadataIdentity().deleted());
    }

    private JsonAssetStore<TestResource> jsonStore(Path assets, AssetTransactionCoordinator coordinator) {
        return new JsonAssetStore<>(assets, tempDir.resolve("legacy"), "text_template", "Text", TestResource::fromJson,
            TestResource::toJson, TestResource::id, null, LegacyRuntimeActivationGate.runtime(tempDir), coordinator,
            () -> true, () -> () -> {
            }, (value, existing, serialized) -> JsonAssetStore.mergePayload(existing, serialized, serialized.keySet()),
            ProjectMetadataLineage.writer(assets, GSON));
    }

    private FlowStorage flowStorage() {
        return new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.runtime(tempDir),
            new AssetPersistenceGate(tempDir), SERVER, coordinator());
    }

    private AssetTransactionCoordinator coordinator() {
        try {
            return new AssetTransactionCoordinator(tempDir.resolve("assets"), GSON);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to open asset transaction coordinator", exception);
        }
    }

    private GraphDocument graph(String type, String id) {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(type)), id);
        return new GraphDocument(new CatalogVersion(1, 0), resource, 1L, BINDING, Set.of(), List.of(), List.of(),
            List.of(), List.of(), OpaqueData.empty());
    }

    private record TestResource(String id, String value) {
        private static TestResource fromJson(String json) {
            return GSON.fromJson(json, TestResource.class);
        }

        private String toJson() {
            return GSON.toJson(this);
        }
    }
}
