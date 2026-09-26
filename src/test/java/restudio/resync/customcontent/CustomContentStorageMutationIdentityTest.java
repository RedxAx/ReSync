package restudio.resync.customcontent;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.CustomContentGraphAdapter;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.resync.flow.ResourceRevisionConflictException;
import restudio.resync.flow.protocol.ResourcePresentationIntent;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceMutationStamp;
import restudio.resync.resources.JsonAssetStore;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.CanonicalProjectMetadataFixture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomContentStorageMutationIdentityTest {
    private static final UUID SAVE_MUTATION = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SECOND_SAVE_MUTATION = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID DELETE_MUTATION = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @TempDir
    Path tempDir;

    @Test
    void exactSaveRetainsEditorPassthroughPinIdentity() throws Exception {
        AssetPersistenceGate gate = new AssetPersistenceGate(tempDir);
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(tempDir.resolve("assets"), new Gson())) {
            CanonicalProjectMetadataFixture.seed(coordinator);
            CustomContentStorage storage = new CustomContentStorage(tempDir.toFile(), gate, coordinator);
            try {
                CustomContentDefinition definition = definition("durable_item", "Durable Item");
                FlowGraph.EditorPassthrough passthrough = new FlowGraph.EditorPassthrough("source", "nearby_player");
                passthrough.setInputPinId("nearby_player");
                passthrough.setInputPinDisplayName("Nearby Player");
                definition.getGraph().getEditorPassthroughs().add(passthrough);
                String expectedHash = ResourcePayloadCodecs.json().hashPayload(
                    new Gson().fromJson(FlowSerializer.serializeCustomContent(definition), Map.class)).canonicalText();
                byte[] metadataBefore = Files.readAllBytes(tempDir.resolve("assets/project.json"));

                assertThrows(FlowResourceAdapter.PreCommitRejection.class,
                    () -> storage.save(definition, SAVE_MUTATION, 0L, "0".repeat(64)));
                assertNull(storage.readMutationStamp("durable_item"));
                assertArrayEquals(metadataBefore, Files.readAllBytes(tempDir.resolve("assets/project.json")));

                storage.save(definition, SAVE_MUTATION, 0L, expectedHash);

                assertEquals(expectedHash, storage.readMutationStamp("durable_item").payloadHash());
                FlowGraph.EditorPassthrough readback = storage.get("durable_item").getGraph().getEditorPassthroughs().getFirst();
                assertEquals("nearby_player", readback.getInputPinId());
                assertEquals("Nearby Player", readback.getInputPinDisplayName());
            } finally {
                gate.quiesce();
                storage.close();
            }
        }
    }

    @Test
    void aggregateCreateReusesDeletedContentIdentityWithRetainedRevision() throws Exception {
        AssetPersistenceGate gate = new AssetPersistenceGate(tempDir);
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(tempDir.resolve("assets"), new Gson())) {
            CanonicalProjectMetadataFixture.seed(coordinator);
            CustomContentStorage storage = new CustomContentStorage(tempDir.toFile(), gate, coordinator);
            try {
                CustomContentDefinition definition = definition("durable_item", "Durable Item");
                storage.save(definition, SAVE_MUTATION, 0L);
                FlowResourceMutationStamp original = storage.readMutationStamp("durable_item");
                storage.delete("durable_item", DELETE_MUTATION, 1L);
                ResourcePresentationIntent presentation = new ResourcePresentationIntent("Recreated Item",
                    "Content/Recreated/durable_item.json", 2);

                JsonAssetStore.AggregateCreateResult created = storage.create(definition, SECOND_SAVE_MUTATION, 2L,
                    presentation, original.payloadHash());

                assertEquals(3L, created.primaryStamp().revision());
                assertEquals(original.payloadHash(), created.primaryStamp().payloadHash());
                assertEquals(SECOND_SAVE_MUTATION, storage.readMutationStamp("durable_item").mutationId());
                assertNotNull(storage.get("durable_item"));
                assertFalse(Files.exists(tempDir.resolve("assets/.tombstones/custom_content/durable_item.json")));
                JsonAssetStore.AggregateCreateResult replay = storage.create(definition, SECOND_SAVE_MUTATION, 2L,
                    presentation, original.payloadHash());
                assertTrue(replay.replayed());
                assertEquals(created.primaryStamp(), replay.primaryStamp());
            } finally {
                gate.quiesce();
                storage.close();
            }
        }
    }

    @Test
    void exactSaveAndDeleteIdentitySurvivesRetriesAndReopen() throws Exception {
        AssetPersistenceGate gate = new AssetPersistenceGate(tempDir);
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(tempDir.resolve("assets"), new Gson())) {
            CanonicalProjectMetadataFixture.seed(coordinator);
            CustomContentStorage storage = new CustomContentStorage(tempDir.toFile(), gate, coordinator);
            CustomContentDefinition firstDefinition = definition("durable_item", "Durable Item");

            storage.save(firstDefinition, SAVE_MUTATION, 0L);
            FlowResourceMutationStamp first = storage.readMutationStamp("durable_item");

            assertNotNull(first);
            assertEquals("custom_content", first.type());
            assertEquals("durable_item", first.id());
            assertEquals(1L, first.revision());
            assertEquals(SAVE_MUTATION, first.mutationId());
            assertFalse(first.deleted());
            assertTrue(first.payloadHash().matches("[0-9a-f]{64}"));
            assertTrue(storage.supportsAuthoritativeMutationIdentity());

            storage.save(firstDefinition, SAVE_MUTATION, 0L);
            assertEquals(first, storage.readMutationStamp("durable_item"));

            CustomContentDefinition changedDefinition = definition("durable_item", "Changed Item");
            IllegalStateException changedPayload = assertThrows(IllegalStateException.class,
                () -> storage.save(changedDefinition, SAVE_MUTATION, 0L));
            assertTrue(changedPayload.getMessage().contains("durable_item"));

            assertThrows(ResourceRevisionConflictException.class,
                () -> storage.save(changedDefinition, SECOND_SAVE_MUTATION, 0L));

            storage.save(changedDefinition, SECOND_SAVE_MUTATION, 1L);
            FlowResourceMutationStamp second = storage.readMutationStamp("durable_item");
            assertEquals(2L, second.revision());
            assertEquals(SECOND_SAVE_MUTATION, second.mutationId());
            assertNotEquals(first.payloadHash(), second.payloadHash());

            IllegalStateException oppositeOperation = assertThrows(IllegalStateException.class,
                () -> storage.delete("durable_item", SECOND_SAVE_MUTATION, 2L));
            assertTrue(oppositeOperation.getMessage().contains("durable_item"));

            storage.delete("durable_item", DELETE_MUTATION, 2L);
            FlowResourceMutationStamp deleted = storage.readMutationStamp("durable_item");
            assertNotNull(deleted);
            assertEquals("custom_content", deleted.type());
            assertEquals("durable_item", deleted.id());
            assertEquals(3L, deleted.revision());
            assertEquals(DELETE_MUTATION, deleted.mutationId());
            assertTrue(deleted.deleted());
            assertTrue(deleted.payloadHash().matches("[0-9a-f]{64}"));
            assertNull(storage.get("durable_item"));

            storage.delete("durable_item", DELETE_MUTATION, 2L);
            assertEquals(deleted, storage.readMutationStamp("durable_item"));

            gate.quiesce();
            storage.close();
            gate.resume();
            CustomContentStorage reopened = new CustomContentStorage(tempDir.toFile(), gate, coordinator);
            assertEquals(deleted, reopened.readMutationStamp("durable_item"));
            assertNull(reopened.get("durable_item"));
            gate.quiesce();
            reopened.close();
        }
    }

    @Test
    void missingMutationStampIsExplicitlyAbsent() throws Exception {
        AssetPersistenceGate gate = new AssetPersistenceGate(tempDir);
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(tempDir.resolve("assets"), new Gson())) {
            CanonicalProjectMetadataFixture.seed(coordinator);
            CustomContentStorage storage = new CustomContentStorage(tempDir.toFile(), gate, coordinator);
            assertNull(storage.readMutationStamp("missing"));
            gate.quiesce();
            storage.close();
        }
    }

    @Test
    void recoveryAcceptsOnlyTheHistoricalLossyLegacyProjectionWhenCoreGraphMatches() throws Exception {
        AssetPersistenceGate gate = new AssetPersistenceGate(tempDir);
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(tempDir.resolve("assets"), new Gson())) {
            CanonicalProjectMetadataFixture.seed(coordinator);
            CustomContentStorage storage = new CustomContentStorage(tempDir.toFile(), gate, coordinator);
            String requestedJson = """
                {"id":"block","displayName":"Block","graph":{"id":"content.block.block","nodes":{"source":{"type":"restudio.resync:custom_content.block","inputValues":{},"instanceId":"source","contentInputs":{"__flow_branches":["interact"]}},"target":{"type":"restudio.resync:player.player_message","inputValues":{},"instanceId":"target"}},"connections":[{"connectionId":"0c9dfc63-de2a-45c5-b021-0ce512829449","sourceNodeId":"source","sourcePinId":"nearby_player","targetNodeId":"target","targetPinId":"flow"}],"localVariables":[],"functionInputs":[],"functionOutputs":[],"contentCoreGraph":{"catalogBinding":{"generation":84}}}}
                """;
            String actualJson = """
                {"id":"block","displayName":"Block","graph":{"id":"content.block.block","nodes":{"source":{"type":"restudio.resync:custom_content.block","inputValues":{}},"target":{"type":"restudio.resync:player.player_message","inputValues":{}}},"connections":[{"sourceNodeId":"source","targetNodeId":"target"}],"localVariables":[],"functionInputs":[],"functionOutputs":[],"contentCoreGraph":{"catalogBinding":{"generation":84}}}}
                """;
            CustomContentDefinition previous = FlowSerializer.deserializeCustomContent(requestedJson);
            CustomContentDefinition requested = FlowSerializer.deserializeCustomContent(requestedJson);
            CustomContentDefinition actual = FlowSerializer.deserializeCustomContent(actualJson);

            assertTrue(storage.matchesCommittedPayloadRecovery(previous, requested, actual));
            actual.getGraph().getOpaqueProperties().put("contentCoreGraph",
                JsonParser.parseString("{\"catalogBinding\":{\"generation\":85}}"));
            assertFalse(storage.matchesCommittedPayloadRecovery(previous, requested, actual));
            gate.quiesce();
            storage.close();
        }
    }

    private static CustomContentDefinition definition(String id, String name) {
        return CustomContentGraphAdapter.toDefinition(CustomContentGraphAdapter.createContentGraph(id, "item", name));
    }
}
