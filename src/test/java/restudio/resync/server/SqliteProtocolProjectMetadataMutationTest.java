package restudio.resync.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.ScoreboardDefinition;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.ConnectionState;
import restudio.resync.core.Session;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.protocol.CanonicalPayload;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ResourceActivateRequest;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourceCreateRequest;
import restudio.resync.flow.protocol.ResourceDeleteRequest;
import restudio.resync.flow.protocol.ResourceDocument;
import restudio.resync.flow.protocol.ResourceOperation;
import restudio.resync.flow.protocol.ResourceSaveRequest;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.modules.flow.CoreResourceMutationTransition;
import restudio.resync.modules.flow.FlowResourceCommitListener;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceMutationListener;
import restudio.resync.modules.flow.FlowResourceMutationStamp;
import restudio.resync.modules.flow.FlowResourcePacketRouter;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.protocol.FrameSender;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.resources.JsonAssetStore;
import restudio.resync.security.ClientIdentity;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.ProjectMetadataLineage;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.worldgen.WorldGenProjectStorage;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.data.WorldGenSerializer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteProtocolProjectMetadataMutationTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("e65887a4-ea27-4c55-bae2-e1c8d92da433"));
    private static final CatalogBinding GRAPH_BINDING = new CatalogBinding(1, new ContentHash("a".repeat(64)),
        new ContentHash("b".repeat(64)));
    private static final ContractRef<ResourceTypeId> TYPE = ContractRef.of(new OwnerId("restudio.resync"),
        new ResourceTypeId(ReSyncResourceCatalog.PROJECT_METADATA));

    @Test
    void rejectsArbitraryProjectMetadataIdsBeforeReceiptAdmissionAndStorage(@TempDir Path directory) throws Exception {
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(directory.resolve("assets"), new Gson())) {
            FlowStorage storage = storage(directory, coordinator);
            FlowResourceRegistry registry = registry(storage);
            Path metadataFile = directory.resolve("assets/project.json");
            boolean metadataExists = Files.exists(metadataFile);
            byte[] metadataBefore = metadataExists ? Files.readAllBytes(metadataFile) : null;
            try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
                ProtocolEnvelopeDispatchResult save = mutate(authority, save(resource("other"),
                    "{\"serverId\":\"project\",\"resources\":[]}",
                    UUID.fromString("55555555-5555-4555-8555-555555555555"), 0L));
                ProtocolEnvelopeDispatchResult delete = mutate(authority, delete(resource("other"),
                    UUID.fromString("66666666-6666-4666-8666-666666666666"), 0L));
                ProtocolEnvelopeDispatchResult create = mutate(authority, create(resource("other"),
                    "{\"serverId\":\"project\",\"resources\":[]}",
                    UUID.fromString("77777777-7777-4777-8777-777777777777")));

                assertEquals("PROTO.RESOURCE_OPERATION_FAILED", save.code());
                assertEquals("PROTO.RESOURCE_OPERATION_FAILED", delete.code());
                assertEquals("PROTO.RESOURCE_OPERATION_FAILED", create.code());
                assertFalse(save.handled());
                assertFalse(delete.handled());
                assertFalse(create.handled());
                assertEquals(List.of(), registry.mutationAdmission().activeKeys());
                assertEquals(0L, mutationReceiptCount(directory.resolve("resource.db")));
                assertNull(storage.getProjectMetadata(SERVER.canonicalText()));
                assertEquals(List.of(), storage.listProjectMetadataIds());
            }
            assertEquals(metadataExists, Files.exists(metadataFile));
            if (metadataExists) {
                assertArrayEquals(metadataBefore, Files.readAllBytes(metadataFile));
            }
            assertEquals(0L, mutationReceiptCount(directory.resolve("resource.db")));
        }
    }

    @Test
    void exactProtocolSaveSettlesCanonicalAliasHashAndIdentity(@TempDir Path directory) throws Exception {
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(directory.resolve("assets"), new Gson())) {
            FlowStorage storage = storage(directory, coordinator);
            FlowResourceRegistry registry = registry(storage);
            storage.saveProjectMetadata("{\"serverId\":\"project\",\"resources\":[]}",
                UUID.fromString("00000000-0000-4000-8000-000000000000"), 0L);
            String alias = "{\"serverId\":\"project\",\"resources\":[]}";
            String canonical = storage.normalizeProjectMetadataPayload(alias);
            ContentHash expectedHash = hash(canonical);
            ServerResourceLocator resource = resource();
            ProtocolEnvelope<Map<String, Object>> request = save(resource, alias,
                UUID.fromString("11111111-1111-4111-8111-111111111111"), 1L);

            try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ProtocolEnvelopeDispatchResult result = mutate(authority, request);
            assertTrue(result.handled(), result.code() + ": " + result.message());
            assertEquals(ProtocolEnvelope.Kind.ACK, result.response().kind());
            assertNotEquals(request.payloadHash(), expectedHash);
            assertEquals(expectedHash, result.response().payloadHash());
                ProtocolBody.ResourceDocumentResponse body = assertInstanceOf(ProtocolBody.ResourceDocumentResponse.class,
                    result.response().body());
                assertEquals(SERVER, body.document().resource().serverId());
                assertEquals(SERVER.canonicalText(), body.document().resource().id());
                Map<?, ?> payload = assertInstanceOf(Map.class, body.document().payload());
                assertEquals(SERVER.canonicalText(), payload.get("serverId"));
                assertEquals(expectedHash, body.document().payloadHash());
                assertEquals(List.of(SERVER.canonicalText()), storage.listProjectMetadataIds());
                assertEquals(SERVER.canonicalText(), JsonParser.parseString(
                    Files.readString(directory.resolve("assets/project.json"))).getAsJsonObject().get("serverId").getAsString());
            }
        }
    }

    @Test
    void exactProtocolActivationCanonicalizesProjectMetadataAlias(@TempDir Path directory) throws Exception {
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(directory.resolve("assets"), new Gson())) {
            FlowStorage storage = storage(directory, coordinator);
            FlowResourceRegistry registry = registry(storage);
            storage.saveProjectMetadata("{\"serverId\":\"project\",\"resources\":[]}",
                UUID.fromString("88888888-8888-4888-8888-888888888888"), 0L);
            ProtocolEnvelope<Map<String, Object>> request = activate(resource(), ResourceActivationState.INACTIVE,
                UUID.fromString("99999999-9999-4999-8999-999999999999"), 1L);

            try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
                ProtocolEnvelopeDispatchResult result = mutate(authority, request);

                assertTrue(result.handled(), result.code() + ": " + result.message());
                ResourceDocument<Map<String, Object>> document = document(result);
                assertEquals(SERVER.canonicalText(), document.resource().id());
                assertEquals(ResourceActivationState.INACTIVE, document.activationState());
                assertEquals(2L, document.revision());
            }
        }
    }

    @Test
    void exactProtocolListReturnsCanonicalProjectMetadataDocument(@TempDir Path directory) throws Exception {
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(directory.resolve("assets"), new Gson())) {
            FlowStorage storage = storage(directory, coordinator);
            FlowResourceRegistry registry = registry(storage);
            UUID mutationId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
            storage.saveProjectMetadata("{\"serverId\":\"project\",\"resources\":[]}", mutationId, 0L);

            try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
                List<ResourceDocument<Map<String, Object>>> documents = authority.list(SERVER, TYPE, "");

                assertEquals(1, documents.size());
                ResourceDocument<Map<String, Object>> document = documents.getFirst();
                assertEquals(SERVER, document.resource().serverId());
                assertEquals(SERVER.canonicalText(), document.resource().id());
                assertEquals(1L, document.revision());
                assertEquals(mutationId, document.mutationId());
                assertEquals(hash(storage.normalizeProjectMetadataPayload(
                    "{\"serverId\":\"project\",\"resources\":[]}")), document.payloadHash());
                assertEquals(SERVER.canonicalText(), document.payload().get("serverId"));
            }
        }
    }

    @Test
    void exactProtocolAdoptsCoordinatorProvenProjectMetadataNormalization(@TempDir Path directory) throws Exception {
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(directory.resolve("assets"), new Gson())) {
            FlowStorage storage = storage(directory, coordinator);
            FlowResourceRegistry registry = registry(storage);
            UUID baselineMutation = UUID.fromString("abababab-abab-4bab-8bab-abababababab");
            storage.saveProjectMetadata("{\"serverId\":\"project\"}", baselineMutation, 0L);

            try (SqliteProtocolResourceMutationAuthority ignored = authority(registry, directory)) {
            }

            UUID normalizationMutation = UUID.fromString("cdcdcdcd-cdcd-4dcd-8dcd-cdcdcdcdcdcd");
            storage.saveProjectMetadata("{\"serverId\":\"project\",\"folders\":[],\"resources\":[]}",
                normalizationMutation, 1L);

            try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
                List<ResourceDocument<Map<String, Object>>> documents = authority.list(SERVER, TYPE, "");

                assertEquals(1, documents.size());
                assertEquals(2L, documents.getFirst().revision());
                assertEquals(normalizationMutation, documents.getFirst().mutationId());
                assertEquals(List.of(), documents.getFirst().payload().get("folders"));
                assertEquals(List.of(), documents.getFirst().payload().get("resources"));
            }
        }
    }

    @Test
    void warmMetadataReadRejectsProjectChangeWithoutLineage(@TempDir Path directory) throws Exception {
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(directory.resolve("assets"), new Gson())) {
            FlowStorage storage = storage(directory, coordinator);
            storage.saveProjectMetadata("{\"serverId\":\"project\",\"resources\":[]}", UUID.randomUUID(), 0L);
            try (SqliteProtocolResourceMutationAuthority authority = authority(registry(storage), directory)) {
                assertEquals(1L, authority.list(SERVER, TYPE, "").getFirst().revision());
                FlowStorage.ProjectMetadataObservation baseline = storage.readProjectMetadataObservation(SERVER.canonicalText());
                AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(current -> current);
                coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(UUID.randomUUID(), snapshot.project(),
                    List.of(), List.of(AssetTransactionCoordinator.ProjectDelta.set(List.of("unknown"), new JsonPrimitive("drift")))));
                FlowStorage.ProjectMetadataObservation changed = storage.readProjectMetadataObservation(SERVER.canonicalText());
                assertEquals(baseline.identity(), changed.identity());
                assertNotEquals(baseline.projectHash(), changed.projectHash());
                assertThrows(IllegalStateException.class, () -> authority.list(SERVER, TYPE, ""));
            }
        }
    }

    @Test
    void exactProtocolListRepairsForwardMetadataDriftFromAppliedCoreReceipt(@TempDir Path directory) throws Exception {
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(directory.resolve("assets"), new Gson())) {
            FlowStorage storage = storage(directory, coordinator);
            FlowResourceRegistry registry = registry(storage);
            UUID initialMetadataMutation = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
            String initialMetadata = storage.normalizeProjectMetadataPayload(
                "{\"serverId\":\"project\",\"resources\":[],\"unknown\":{\"keep\":true}}");
            storage.saveProjectMetadata(initialMetadata, initialMetadataMutation, 0L);
            ServerResourceLocator graph = new ServerResourceLocator(SERVER,
                ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId("flow")), "metadata-drift");
            UUID graphMutation = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
            CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
            CoreGraphResourceAuthority core = new FlowStorageCoreGraphResourceAuthority(storage, SERVER);
            List<CoreResourceMutationTransition> transitions = new ArrayList<>();
            registry.addCoreMutationListener(transitions::add);

            try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory, core)) {
                ProtocolEnvelopeDispatchResult result = mutate(authority,
                    createCore(graph, graphMutation, boundary, core.activeCatalogBinding().orElseThrow()));
                assertTrue(result.handled(), result.code() + ": " + result.message());
            }
            assertEquals(1, transitions.size());
            CoreResourceMutationTransition transition = transitions.getFirst();
            assertEquals(graph, transition.locator());
            assertEquals(1L, transition.revision());
            assertEquals(graphMutation, transition.mutationId());
            assertFalse(transition.deleted());
            assertEquals(ResourceActivationState.ACTIVE, transition.activationState());
            CoreGraphStorageBoundary.Decoded committed = core.load(graph).orElseThrow();

            String metadataResource = new ServerResourceLocator(SERVER, TYPE, SERVER.canonicalText()).canonicalText();
            ContentHash initialHash = hash(initialMetadata);
            Map<String, Object> initialPayloadValue = new Gson().fromJson(initialMetadata, Map.class);
            String initialPayload = ResourcePayloadCodecs.json().canonicalInput(
                ResourcePayloadCodecs.json().canonicalize(initialPayloadValue).value());
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("resource.db"));
                 var update = connection.prepareStatement("""
                     UPDATE resource_mutation_state
                     SET revision = ?, mutation_id = ?, payload_hash = ?, deleted = 0, payload = ?, activation_state = 'active'
                     WHERE resource = ?
                     """)) {
                update.setLong(1, 1L);
                update.setString(2, initialMetadataMutation.toString());
                update.setString(3, initialHash.canonicalText());
                update.setString(4, initialPayload);
                update.setString(5, metadataResource);
                assertEquals(1, update.executeUpdate());
            }

            try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory, core)) {
                List<ResourceDocument<Map<String, Object>>> documents = authority.list(SERVER, TYPE, "");

                assertEquals(1, documents.size());
                ResourceDocument<Map<String, Object>> document = documents.getFirst();
                assertEquals(2L, document.revision());
                assertEquals(graphMutation, document.mutationId());
                assertEquals(SERVER.canonicalText(), document.resource().id());
                assertEquals(SERVER.canonicalText(), document.payload().get("serverId"));
                assertEquals(Map.of("keep", true), document.payload().get("unknown"));
            }
            CoreGraphStorageBoundary.Decoded recovered = core.load(graph).orElseThrow();
            assertEquals(committed.envelope().assetRevision(), recovered.envelope().assetRevision());
            assertEquals(committed.envelope().assetMutationId(), recovered.envelope().assetMutationId());
            assertEquals(committed.graphDocument().checksum(), recovered.graphDocument().checksum());
            assertEquals(1, transitions.size());

            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("resource.db"));
                 var state = connection.prepareStatement(
                     "SELECT revision, mutation_id FROM resource_mutation_state WHERE resource = ?")) {
                state.setString(1, metadataResource);
                try (var result = state.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(2L, result.getLong(1));
                    assertEquals(graphMutation.toString(), result.getString(2));
                    assertFalse(result.next());
                }
            }
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("resource.db"));
                 var receipt = connection.prepareStatement("""
                     SELECT status, result_revision, transition_published, transition_envelope
                     FROM resource_mutation_receipt WHERE mutation_id = ?
                     """)) {
                receipt.setString(1, graphMutation.toString());
                try (var result = receipt.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("APPLIED", result.getString("status"));
                    assertEquals(1L, result.getLong("result_revision"));
                    assertEquals(1, result.getInt("transition_published"));
                    assertNull(result.getString("transition_envelope"));
                    assertFalse(result.next());
                }
            }
        }
    }

    @Test
    void exactProtocolRecoversLegacyGenericProjectMetadataLineageGap(@TempDir Path directory) throws Exception {
        verifyLegacyGenericProjectMetadataLineageGap(directory, false);
    }

    @Test
    void exactProtocolResumesCommittedLineageRepairBeforeSqliteSettlement(@TempDir Path directory) throws Exception {
        verifyLegacyGenericProjectMetadataLineageGap(directory, true);
    }

    @Test
    void lineageRecoveryProvesSavePresentationFromTheSourceMutation(@TempDir Path directory) throws Exception {
        Path assets = directory.resolve("assets");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(assets, new Gson())) {
            FlowStorage storage = storage(directory, coordinator);
            UUID initialMetadata = UUID.fromString("13131313-1313-4313-8313-131313131313");
            storage.saveProjectMetadata(projectMetadata("other", "claimed"), initialMetadata, 0L);
            try (JsonAssetStore<JsonObject> legacy = legacyLineageGapStore(assets, directory, coordinator)) {
                JsonObject source = resourcePayload("claimed", "Text/Templates");
                source.addProperty("text", "Saved");
                legacy.save(source, UUID.fromString("14141414-1414-4414-8414-141414141414"), 0L);
                UUID alignedMetadata = UUID.fromString("15151515-1515-4515-8515-151515151515");
                storage.saveProjectMetadata(projectMetadata("claimed", "other"), alignedMetadata, 1L);
                FlowStorage.ResourceIdentity baseline = storage.readProjectMetadataIdentity(SERVER.canonicalText());
                assertEquals(2L, baseline.revision());

                UUID sourceMutation = UUID.fromString("16161616-1616-4616-8616-161616161616");
                legacy.save(source, sourceMutation, 1L);
                String sourceHash = hash(canonicalJson(JsonAssetStore.logicalPayload(legacy.get("claimed")))).canonicalText();
                FlowStorage.ResourceIdentity repaired = storage.recoverProjectMetadataLineage(sourceMutation,
                    ReSyncResourceCatalog.TEXT_TEMPLATE, "claimed", 2L, sourceHash, baseline.revision(),
                    UUID.fromString(baseline.mutationId()), baseline.payloadHash());

                assertEquals(3L, repaired.revision());
                assertEquals(List.of("other", "claimed"),
                    projectMetadataResourceIds(storage.getProjectMetadata(SERVER.canonicalText())));
            }
        }
    }

    @Test
    void lineageRecoveryReusesMetadataCommittedWithTheSourceMutation(@TempDir Path directory) throws Exception {
        Path assets = directory.resolve("assets");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(assets, new Gson())) {
            FlowStorage storage = storage(directory, coordinator);
            UUID initialMetadata = UUID.fromString("21212121-2121-4121-8121-212121212121");
            storage.saveProjectMetadata(projectMetadata(), initialMetadata, 0L);
            FlowStorage.ResourceIdentity baseline = storage.readProjectMetadataIdentity(SERVER.canonicalText());

            try (JsonAssetStore<JsonObject> resources = legacyAdminStore(assets, directory,
                ReSyncResourceCatalog.TEXT_TEMPLATE, coordinator)) {
                JsonObject source = resourcePayload("claimed", "Text/Templates");
                source.addProperty("text", "Saved");
                UUID sourceMutation = UUID.fromString("22222222-2222-4222-8222-222222222222");
                resources.save(source, sourceMutation, 0L);
                String sourceHash = hash(canonicalJson(JsonAssetStore.logicalPayload(resources.get("claimed"))))
                    .canonicalText();
                FlowStorage.ResourceIdentity committed = storage.readProjectMetadataIdentity(SERVER.canonicalText());
                long sequence = coordinator.read(AssetTransactionCoordinator.Snapshot::rootSequence);

                FlowStorage.ResourceIdentity recovered = storage.recoverProjectMetadataLineage(sourceMutation,
                    ReSyncResourceCatalog.TEXT_TEMPLATE, "claimed", 1L, sourceHash, baseline.revision(),
                    UUID.fromString(baseline.mutationId()), baseline.payloadHash());

                assertEquals(committed, recovered);
                assertEquals(sourceMutation.toString(), recovered.mutationId());
                assertEquals(sequence, coordinator.read(AssetTransactionCoordinator.Snapshot::rootSequence));
            }
        }
    }

    @Test
    void lineageRecoveryProvesDeletePresentationFromTheSourceMutation(@TempDir Path directory) throws Exception {
        Path assets = directory.resolve("assets");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(assets, new Gson())) {
            FlowStorage storage = storage(directory, coordinator);
            UUID initialMetadata = UUID.fromString("17171717-1717-4717-8717-171717171717");
            storage.saveProjectMetadata(projectMetadata("claimed"), initialMetadata, 0L);
            try (JsonAssetStore<JsonObject> legacy = legacyLineageGapStore(assets, directory, coordinator)) {
                JsonObject source = resourcePayload("claimed", "Text/Templates");
                source.addProperty("text", "Deleted");
                legacy.save(source, UUID.fromString("18181818-1818-4818-8818-181818181818"), 0L);
                FlowStorage.ResourceIdentity baseline = storage.readProjectMetadataIdentity(SERVER.canonicalText());
                assertEquals(1L, baseline.revision());
                String sourceHash = hash(canonicalJson(JsonAssetStore.logicalPayload(legacy.get("claimed")))).canonicalText();

                UUID sourceMutation = UUID.fromString("19191919-1919-4919-8919-191919191919");
                legacy.delete("claimed", sourceMutation, 1L);
                FlowStorage.ResourceIdentity repaired = storage.recoverProjectMetadataLineage(sourceMutation,
                    ReSyncResourceCatalog.TEXT_TEMPLATE, "claimed", 2L, sourceHash, baseline.revision(),
                    UUID.fromString(baseline.mutationId()), baseline.payloadHash());

                assertEquals(2L, repaired.revision());
                assertTrue(projectMetadataResourceIds(storage.getProjectMetadata(SERVER.canonicalText())).isEmpty());
            }
        }
    }

    @Test
    void lineageRecoveryRejectsMetadataChangedByAnUnrelatedResource(@TempDir Path directory) throws Exception {
        Path assets = directory.resolve("assets");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(assets, new Gson())) {
            FlowStorage storage = storage(directory, coordinator);
            UUID initialMetadata = UUID.fromString("20202020-2020-4020-8020-202020202020");
            storage.saveProjectMetadata(projectMetadata("other", "claimed"), initialMetadata, 0L);
            try (JsonAssetStore<JsonObject> legacy = legacyLineageGapStore(assets, directory, coordinator)) {
                JsonObject source = resourcePayload("claimed", "Text/Templates");
                source.addProperty("text", "Claimed");
                legacy.save(source, UUID.fromString("21212121-2121-4121-8121-212121212121"), 0L);
                FlowStorage.ResourceIdentity baseline = storage.readProjectMetadataIdentity(SERVER.canonicalText());
                assertEquals(1L, baseline.revision());

                UUID combinedMutation = UUID.fromString("23232323-2323-4323-8323-232323232323");
                AssetTransactionCoordinator.Snapshot snapshot = legacy.coordinatorSnapshot();
                JsonObject unrelated = resourcePayload("other", "Text/Templates");
                unrelated.addProperty("text", "Unrelated");
                JsonAssetStore.PreparedMutation sourcePlan = legacy.prepareSave(snapshot, source, Map.of(), combinedMutation, 1L);
                JsonAssetStore.PreparedMutation unrelatedPlan = legacy.prepareSave(snapshot, unrelated, Map.of(), combinedMutation, 0L);
                legacy.commitPrepared(combinedMutation, snapshot, List.of(sourcePlan, unrelatedPlan));
                String sourceHash = hash(canonicalJson(JsonAssetStore.logicalPayload(legacy.get("claimed")))).canonicalText();

                IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> storage.recoverProjectMetadataLineage(combinedMutation, ReSyncResourceCatalog.TEXT_TEMPLATE,
                        "claimed", 2L, sourceHash, baseline.revision(), UUID.fromString(baseline.mutationId()),
                        baseline.payloadHash()));
                assertTrue(failure.getCause().getMessage().contains("unrelated resource asset"));
                assertEquals(1L, storage.readProjectMetadataIdentity(SERVER.canonicalText()).revision());
            }
        }
    }

    @Test
    void exactProtocolImportsCoordinatorProvenLegacyAdminChainOnce(@TempDir Path directory) throws Exception {
        verifyCoordinatorProvenLegacyAdminChain(directory, false, true);
    }

    @Test
    void exactProtocolQuarantinesCoordinatorProvenDisposableLegacyCoreOnce(@TempDir Path directory) throws Exception {
        verifyCoordinatorProvenLegacyAdminChain(directory, true, true);
    }

    @Test
    void exactProtocolRejectsDisposableLegacyCoreOutsideTheProbeScope(@TempDir Path directory) throws Exception {
        verifyCoordinatorProvenLegacyAdminChain(directory, true, false);
    }

    @Test
    void exactProtocolResumesLegacyCoreRecoveryAfterSqliteSettlementLoss(@TempDir Path directory) throws Exception {
        verifyCoordinatorProvenLegacyAdminChain(directory, true, true, true);
    }

    private void verifyCoordinatorProvenLegacyAdminChain(Path directory, boolean legacyCore,
                                                          boolean eligibleLegacyCore) throws Exception {
        verifyCoordinatorProvenLegacyAdminChain(directory, legacyCore, eligibleLegacyCore, false);
    }

    private void verifyCoordinatorProvenLegacyAdminChain(Path directory, boolean legacyCore,
                                                          boolean eligibleLegacyCore,
                                                          boolean interruptSqliteSettlement) throws Exception {
        Path assets = directory.resolve("assets");
        Path runtime = directory.resolve("runtime");
        String id = legacyCore && eligibleLegacyCore ? "codex_runtime_probe_test" : "legacy-admin-chain";
        UUID textCreate = UUID.fromString("20202020-2020-4020-8020-202020202020");
        UUID scoreboardCreate = UUID.fromString("30303030-3030-4030-8030-303030303030");
        UUID motdCreate = UUID.fromString("40404040-4040-4040-8040-404040404040");
        UUID flowCreate = UUID.fromString("50505050-5050-4050-8050-505050505050");
        UUID scoreboardDelete = UUID.fromString("60606060-6060-4060-8060-606060606060");
        UUID motdDelete = UUID.fromString("70707070-7070-4070-8070-707070707070");
        UUID textDelete = UUID.fromString("80808080-8080-4080-8080-808080808080");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(assets, new Gson())) {
            FlowStorage storage = storage(directory, coordinator);
            UUID baseline = UUID.fromString("10101010-1010-4010-8010-101010101010");
            storage.saveProjectMetadata("""
                {"serverId":"project","folders":[
                {"collapsed":false,"name":"Text","parentPath":"","path":"Text","sortOrder":0},
                {"collapsed":false,"name":"Templates","parentPath":"Text","path":"Text/Templates","sortOrder":0},
                {"collapsed":false,"name":"Customization","parentPath":"","path":"Customization","sortOrder":0},
                {"collapsed":false,"name":"Scoreboards","parentPath":"Customization","path":"Customization/Scoreboards","sortOrder":0},
                {"collapsed":false,"name":"MOTDs","parentPath":"Customization","path":"Customization/MOTDs","sortOrder":0},
                {"collapsed":false,"name":"Blueprints","parentPath":"","path":"Blueprints","sortOrder":5},
                {"collapsed":false,"name":"Flows","parentPath":"Blueprints","path":"Blueprints/Flows","sortOrder":6}
                ],"resources":[]}
                """, baseline, 0L);
            try (JsonAssetStore<JsonObject> text = legacyAdminStore(assets, directory, ReSyncResourceCatalog.TEXT_TEMPLATE, coordinator);
                 JsonAssetStore<JsonObject> motd = legacyAdminStore(assets, directory, ReSyncResourceCatalog.MOTD_PROFILE, coordinator)) {
                FlowResourceRegistry registry = legacyRegistry(storage, text);
                registerScoreboardAdapter(registry, storage);
                registerLegacyAdapter(registry, ReSyncResourceCatalog.MOTD_PROFILE, motd);
                registry.addCoreMutationListener(ignored -> {
                });
                CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
                ServerResourceLocator neutralFlow = new ServerResourceLocator(SERVER,
                    ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId("flow")), "receipted-neutral-flow");
                UUID neutralCreate = UUID.fromString("11111111-2222-4333-8444-555555555555");
                UUID neutralSave = UUID.fromString("99999999-aaaa-4bbb-8ccc-dddddddddddd");
                try (SqliteProtocolResourceMutationAuthority authority = authority(registry, runtime,
                    new FlowStorageCoreGraphResourceAuthority(storage, SERVER))) {
                    assertEquals(1L, authority.list(SERVER, TYPE, "").getFirst().revision());
                    ProtocolEnvelopeDispatchResult created = mutate(authority, createCore(neutralFlow, neutralCreate, boundary));
                    assertTrue(created.handled(), created.code() + ": " + created.message());
                    ProtocolEnvelopeDispatchResult saved = mutate(authority, saveCore(neutralFlow, neutralSave, boundary, 1L));
                    assertTrue(saved.handled(), saved.code() + ": " + saved.message());
                }

                text.save(resourcePayload(id, "Text/Templates"), textCreate, 0L);
                storage.saveScoreboard(new ScoreboardDefinition(id, "Server"), scoreboardCreate, 0L);
                motd.save(resourcePayload(id, "Customization/MOTDs"), motdCreate, 0L);
                ServerResourceLocator flow = new ServerResourceLocator(SERVER,
                    ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId("flow")), id);
                if (legacyCore) {
                    FlowGraph graph = new FlowGraph(id, Map.of(), List.of(), List.of());
                    storage.saveGraph(graph, flowCreate, 0L);
                } else {
                    GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), flow, 1L, GRAPH_BINDING, Set.of(),
                        List.of(), List.of(), List.of(), List.of(), OpaqueData.of(Map.of("test", "legacy-admin")));
                    storage.saveCoreGraph(graph, ResourceActivationState.ACTIVE, flowCreate, 0L);
                    CoreGraphStorageBoundary.Decoded savedFlow = new FlowStorageCoreGraphResourceAuthority(storage, SERVER)
                        .load(flow).orElseThrow();
                    assertEquals(flowCreate.toString(), savedFlow.envelope().assetMutationId());
                }
                storage.deleteScoreboard(id, scoreboardDelete, 1L);
                motd.delete(id, motdDelete, 1L);
                text.delete(id, textDelete, 1L);
                if (!legacyCore) {
                    CoreGraphStorageBoundary.Decoded retainedFlow = new FlowStorageCoreGraphResourceAuthority(storage, SERVER)
                        .load(flow).orElseThrow();
                    assertEquals(flowCreate.toString(), retainedFlow.envelope().assetMutationId());
                }

                Path unexpectedBinding = assets.resolve(".asset-coordinator/bindings/unrelated.intent");
                Files.writeString(unexpectedBinding, "{}");
                assertThrows(IllegalStateException.class, () -> authority(registry, runtime,
                    new FlowStorageCoreGraphResourceAuthority(storage, SERVER)));
                assertEquals(0L, legacyReceiptCount(runtime.resolve("resource.db")));
                Files.delete(unexpectedBinding);
            }
        }

        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(assets, new Gson())) {
            FlowStorage storage = storage(directory, coordinator);
            try (JsonAssetStore<JsonObject> text = legacyAdminStore(assets, directory, ReSyncResourceCatalog.TEXT_TEMPLATE, coordinator);
                 JsonAssetStore<JsonObject> motd = legacyAdminStore(assets, directory, ReSyncResourceCatalog.MOTD_PROFILE, coordinator)) {
                FlowResourceRegistry registry = legacyRegistry(storage, text);
                registerScoreboardAdapter(registry, storage);
                registerLegacyAdapter(registry, ReSyncResourceCatalog.MOTD_PROFILE, motd);
                registry.addCoreMutationListener(ignored -> {
                });
                if (legacyCore && !eligibleLegacyCore) {
                    assertThrows(IllegalStateException.class, () -> authority(registry, runtime,
                        new FlowStorageCoreGraphResourceAuthority(storage, SERVER)));
                    assertEquals(0L, legacyReceiptCount(runtime.resolve("resource.db")));
                    assertTrue(storage.getGraph(id) != null);
                    return;
                }

                CoreGraphResourceAuthority recoveryAuthority = new FlowStorageCoreGraphResourceAuthority(storage, SERVER);
                long recoveredSequence = -1L;
                if (interruptSqliteSettlement) {
                    CoreGraphResourceAuthority interrupted = failAfterLegacyRecovery(recoveryAuthority);
                    assertThrows(IllegalStateException.class, () -> authority(registry, runtime, interrupted));
                    assertLegacyAdminCoreRecoveryPending(runtime.resolve("resource.db"));
                    recoveredSequence = coordinator.read(AssetTransactionCoordinator.Snapshot::rootSequence);
                }

                String metadataHash;
                try (SqliteProtocolResourceMutationAuthority authority = authority(registry, runtime,
                    recoveryAuthority)) {
                    ResourceDocument<Map<String, Object>> metadata = authority.list(SERVER, TYPE, "").getFirst();
                    assertEquals(legacyCore ? 10L : 9L, metadata.revision());
                    if (!legacyCore) {
                        assertEquals(textDelete, metadata.mutationId());
                    }
                    metadataHash = metadata.payloadHash().canonicalText();
                }
                long settledSequence = coordinator.read(AssetTransactionCoordinator.Snapshot::rootSequence);
                if (interruptSqliteSettlement) {
                    assertEquals(Math.addExact(recoveredSequence, 1L), settledSequence);
                }
                List<UUID> chain = List.of(textCreate, scoreboardCreate, motdCreate, flowCreate,
                    scoreboardDelete, motdDelete, textDelete);
                if (legacyCore) {
                    assertLegacyAdminCoreRecovery(runtime.resolve("resource.db"), chain, flowCreate, metadataHash);
                } else {
                    assertLegacyAdminRecovery(runtime.resolve("resource.db"), chain, flowCreate, textDelete, metadataHash);
                }
                try (SqliteProtocolResourceMutationAuthority authority = authority(registry, runtime,
                    new FlowStorageCoreGraphResourceAuthority(storage, SERVER))) {
                    assertEquals(legacyCore ? 10L : 9L, authority.list(SERVER, TYPE, "").getFirst().revision());
                }
                if (interruptSqliteSettlement) {
                    assertEquals(settledSequence,
                        coordinator.read(AssetTransactionCoordinator.Snapshot::rootSequence));
                }
                if (legacyCore) {
                    assertLegacyAdminCoreRecovery(runtime.resolve("resource.db"), chain, flowCreate, metadataHash);
                } else {
                    assertLegacyAdminRecovery(runtime.resolve("resource.db"), chain, flowCreate, textDelete, metadataHash);
                }
            }
        }
    }

    @Test
    void exactProtocolRepairsUnreceiptedWorldGenProjectMetadataLineage(@TempDir Path directory) throws Exception {
        Path assets = directory.resolve("assets");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(assets, new Gson())) {
            FlowStorage storage = storage(directory, coordinator);
            UUID initialMetadataMutation = UUID.fromString("56565656-5656-4656-8656-565656565656");
            storage.saveProjectMetadata("{\"serverId\":\"project\",\"resources\":[]}", initialMetadataMutation, 0L);
            FlowResourceRegistry registry = registry(storage);
            try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
                assertEquals(1L, authority.list(SERVER, TYPE, "").getFirst().revision());
            }
            WorldGenProject template = new WorldGenProject();
            template.setId("lineage-gap");
            JsonObject serialized = JsonParser.parseString(WorldGenSerializer.serializeProjectOwned(template)).getAsJsonObject();
            JsonObject payload = new JsonObject();
            serialized.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> payload.add(entry.getKey(), entry.getValue().deepCopy()));
            WorldGenProject project = WorldGenSerializer.deserializeProject(payload.toString());
            UUID sourceMutation = UUID.fromString("78787878-7878-4878-8878-787878787878");
            JsonObject scope = worldGenScope(project, payload, sourceMutation);
            try (JsonAssetStore<WorldGenProject> legacyWorldGen = new JsonAssetStore<>(assets,
                directory.resolve("worldgen-projects"), ReSyncResourceCatalog.WORLDGEN,
                ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.WORLDGEN),
                WorldGenSerializer::deserializeProject, WorldGenSerializer::serializeProject,
                WorldGenProject::getId, null, LegacyRuntimeActivationGate.runtime(directory), coordinator, () -> true,
                () -> () -> {
                }, WorldGenSerializer::mergeProjectPayload)) {
                AssetTransactionCoordinator.Snapshot snapshot = legacyWorldGen.coordinatorSnapshot();
                coordinator.withTransactionIntentScope(scope, () -> legacyWorldGen.commitPrepared(sourceMutation, snapshot,
                    List.of(legacyWorldGen.prepareSave(snapshot, project, Map.of(), sourceMutation, 0L))));

                FlowStorage.ResourceIdentity before = storage.readProjectMetadataIdentity(SERVER.canonicalText());
                assertEquals(1L, before.revision());
                assertEquals(initialMetadataMutation.toString(), before.mutationId());
                UUID repairMutation;
                try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
                    List<ResourceDocument<Map<String, Object>>> metadata = authority.list(SERVER, TYPE, "");
                    assertEquals(1, metadata.size());
                    ResourceDocument<Map<String, Object>> document = metadata.getFirst();
                    assertEquals(2L, document.revision());
                    assertNotEquals(initialMetadataMutation, document.mutationId());
                    assertNotEquals(sourceMutation, document.mutationId());
                    repairMutation = document.mutationId();
                    List<?> resources = assertInstanceOf(List.class, document.payload().get("resources"));
                    assertTrue(resources.stream().map(Map.class::cast).anyMatch(resource ->
                        "worldgen".equals(resource.get("type")) && "lineage-gap".equals(resource.get("id"))));
                }
                assertTrue(coordinator.mutation(repairMutation).isPresent());
                try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
                    ResourceDocument<Map<String, Object>> replay = authority.list(SERVER, TYPE, "").getFirst();
                    assertEquals(2L, replay.revision());
                    assertEquals(repairMutation, replay.mutationId());
                }
            }
        }
    }

    @Test
    void exactWorldGenSavePublishesAtomicProjectMetadataLineage(@TempDir Path directory) throws Exception {
        Path assets = directory.resolve("assets");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(assets, new Gson())) {
            FlowStorage storage = storage(directory, coordinator);
            UUID initialMetadataMutation = UUID.fromString("89898989-8989-4989-8989-898989898989");
            storage.saveProjectMetadata("{\"serverId\":\"project\",\"resources\":[]}", initialMetadataMutation, 0L);
            FlowResourceRegistry registry = registry(storage);
            try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
                assertEquals(1L, authority.list(SERVER, TYPE, "").getFirst().revision());
                WorldGenProjectStorage worldGen = new WorldGenProjectStorage(directory.toFile(),
                    LegacyRuntimeActivationGate.runtime(directory), new AssetPersistenceGate(directory), coordinator);
                WorldGenProject template = new WorldGenProject();
                template.setId("atomic-lineage");
                JsonObject serialized = JsonParser.parseString(WorldGenSerializer.serializeProjectOwned(template)).getAsJsonObject();
                JsonObject payload = new JsonObject();
                payload.addProperty("futureOpaqueField", "preserved");
                serialized.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> payload.add(entry.getKey(), entry.getValue().deepCopy()));
                WorldGenProject project = WorldGenSerializer.deserializeProject(payload.toString());
                UUID sourceMutation = UUID.fromString("90909090-9090-4090-8090-909090909090");
                worldGen.saveProject(project, sourceMutation, 0L, worldGenScope(project, payload, sourceMutation));
                FlowStorage.ResourceIdentity identity = storage.readProjectMetadataIdentity(SERVER.canonicalText());
                assertEquals(2L, identity.revision());
                assertEquals(sourceMutation.toString(), identity.mutationId());

                ResourceDocument<Map<String, Object>> metadata = authority.list(SERVER, TYPE, "").getFirst();
                assertEquals(2L, metadata.revision());
                assertEquals(sourceMutation, metadata.mutationId());
                worldGen.closePersistence();
            }
            try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
                ResourceDocument<Map<String, Object>> replay = authority.list(SERVER, TYPE, "").getFirst();
                assertEquals(2L, replay.revision());
                assertEquals(UUID.fromString("90909090-9090-4090-8090-909090909090"), replay.mutationId());
            }
        }
    }

    private void verifyLegacyGenericProjectMetadataLineageGap(Path directory, boolean commitRepairBeforeRestart) throws Exception {
        Path assets = directory.resolve("assets");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(assets, new Gson())) {
            FlowStorage storage = storage(directory, coordinator);
            UUID initialMetadataMutation = UUID.fromString("12121212-1212-4212-8212-121212121212");
            storage.saveProjectMetadata("{\"serverId\":\"project\",\"resources\":[]}", initialMetadataMutation, 0L);
            try (JsonAssetStore<JsonObject> legacy = new JsonAssetStore<>(assets, directory.resolve("legacy-text"),
                ReSyncResourceCatalog.TEXT_TEMPLATE, "Text/Templates", json -> JsonParser.parseString(json).getAsJsonObject(),
                SqliteProtocolProjectMetadataMutationTest::canonicalJson, value -> value.get("id").getAsString(),
                value -> value.has("path") ? value.get("path").getAsString() : "Text/Templates",
                LegacyRuntimeActivationGate.runtime(directory), coordinator, () -> true)) {
                FlowResourceRegistry registry = legacyRegistry(storage, legacy);
                ContractRef<ResourceTypeId> textType = ContractRef.of(new OwnerId("restudio.resync"),
                    new ResourceTypeId(ReSyncResourceCatalog.TEXT_TEMPLATE));
                ServerResourceLocator text = new ServerResourceLocator(SERVER, textType, "legacy-gap");
                UUID sourceMutation = UUID.fromString("34343434-3434-4434-8434-343434343434");
                String payload = "{\"id\":\"legacy-gap\",\"path\":\"Text/Templates\",\"text\":\"Recovered\"}";

                try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
                    ProtocolEnvelopeDispatchResult result = mutate(authority, create(text, payload, sourceMutation));
                    assertFalse(result.handled());
                }
                assertEquals(hash(payload).canonicalText(), legacy.readStamp("legacy-gap").payloadHash());
                assertEquals(JsonParser.parseString(payload).getAsJsonObject(), JsonAssetStore.logicalPayload(legacy.get("legacy-gap")));
                assertEquals(hash(payload).canonicalText(),
                    hash(canonicalJson(JsonAssetStore.logicalPayload(legacy.get("legacy-gap")))).canonicalText());
                assertEquals("PENDING", mutationStatus(directory.resolve("resource.db"), sourceMutation));
                FlowStorage.ResourceIdentity before = storage.readProjectMetadataIdentity(SERVER.canonicalText());
                assertEquals(1L, before.revision());
                assertEquals(initialMetadataMutation.toString(), before.mutationId());
                if (commitRepairBeforeRestart) {
                    FlowStorage.ResourceIdentity committedRepair = storage.recoverProjectMetadataLineage(sourceMutation,
                        ReSyncResourceCatalog.TEXT_TEMPLATE, "legacy-gap", 1L, hash(payload).canonicalText(),
                        before.revision(), UUID.fromString(before.mutationId()), before.payloadHash());
                    assertEquals(2L, committedRepair.revision());
                    assertEquals("PENDING", mutationStatus(directory.resolve("resource.db"), sourceMutation));
                }

                UUID repairMutation;
                try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
                    List<ResourceDocument<Map<String, Object>>> texts = authority.list(SERVER, textType, "");
                    assertEquals(List.of("legacy-gap"), texts.stream().map(document -> document.resource().id()).toList());
                    assertEquals(sourceMutation, texts.getFirst().mutationId());
                    FlowStorage.ResourceIdentity repaired = storage.readProjectMetadataIdentity(SERVER.canonicalText());
                    assertEquals(2L, repaired.revision());
                    assertNotEquals(sourceMutation.toString(), repaired.mutationId());
                    repairMutation = UUID.fromString(repaired.mutationId());
                    assertTrue(coordinator.mutation(repairMutation).isPresent());
                }
                assertEquals("APPLIED", mutationStatus(directory.resolve("resource.db"), sourceMutation));
                try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
                    List<ResourceDocument<Map<String, Object>>> metadata = authority.list(SERVER, TYPE, "");
                    assertEquals(2L, metadata.getFirst().revision());
                    assertEquals(repairMutation, metadata.getFirst().mutationId());
                }
                assertEquals(2L, storage.readProjectMetadataIdentity(SERVER.canonicalText()).revision());
            }
        }
    }

    @Test
    void exactProtocolSaveAndDeleteReplayAfterCallbackLossAndRestart(@TempDir Path directory) throws Exception {
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(directory.resolve("assets"), new Gson())) {
            FlowStorage storage = storage(directory, coordinator);
            FlowResourceRegistry registry = registry(storage);
            registry.setMutationListener(failingMutationListener());
            registry.setCommitListener(failingCommitListener());
            storage.saveProjectMetadata("{\"serverId\":\"project\",\"resources\":[]}",
                UUID.fromString("22222222-2222-4222-8222-222222222222"), 0L);
            ServerResourceLocator resource = resource();
            UUID saveMutation = UUID.fromString("33333333-3333-4333-8333-333333333333");
            UUID deleteMutation = UUID.fromString("44444444-4444-4444-8444-444444444444");
            ProtocolEnvelope<Map<String, Object>> save = save(resource,
                "{\"serverId\":\"project\",\"resources\":[]}",
                saveMutation, 1L);
            ProtocolEnvelope<Map<String, Object>> delete = delete(resource,
                deleteMutation, 2L);

            ProtocolEnvelopeDispatchResult firstSave;
            ProtocolEnvelopeDispatchResult firstDelete;
            try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
                firstSave = mutate(authority, save);
                assertTrue(firstSave.handled(), firstSave.code() + ": " + firstSave.message());
                firstDelete = mutate(authority, delete);
                assertTrue(firstDelete.handled(), firstDelete.code() + ": " + firstDelete.message());
            }
            try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
                ProtocolEnvelopeDispatchResult saveReplay = mutate(authority, save(canonicalResource(),
                    "{\"serverId\":\"" + SERVER.canonicalText() + "\",\"resources\":[]}", saveMutation, 1L));
                ProtocolEnvelopeDispatchResult deleteReplay = mutate(authority, delete(canonicalResource(), deleteMutation, 2L));
                assertTrue(saveReplay.handled(), saveReplay.code() + ": " + saveReplay.message());
                assertTrue(deleteReplay.handled(), deleteReplay.code() + ": " + deleteReplay.message());
                ResourceDocument<Map<String, Object>> saved = document(firstSave);
                ResourceDocument<Map<String, Object>> replayedSave = document(saveReplay);
                ResourceDocument<Map<String, Object>> deleted = document(firstDelete);
                ResourceDocument<Map<String, Object>> replayedDelete = document(deleteReplay);
                assertEquals(saved.revision(), replayedSave.revision());
                assertEquals(saved.mutationId(), replayedSave.mutationId());
                assertEquals(saved.payloadHash(), replayedSave.payloadHash());
                assertEquals(deleted.revision(), replayedDelete.revision());
                assertEquals(deleted.mutationId(), replayedDelete.mutationId());
                assertEquals(deleted.payloadHash(), replayedDelete.payloadHash());
                assertTrue(replayedDelete.deleted());
                assertNull(replayedDelete.payload());
            }
            assertEquals(List.of(), storage.listProjectMetadataIds());
        }
    }

    @Test
    void exactProtocolRecoversUnreceiptedWorldGenDeleteAfterAuthorityRestart(@TempDir Path directory) throws Exception {
            UUID initialMetadataMutation = UUID.fromString("12121212-1212-4212-8212-121212121212");
            UUID saveMutation = UUID.fromString("13131313-1313-4313-8313-131313131313");
            UUID deleteMutation = UUID.fromString("14141414-1414-4414-8414-141414141414");
            UUID deleteOperation = UUID.fromString("15151515-1515-4515-8515-151515151515");
            String projectId = "delete-lineage";
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(directory.resolve("assets"), new Gson())) {
            FlowStorage storage = storage(directory, coordinator);
            storage.saveProjectMetadata("{\"serverId\":\"project\",\"resources\":[]}", initialMetadataMutation, 0L);
            FlowResourceRegistry registry = registry(storage);
            try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
                assertEquals(1L, authority.list(SERVER, TYPE, "").getFirst().revision());
                FlowStorage.ProjectMetadataObservation baseline = storage.readProjectMetadataObservation(SERVER.canonicalText());
                WorldGenProjectStorage worldGen = new WorldGenProjectStorage(directory.toFile(),
                    LegacyRuntimeActivationGate.runtime(directory), new AssetPersistenceGate(directory), coordinator);
                WorldGenProject project = new WorldGenProject();
                project.setId(projectId);
                worldGen.saveProject(project, saveMutation, 0L, worldGenScope(project, saveMutation));
                FlowStorage.ProjectMetadataObservation saved = storage.readProjectMetadataObservation(SERVER.canonicalText());
                assertNotEquals(baseline.projectHash(), saved.projectHash());
                assertEquals(2L, authority.list(SERVER, TYPE, "").getFirst().revision());
                assertNotEquals(deleteMutation, deleteOperation);
                worldGen.deleteProject(projectId, deleteMutation, 1L,
                    worldGenDeleteScope(projectId, deleteMutation, deleteOperation, 1L));

                AssetTransactionCoordinator.MutationView delete = coordinator.mutation(deleteMutation).orElseThrow();
                Set<AssetTransactionCoordinator.AssetKey> keys = Set.of(
                    new AssetTransactionCoordinator.AssetKey(ReSyncResourceCatalog.WORLDGEN, projectId),
                    new AssetTransactionCoordinator.AssetKey(ReSyncResourceCatalog.WORLDGEN + ".tombstone", projectId),
                    new AssetTransactionCoordinator.AssetKey(ReSyncResourceCatalog.WORLDGEN + ".intent", projectId),
                    new AssetTransactionCoordinator.AssetKey("project_metadata.lineage", "project"));
                assertEquals(keys, delete.result().states().keySet());
                assertTrue(delete.result().states().get(new AssetTransactionCoordinator.AssetKey(
                    ReSyncResourceCatalog.WORLDGEN, projectId)) instanceof AssetTransactionCoordinator.Deleted);
                assertEquals("worldGenProjectDelete", delete.intent().getAsJsonObject("scope").get("action").getAsString());
                assertEquals("worldGenProjectDelete:" + projectId + ":" + deleteOperation,
                    delete.intent().getAsJsonObject("scope").get("requestId").getAsString());
                assertEquals(delete.intent().getAsJsonObject("scope").get("requestId").getAsString(),
                    delete.intent().getAsJsonObject("scope").get("operationId").getAsString());
                JsonObject semanticIntent = JsonParser.parseString(Files.readString(directory.resolve(
                    "assets/.mutation-intents/worldgen/" + projectId + ".json"))).getAsJsonObject();
                assertEquals(Set.of("operation", "type"), semanticIntent.keySet());
                assertEquals("delete", semanticIntent.get("operation").getAsString());
                assertEquals(ReSyncResourceCatalog.WORLDGEN, semanticIntent.get("type").getAsString());
                assertTrue(storage.readProjectMetadataIdentity(SERVER.canonicalText()).revision() > 1L);
                assertEquals(List.of(SERVER.canonicalText()), storage.listProjectMetadataIds());
                ResourceDocument<Map<String, Object>> immediate = authority.list(SERVER, TYPE, "").getFirst();
                assertEquals(3L, immediate.revision());
                assertEquals(deleteMutation, immediate.mutationId());
                assertTrue(assertInstanceOf(List.class, immediate.payload().get("resources")).isEmpty());
                worldGen.closePersistence();
            }
        }

        try (AssetTransactionCoordinator restartedCoordinator = AssetTransactionCoordinator.open(
            directory.resolve("assets"), new Gson())) {
            FlowStorage restartedStorage = storage(directory, restartedCoordinator);
            FlowResourceRegistry restartedRegistry = registry(restartedStorage);
            try (SqliteProtocolResourceMutationAuthority authority = authority(restartedRegistry, directory)) {
                ResourceDocument<Map<String, Object>> metadata = authority.list(SERVER, TYPE, "").getFirst();
                assertEquals(3L, metadata.revision());
                assertEquals(deleteMutation, metadata.mutationId());
                assertTrue(assertInstanceOf(List.class, metadata.payload().get("resources")).isEmpty());
            }
        }
    }

    @Test
    void recoversTwoCommittedPendingJsonSavesFromCoordinatorHistory(@TempDir Path directory) throws Exception {
        recoverTwoCommittedSaves(directory, false);
    }

    @Test
    void rejectsCommittedHistoryWithAnUnrelatedMetadataBaseline(@TempDir Path directory) throws Exception {
        recoverTwoCommittedSaves(directory, true);
    }

    private void recoverTwoCommittedSaves(Path directory, boolean unrelatedBaseline) throws Exception {
        UUID initialMetadataMutation = UUID.fromString("a1010101-0101-4101-8101-a10101010101");
        UUID initialAlphaMutation = UUID.fromString("a2020202-0202-4202-8202-a20202020202");
        UUID initialBetaMutation = UUID.fromString("a3030303-0303-4303-8303-a30303030303");
        UUID alphaMutation = UUID.fromString("a5050505-0505-4505-8505-a50505050505");
        UUID betaMutation = UUID.fromString("a4040404-0404-4404-8404-a40404040404");
        UUID unrelatedMutation = UUID.fromString("a6060606-0606-4606-8606-a60606060606");
        Path runtime = directory.resolve("runtime");
        Path database = runtime.resolve("resource.db");
        long baselineMetadataRevision;
        UUID baselineMetadataMutation;
        long committedRootSequence;
        JsonObject initialAlpha = textTemplate("alpha", "one");
        JsonObject initialBeta = textTemplate("beta", "one");
        JsonObject savedAlpha = textTemplate("alpha", "two");
        JsonObject savedBeta = textTemplate("beta", "two");
        String alphaPreconditionHash = hash(canonicalJson(initialAlpha)).canonicalText();
        String betaPreconditionHash = hash(canonicalJson(initialBeta)).canonicalText();

        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(directory.resolve("assets"), new Gson());
             JsonAssetStore<JsonObject> legacy = legacyAdminStore(directory.resolve("assets"), directory,
                 ReSyncResourceCatalog.TEXT_TEMPLATE, coordinator)) {
            FlowStorage storage = storage(directory, coordinator);
            storage.saveProjectMetadata(projectMetadata("alpha", "beta"), initialMetadataMutation, 0L);
            legacy.save(initialAlpha, initialAlphaMutation, 0L);
            legacy.save(initialBeta, initialBetaMutation, 0L);
            FlowStorage.ResourceIdentity baseline = storage.readProjectMetadataIdentity(SERVER.canonicalText());
            baselineMetadataRevision = baseline.revision();
            baselineMetadataMutation = UUID.fromString(baseline.mutationId());

            FlowResourceRegistry registry = legacyRegistry(storage, legacy);
            try (SqliteProtocolResourceMutationAuthority authority = authority(registry, runtime)) {
                assertEquals(2, authority.list(SERVER,
                    ContractRef.of(new OwnerId("restudio.resync"),
                        new ResourceTypeId(ReSyncResourceCatalog.TEXT_TEMPLATE)), "").size());
                assertEquals(baselineMetadataRevision,
                    authority.load(new ServerResourceLocator(SERVER, TYPE, SERVER.canonicalText())).revision());
            }

            legacy.save(savedAlpha, alphaMutation, 1L);
            legacy.save(savedBeta, betaMutation, 1L);
            assertEquals(alphaMutation.toString(), legacy.readStamp("alpha").mutationValue());
            assertEquals(betaMutation.toString(), legacy.readStamp("beta").mutationValue());
            FlowStorage.ResourceIdentity advanced = storage.readProjectMetadataIdentity(SERVER.canonicalText());
            assertEquals(baselineMetadataRevision + 2L, advanced.revision());
            assertEquals(betaMutation.toString(), advanced.mutationId());
            committedRootSequence = coordinator.read(snapshot -> snapshot.rootSequence());
            insertPendingSave(database, textResource("alpha"), alphaMutation, 1L, alphaPreconditionHash,
                savedAlpha, 1_000L);
            insertPendingSave(database, textResource("beta"), betaMutation, 1L, betaPreconditionHash,
                savedBeta, 1_000L);
            if (unrelatedBaseline) {
                try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                     PreparedStatement statement = connection.prepareStatement(
                         "UPDATE resource_mutation_state SET mutation_id = ? WHERE resource = ?")) {
                    statement.setString(1, unrelatedMutation.toString());
                    statement.setString(2, new ServerResourceLocator(SERVER, TYPE, SERVER.canonicalText()).canonicalText());
                    assertEquals(1, statement.executeUpdate());
                }
            }
            assertEquals("PENDING", mutationStatus(database, alphaMutation));
            assertEquals("PENDING", mutationStatus(database, betaMutation));
        }

        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(directory.resolve("assets"), new Gson());
             JsonAssetStore<JsonObject> legacy = legacyAdminStore(directory.resolve("assets"), directory,
                 ReSyncResourceCatalog.TEXT_TEMPLATE, coordinator)) {
            FlowStorage storage = storage(directory, coordinator);
            FlowResourceRegistry registry = legacyRegistry(storage, legacy);
            try (SqliteProtocolResourceMutationAuthority authority = authority(registry, runtime)) {
                assertEquals(!unrelatedBaseline, authority.durable());
                assertEquals(unrelatedBaseline ? "PENDING" : "APPLIED", mutationStatus(database, alphaMutation));
                assertEquals(unrelatedBaseline ? "PENDING" : "APPLIED", mutationStatus(database, betaMutation));
            }
            FlowStorage.ResourceIdentity metadata = storage.readProjectMetadataIdentity(SERVER.canonicalText());
            assertEquals(baselineMetadataRevision + 2L, metadata.revision());
            assertEquals(betaMutation.toString(), metadata.mutationId());
            assertNotEquals(baselineMetadataMutation.toString(), metadata.mutationId());
            assertEquals(2L, legacy.readStamp("alpha").revision());
            assertEquals(alphaMutation.toString(), legacy.readStamp("alpha").mutationValue());
            assertEquals(2L, legacy.readStamp("beta").revision());
            assertEquals(betaMutation.toString(), legacy.readStamp("beta").mutationValue());
            ServerResourceLocator metadataResource = new ServerResourceLocator(SERVER, TYPE, SERVER.canonicalText());
            assertEquals(baselineMetadataRevision + (unrelatedBaseline ? 0L : 2L), persistedStateRevision(database, metadataResource));
            assertEquals((unrelatedBaseline ? unrelatedMutation : betaMutation).toString(), persistedStateMutation(database, metadataResource));
            assertEquals(committedRootSequence, coordinator.read(snapshot -> snapshot.rootSequence()).longValue());
        }
    }

    private static FlowStorage storage(Path directory, AssetTransactionCoordinator coordinator) {
        return new FlowStorage(directory.toFile(), LegacyRuntimeActivationGate.runtime(directory),
            new AssetPersistenceGate(directory), SERVER, coordinator);
    }

    private static JsonObject worldGenScope(WorldGenProject project, UUID mutationId) {
        project.rebuildIndices();
        JsonObject payload = JsonParser.parseString(WorldGenSerializer.serializeProjectOwned(project)).getAsJsonObject();
        return worldGenScope(project, payload, mutationId);
    }

    private static JsonObject worldGenScope(WorldGenProject project, JsonObject payload, UUID mutationId) {
        String requestId = "worldGenProjectSave:" + project.getId() + ":" + mutationId;
        String actorClientId = "bridge:test-client";
        Map<String, Object> intent = new java.util.LinkedHashMap<>();
        intent.put("actorClientId", actorClientId);
        intent.put("requestId", requestId);
        intent.put("mutationId", mutationId.toString());
        intent.put("operationId", requestId);
        intent.put("action", "worldGenProjectSave");
        intent.put("resourceId", project.getId());
        intent.put("expectedRevision", 0L);
        intent.put("data", CanonicalJson.parseOpaque(payload.toString()));
        JsonObject scope = new JsonObject();
        scope.addProperty("actorClientId", actorClientId);
        scope.addProperty("requestId", requestId);
        scope.addProperty("mutationId", mutationId.toString());
        scope.addProperty("operationId", requestId);
        scope.addProperty("action", "worldGenProjectSave");
        scope.addProperty("resourceId", project.getId());
        scope.addProperty("expectedRevision", 0L);
        scope.add("payload", payload.deepCopy());
        scope.addProperty("worldGenIntentHash", CanonicalJson.sha256("worldgen-mutation", intent));
        return scope;
    }

    private static JsonObject worldGenDeleteScope(String projectId, UUID mutationId, long expectedRevision) {
        return worldGenDeleteScope(projectId, mutationId, mutationId, expectedRevision);
    }

    private static JsonObject worldGenDeleteScope(String projectId, UUID mutationId, UUID operationUuid,
                                                  long expectedRevision) {
        String requestId = "worldGenProjectDelete:" + projectId + ":" + operationUuid;
        String actorClientId = "bridge:test-client";
        JsonObject payload = new JsonObject();
        payload.addProperty("projectId", projectId);
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("actorClientId", actorClientId);
        intent.put("requestId", requestId);
        intent.put("mutationId", mutationId.toString());
        intent.put("operationId", requestId);
        intent.put("action", "worldGenProjectDelete");
        intent.put("resourceId", projectId);
        intent.put("expectedRevision", expectedRevision);
        intent.put("data", CanonicalJson.parseOpaque(payload.toString()));
        JsonObject scope = new JsonObject();
        scope.addProperty("actorClientId", actorClientId);
        scope.addProperty("requestId", requestId);
        scope.addProperty("mutationId", mutationId.toString());
        scope.addProperty("operationId", requestId);
        scope.addProperty("action", "worldGenProjectDelete");
        scope.addProperty("resourceId", projectId);
        scope.addProperty("expectedRevision", expectedRevision);
        scope.add("payload", payload);
        scope.addProperty("worldGenIntentHash", CanonicalJson.sha256("worldgen-mutation", intent));
        return scope;
    }

    private static FlowResourceRegistry registry(FlowStorage storage) {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        new FlowResourcePacketRouter(storage, null, null, null, null, null, null, registry, ignored -> {
        });
        return registry;
    }

    private static FlowResourceRegistry legacyRegistry(FlowStorage storage, JsonAssetStore<JsonObject> legacy) {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(new FlowResourceAdapter<String>() {
            @Override
            public restudio.resync.resources.ReSyncManagedResource descriptor() {
                return ReSyncResourceCatalog.byType(ReSyncResourceCatalog.PROJECT_METADATA);
            }

            @Override
            public String get(String id) {
                return storage.getProjectMetadata(id);
            }

            @Override
            public List<String> listIds() {
                return storage.listProjectMetadataIds();
            }

            @Override
            public String deserialize(String json) {
                return storage.normalizeProjectMetadataPayload(json);
            }

            @Override
            public String serialize(String value) {
                return storage.normalizeProjectMetadataPayload(value);
            }

            @Override
            public String id(String value) {
                return storage.projectMetadataResourceId();
            }

            @Override
            public void save(String value) {
                storage.saveProjectMetadata(value);
            }

            @Override
            public void delete(String id) {
                storage.deleteProjectMetadata(id);
            }

            @Override
            public boolean supportsAuthoritativeMutationIdentity() {
                return true;
            }

            @Override
            public void save(String value, UUID mutationId, long expectedRevision) {
                storage.saveProjectMetadata(value, mutationId, expectedRevision);
            }

            @Override
            public void delete(String id, UUID mutationId, long expectedRevision) {
                storage.deleteProjectMetadata(id, mutationId, expectedRevision);
            }

            @Override
            public FlowResourceMutationStamp readMutationStamp(String id) {
                FlowStorage.ResourceIdentity identity = storage.readProjectMetadataIdentity(id);
                return identity == null ? null : new FlowResourceMutationStamp(identity.type(), identity.id(),
                    identity.revision(), UUID.fromString(identity.mutationId()), identity.payloadHash(), identity.deleted());
            }

            @Override
            public FlowResourceMutationStamp recoverProjectMetadataLineage(UUID sourceMutationId, String sourceType,
                                                                            String sourceId, long sourceRevision,
                                                                            String sourceHash,
                                                                            long previousMetadataRevision,
                                                                            UUID previousMetadataMutationId,
                                                                            String previousMetadataHash) {
                FlowStorage.ResourceIdentity identity = storage.recoverProjectMetadataLineage(sourceMutationId,
                    sourceType, sourceId, sourceRevision, sourceHash, previousMetadataRevision,
                    previousMetadataMutationId, previousMetadataHash);
                return new FlowResourceMutationStamp(identity.type(), identity.id(), identity.revision(),
                    UUID.fromString(identity.mutationId()), identity.payloadHash(), identity.deleted());
            }
        });
        registerLegacyAdapter(registry, ReSyncResourceCatalog.TEXT_TEMPLATE, legacy);
        return registry;
    }

    private static void registerLegacyAdapter(FlowResourceRegistry registry, String type,
                                              JsonAssetStore<JsonObject> legacy) {
        registry.register(new FlowResourceAdapter<JsonObject>() {
            @Override
            public restudio.resync.resources.ReSyncManagedResource descriptor() {
                return ReSyncResourceCatalog.byType(type);
            }

            @Override
            public JsonObject get(String id) {
                JsonObject value = legacy.get(id);
                return value == null ? null : JsonAssetStore.logicalPayload(value);
            }

            @Override
            public List<String> listIds() {
                return legacy.listIds();
            }

            @Override
            public JsonObject deserialize(String json) {
                return JsonParser.parseString(json).getAsJsonObject();
            }

            @Override
            public String serialize(JsonObject value) {
                return canonicalJson(value);
            }

            @Override
            public String id(JsonObject value) {
                return value.get("id").getAsString();
            }

            @Override
            public void save(JsonObject value) {
                legacy.save(value);
            }

            @Override
            public void delete(String id) {
                legacy.delete(id);
            }

            @Override
            public boolean supportsAuthoritativeMutationIdentity() {
                return true;
            }

            @Override
            public void save(JsonObject value, UUID mutationId, long expectedRevision) {
                legacy.save(value, mutationId, expectedRevision);
            }

            @Override
            public void delete(String id, UUID mutationId, long expectedRevision) {
                legacy.delete(id, mutationId, expectedRevision);
            }

            @Override
            public FlowResourceMutationStamp readMutationStamp(String id) {
                JsonAssetStore.AssetStamp stamp = legacy.readStamp(id);
                return stamp == null ? null : new FlowResourceMutationStamp(stamp.type(), stamp.id(), stamp.revision(),
                    stamp.mutationId(), stamp.payloadHash(), stamp.deleted());
            }
        });
    }

    private static void registerScoreboardAdapter(FlowResourceRegistry registry, FlowStorage storage) {
        registry.register(new FlowResourceAdapter<ScoreboardDefinition>() {
            @Override
            public restudio.resync.resources.ReSyncManagedResource descriptor() {
                return ReSyncResourceCatalog.byType(ReSyncResourceCatalog.SCOREBOARD);
            }

            @Override
            public ScoreboardDefinition get(String id) {
                return storage.getScoreboard(id);
            }

            @Override
            public List<String> listIds() {
                return storage.listScoreboardIds();
            }

            @Override
            public ScoreboardDefinition deserialize(String json) {
                return FlowSerializer.deserializeScoreboard(json);
            }

            @Override
            public String serialize(ScoreboardDefinition value) {
                return FlowSerializer.serializeScoreboard(value);
            }

            @Override
            public String id(ScoreboardDefinition value) {
                return value.getId();
            }

            @Override
            public void save(ScoreboardDefinition value) {
                storage.saveScoreboard(value);
            }

            @Override
            public void delete(String id) {
                storage.deleteScoreboard(id);
            }

            @Override
            public boolean supportsAuthoritativeMutationIdentity() {
                return true;
            }

            @Override
            public void save(ScoreboardDefinition value, UUID mutationId, long expectedRevision) {
                storage.saveScoreboard(value, mutationId, expectedRevision);
            }

            @Override
            public void delete(String id, UUID mutationId, long expectedRevision) {
                storage.deleteScoreboard(id, mutationId, expectedRevision);
            }

            @Override
            public FlowResourceMutationStamp readMutationStamp(String id) {
                FlowStorage.ResourceIdentity identity = storage.readResourceIdentity(ReSyncResourceCatalog.SCOREBOARD, id);
                return identity == null ? null : new FlowResourceMutationStamp(identity.type(), identity.id(), identity.revision(),
                    UUID.fromString(identity.mutationId()), identity.payloadHash(), identity.deleted());
            }
        });
    }

    private static JsonAssetStore<JsonObject> legacyAdminStore(Path assets, Path directory, String type,
                                                                AssetTransactionCoordinator coordinator) {
        return new JsonAssetStore<>(assets, directory.resolve("legacy-" + type), type,
            ReSyncResourceCatalog.defaultFolder(type), json -> JsonParser.parseString(json).getAsJsonObject(),
            SqliteProtocolProjectMetadataMutationTest::canonicalJson, value -> value.get("id").getAsString(),
            value -> value.get("path").getAsString(), LegacyRuntimeActivationGate.runtime(directory), coordinator,
            () -> true, () -> () -> {
            }, (value, existing, serialized) -> serialized,
            ProjectMetadataLineage.writer(assets, new GsonBuilder().setPrettyPrinting().create()));
    }

    private static JsonAssetStore<JsonObject> legacyLineageGapStore(Path assets, Path directory,
                                                                     AssetTransactionCoordinator coordinator) {
        return new JsonAssetStore<>(assets, directory.resolve("legacy-lineage-gap"),
            ReSyncResourceCatalog.TEXT_TEMPLATE, "Text/Templates",
            json -> JsonParser.parseString(json).getAsJsonObject(),
            SqliteProtocolProjectMetadataMutationTest::canonicalJson, value -> value.get("id").getAsString(),
            value -> value.has("path") ? value.get("path").getAsString() : "Text/Templates",
            LegacyRuntimeActivationGate.runtime(directory), coordinator, () -> true);
    }

    private static String projectMetadata(String... ids) {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("serverId", "project");
        JsonArray folders = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("path", "Text");
        text.addProperty("parentPath", "");
        text.addProperty("name", "Text");
        text.addProperty("sortOrder", 0);
        text.addProperty("collapsed", false);
        folders.add(text);
        JsonObject templates = new JsonObject();
        templates.addProperty("path", "Text/Templates");
        templates.addProperty("parentPath", "Text");
        templates.addProperty("name", "Templates");
        templates.addProperty("sortOrder", 0);
        templates.addProperty("collapsed", false);
        folders.add(templates);
        metadata.add("folders", folders);
        JsonArray resources = new JsonArray();
        for (String id : ids) {
            JsonObject resource = new JsonObject();
            resource.addProperty("type", ReSyncResourceCatalog.TEXT_TEMPLATE);
            resource.addProperty("id", id);
            resource.addProperty("path", "Text/Templates/" + id + ".json");
            resources.add(resource);
        }
        metadata.add("resources", resources);
        return metadata.toString();
    }

    private static JsonObject textTemplate(String id, String value) {
        JsonObject resource = new JsonObject();
        resource.addProperty("id", id);
        resource.addProperty("path", "Text/Templates/" + id + ".json");
        resource.addProperty("value", value);
        return resource;
    }

    private static ServerResourceLocator textResource(String id) {
        return new ServerResourceLocator(SERVER,
            ContractRef.of(new OwnerId("restudio.resync"),
                new ResourceTypeId(ReSyncResourceCatalog.TEXT_TEMPLATE)), id);
    }

    private static void insertPendingSave(Path database, ServerResourceLocator resource, UUID mutationId,
                                          long expectedRevision, String preconditionHash, JsonObject result,
                                          long createdAt) throws Exception {
        String payload = canonicalJson(result);
        String canonicalResource = resource.canonicalText();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO resource_mutation_receipt(mutation_id, actor_id, fingerprint, operation,
                     requested_resource, response_resource, source_resource, target_resource, target_activation_state,
                     expected_revision, precondition_hash, status, result_revision, result_mutation_id, result_hash,
                     result_deleted, result_activation_state, result_payload, precondition_asset_hash,
                     precondition_core_payload_hash, precondition_core_payload_kind, result_asset_hash,
                     result_core_payload_hash, result_core_payload_kind, sequence, error_code, error_message,
                     created_at, updated_at)
                 VALUES(?, ?, ?, 'SAVE', ?, ?, NULL, ?, NULL, ?, ?, 'PENDING', ?, ?, ?, 0, 'active', ?,
                     NULL, NULL, NULL, NULL, NULL, NULL, 0, '', '', ?, ?)
                 """)) {
            statement.setString(1, mutationId.toString());
            statement.setString(2, "history-fixture");
            statement.setString(3, "history-fixture:" + mutationId);
            statement.setString(4, canonicalResource);
            statement.setString(5, canonicalResource);
            statement.setString(6, canonicalResource);
            statement.setLong(7, expectedRevision);
            statement.setString(8, preconditionHash);
            statement.setLong(9, expectedRevision + 1L);
            statement.setString(10, mutationId.toString());
            statement.setString(11, hash(payload).canonicalText());
            statement.setString(12, payload);
            statement.setLong(13, createdAt);
            statement.setLong(14, createdAt);
            statement.executeUpdate();
        }
    }

    private static long persistedStateRevision(Path database, ServerResourceLocator resource) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT revision FROM resource_mutation_state WHERE resource = ?")) {
            statement.setString(1, resource.canonicalText());
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new AssertionError("Missing persisted mutation state: " + resource.canonicalText());
                }
                return result.getLong(1);
            }
        }
    }

    private static String persistedStateMutation(Path database, ServerResourceLocator resource) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT mutation_id FROM resource_mutation_state WHERE resource = ?")) {
            statement.setString(1, resource.canonicalText());
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new AssertionError("Missing persisted mutation state: " + resource.canonicalText());
                }
                return result.getString(1);
            }
        }
    }

    private static List<String> projectMetadataResourceIds(String json) {
        return JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("resources").asList().stream()
            .map(JsonElement::getAsJsonObject).map(resource -> resource.get("id").getAsString()).toList();
    }

    private static JsonObject resourcePayload(String id, String path) {
        JsonObject value = new JsonObject();
        value.addProperty("id", id);
        value.addProperty("path", path);
        return value;
    }

    private static long legacyReceiptCount(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             var result = statement.executeQuery(
                 "SELECT COUNT(*) FROM resource_mutation_receipt WHERE actor_id = 'legacy-unattributed'")) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static CoreGraphResourceAuthority failAfterLegacyRecovery(CoreGraphResourceAuthority delegate) {
        AtomicBoolean fail = new AtomicBoolean(true);
        return (CoreGraphResourceAuthority) Proxy.newProxyInstance(CoreGraphResourceAuthority.class.getClassLoader(),
            new Class<?>[]{CoreGraphResourceAuthority.class}, (proxy, method, arguments) -> {
                try {
                    Object result = method.invoke(delegate, arguments);
                    if ("recoverLegacyDelete".equals(method.getName()) && fail.getAndSet(false)) {
                        throw new IllegalStateException("Simulated SQLite settlement loss");
                    }
                    return result;
                } catch (InvocationTargetException exception) {
                    throw exception.getCause();
                }
            });
    }

    private static void assertLegacyAdminCoreRecoveryPending(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT status FROM resource_mutation_receipt "
                 + "WHERE actor_id = 'legacy-admin-recovery'")) {
            assertTrue(result.next());
            assertEquals("PENDING", result.getString("status"));
            assertFalse(result.next());
        }
    }

    private static void assertLegacyAdminRecovery(Path database, List<UUID> mutations, UUID coreMutation,
                                                  UUID metadataMutation, String metadataHash) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            List<String> hashes = new ArrayList<>();
            try (var result = statement.executeQuery("""
                SELECT mutation_id, operation, expected_revision, status, result_revision, result_deleted,
                    result_hash, precondition_hash, result_asset_hash, result_core_payload_hash,
                    result_core_payload_kind, transition_hash, transition_published
                FROM resource_mutation_receipt
                WHERE actor_id = 'legacy-unattributed'
                ORDER BY sequence
                """)) {
                for (int index = 0; index < mutations.size(); index++) {
                    assertTrue(result.next());
                    assertEquals(mutations.get(index).toString(), result.getString("mutation_id"));
                    boolean deleted = index >= 4;
                    assertEquals(deleted ? "DELETE" : "CREATE", result.getString("operation"));
                    long expectedRevision = deleted ? 1L : 0L;
                    assertEquals(expectedRevision, result.getLong("expected_revision"));
                    assertEquals("APPLIED", result.getString("status"));
                    assertEquals(expectedRevision + 1L, result.getLong("result_revision"));
                    assertEquals(deleted ? 1 : 0, result.getInt("result_deleted"));
                    hashes.add(result.getString("result_hash"));
                    if (deleted) {
                        assertEquals(result.getString("result_hash"), result.getString("precondition_hash"));
                    }
                    if (mutations.get(index).equals(coreMutation)) {
                        assertFalse(result.getString("result_asset_hash").isBlank());
                        assertFalse(result.getString("result_core_payload_hash").isBlank());
                        assertFalse(result.getString("result_core_payload_kind").isBlank());
                        assertFalse(result.getString("transition_hash").isBlank());
                        assertEquals(1, result.getInt("transition_published"));
                    }
                }
                assertFalse(result.next());
            }
            assertEquals(hashes.get(0), hashes.get(6));
            assertEquals(hashes.get(1), hashes.get(4));
            assertEquals(hashes.get(2), hashes.get(5));
            try (var result = statement.executeQuery("SELECT revision, mutation_id, payload_hash FROM resource_mutation_state "
                + "WHERE resource LIKE '%/project_metadata/%'")) {
                assertTrue(result.next());
                assertEquals(9L, result.getLong("revision"));
                assertEquals(metadataMutation.toString(), result.getString("mutation_id"));
                assertEquals(metadataHash, result.getString("payload_hash"));
                assertFalse(result.next());
            }
        }
    }

    private static void assertLegacyAdminCoreRecovery(Path database, List<UUID> mutations, UUID coreMutation,
                                                       String metadataHash) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT mutation_id, result_core_payload_kind, transition_hash "
                + "FROM resource_mutation_receipt WHERE actor_id = 'legacy-unattributed' ORDER BY sequence")) {
                for (UUID mutation : mutations) {
                    assertTrue(result.next());
                    assertEquals(mutation.toString(), result.getString("mutation_id"));
                    if (mutation.equals(coreMutation)) {
                        assertEquals("legacy-flow-graph-v3", result.getString("result_core_payload_kind"));
                        assertTrue(result.getString("transition_hash") == null || result.getString("transition_hash").isBlank());
                    }
                }
                assertFalse(result.next());
            }
            String recoveryMutation;
            try (var result = statement.executeQuery("SELECT mutation_id, operation, expected_revision, status, "
                + "result_revision, result_deleted, result_core_payload_kind, transition_hash, transition_published "
                + "FROM resource_mutation_receipt WHERE actor_id = 'legacy-admin-recovery'")) {
                assertTrue(result.next());
                recoveryMutation = result.getString("mutation_id");
                assertEquals("DELETE", result.getString("operation"));
                assertEquals(1L, result.getLong("expected_revision"));
                assertEquals("APPLIED", result.getString("status"));
                assertEquals(2L, result.getLong("result_revision"));
                assertEquals(1, result.getInt("result_deleted"));
                assertEquals("legacy-flow-graph-v3", result.getString("result_core_payload_kind"));
                assertFalse(result.getString("transition_hash").isBlank());
                assertEquals(1, result.getInt("transition_published"));
                assertFalse(result.next());
            }
            try (var result = statement.executeQuery("SELECT revision, mutation_id, payload_hash, deleted, "
                + "core_payload_kind FROM resource_mutation_state WHERE resource LIKE '%/flow/codex_runtime_probe_test'")) {
                assertTrue(result.next());
                assertEquals(2L, result.getLong("revision"));
                assertEquals(recoveryMutation, result.getString("mutation_id"));
                assertEquals(1, result.getInt("deleted"));
                assertEquals("legacy-flow-graph-v3", result.getString("core_payload_kind"));
                assertFalse(result.next());
            }
            try (var result = statement.executeQuery("SELECT revision, mutation_id, payload_hash FROM resource_mutation_state "
                + "WHERE resource LIKE '%/project_metadata/%'")) {
                assertTrue(result.next());
                assertEquals(10L, result.getLong("revision"));
                assertEquals(recoveryMutation, result.getString("mutation_id"));
                assertEquals(metadataHash, result.getString("payload_hash"));
                assertFalse(result.next());
            }
        }
    }

    private static SqliteProtocolResourceMutationAuthority authority(FlowResourceRegistry registry, Path directory) {
        return new SqliteProtocolResourceMutationAuthority(registry, SERVER, directory.resolve("resource.db"),
            CoreGraphResourceAuthority.unavailable(), AuthorityEpoch.fixed(1L));
    }

    private static SqliteProtocolResourceMutationAuthority authority(FlowResourceRegistry registry, Path directory,
                                                                      CoreGraphResourceAuthority coreAuthority) {
        return new SqliteProtocolResourceMutationAuthority(registry, SERVER, directory.resolve("resource.db"),
            coreAuthority, AuthorityEpoch.fixed(1L));
    }

    private static ServerResourceLocator resource() {
        return resource("project");
    }

    private static ServerResourceLocator resource(String id) {
        return new ServerResourceLocator(SERVER, TYPE, id);
    }

    private static ServerResourceLocator canonicalResource() {
        return resource(SERVER.canonicalText());
    }

    private static ProtocolEnvelope<Map<String, Object>> createCore(ServerResourceLocator resource, UUID mutationId,
                                                                     CoreGraphStorageBoundary boundary) {
        return createCore(resource, mutationId, boundary, GRAPH_BINDING);
    }

    private static ProtocolEnvelope<Map<String, Object>> createCore(ServerResourceLocator resource, UUID mutationId,
                                                                     CoreGraphStorageBoundary boundary, CatalogBinding binding) {
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, 1L, binding, Set.of(),
            List.of(), List.of(), List.of(), List.of(), OpaqueData.of(Map.of("test", "metadata-drift")));
        byte[] bytes = boundary.encode(graph, new CoreGraphStorageBoundary.AssetMetadata(resource.resourceType().value(),
            1L, mutationId, ResourceActivationState.ACTIVE), resource);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) CanonicalJson.parse(bytes);
        CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(payload);
        return envelope(resource, new ResourceCreateRequest<>(resource, canonical, mutationId));
    }

    private static ProtocolEnvelope<Map<String, Object>> saveCore(ServerResourceLocator resource, UUID mutationId,
                                                                   CoreGraphStorageBoundary boundary, long expectedRevision) {
        long revision = expectedRevision + 1L;
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, revision, GRAPH_BINDING, Set.of(),
            List.of(), List.of(), List.of(), List.of(), OpaqueData.of(Map.of("test", "metadata-neutral-save")));
        byte[] bytes = boundary.encode(graph, new CoreGraphStorageBoundary.AssetMetadata(resource.resourceType().value(),
            revision, mutationId, ResourceActivationState.ACTIVE), resource);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) CanonicalJson.parse(bytes);
        CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(payload);
        return envelope(resource, new ResourceSaveRequest<>(resource, expectedRevision, canonical, mutationId));
    }

    private static ProtocolEnvelope<Map<String, Object>> save(ServerResourceLocator resource, String json,
                                                                UUID mutationId, long expectedRevision) {
        Map<String, Object> payload = new Gson().fromJson(json, Map.class);
        CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(payload);
        return envelope(resource, new ResourceSaveRequest<>(resource, expectedRevision, canonical, mutationId));
    }

    private static ProtocolEnvelope<Map<String, Object>> delete(ServerResourceLocator resource, UUID mutationId,
                                                                  long expectedRevision) {
        return envelope(resource, new ResourceDeleteRequest(resource, expectedRevision, mutationId));
    }

    private static ProtocolEnvelope<Map<String, Object>> create(ServerResourceLocator resource, String json, UUID mutationId) {
        Map<String, Object> payload = new Gson().fromJson(json, Map.class);
        CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(payload);
        return envelope(resource, new ResourceCreateRequest<>(resource, canonical, mutationId));
    }

    private static ProtocolEnvelope<Map<String, Object>> activate(ServerResourceLocator resource,
                                                                    ResourceActivationState state, UUID mutationId,
                                                                    long expectedRevision) {
        return envelope(resource, new ResourceActivateRequest(resource, expectedRevision, state, mutationId));
    }

    private static ProtocolEnvelope<Map<String, Object>> envelope(ServerResourceLocator resource,
                                                                   ResourceOperation operation) {
        var contractVersion = operation instanceof ResourceActivateRequest
            ? ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION
            : ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_MINIMUM_VERSION;
        Set<ContractRef<CapabilityId>> capabilities = operation instanceof ResourceActivateRequest
            ? Set.of(ContractRef.of(new OwnerId("restudio.resync"), new CapabilityId("resources")),
                ReSyncProtocolContract.RESOURCE_ACTIVATION_CAPABILITY)
            : Set.of(ContractRef.of(new OwnerId("restudio.resync"), new CapabilityId("resources")));
        long revision = operation instanceof ResourceSaveRequest<?> save ? save.expectedRevision()
            : operation instanceof ResourceDeleteRequest delete ? delete.expectedRevision()
            : operation instanceof ResourceActivateRequest activate ? activate.expectedRevision() : 0L;
        ContentHash payloadHash = operation instanceof ResourceCreateRequest<?> create ? create.payloadHash()
            : operation instanceof ResourceSaveRequest<?> save ? save.payloadHash() : null;
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.REQUEST, contractVersion,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), SERVER, resource, revision, 1L,
            mutation(operation), ContractRef.of(new OwnerId("restudio.resync"),
                new OperationId("resource." + operation.kind().name().toLowerCase())),
            capabilities,
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId("resource.document")), null, payloadHash,
            false, null, null, null, null, null, 0, ProtocolEnvelope.Status.ACCEPTED, List.of(), Map.of(),
            new ProtocolBody.ResourceRequest(operation));
    }

    private static UUID mutation(ResourceOperation operation) {
        return switch (operation) {
            case ResourceCreateRequest<?> create -> create.mutationId();
            case ResourceSaveRequest<?> save -> save.mutationId();
            case ResourceDeleteRequest delete -> delete.mutationId();
            case ResourceActivateRequest activate -> activate.mutationId();
            default -> throw new IllegalArgumentException("Unsupported test operation");
        };
    }

    private static ContentHash hash(String json) {
        return ResourcePayloadCodecs.json().hashPayload(new Gson().fromJson(json, Map.class));
    }

    @SuppressWarnings("unchecked")
    private static String canonicalJson(JsonObject value) {
        Map<String, Object> payload = new Gson().fromJson(value, Map.class);
        return ResourcePayloadCodecs.json().canonicalInput(
            ResourcePayloadCodecs.json().canonicalize(payload).value());
    }

    private static long mutationReceiptCount(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM resource_mutation_receipt")) {
            return result.next() ? result.getLong(1) : 0L;
        }
    }

    private static String mutationStatus(Path database, UUID mutationId) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             var statement = connection.prepareStatement(
                 "SELECT status FROM resource_mutation_receipt WHERE mutation_id = ?")) {
            statement.setString(1, mutationId.toString());
            try (var result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : "";
            }
        }
    }

    private static ProtocolEnvelopeDispatchResult mutate(SqliteProtocolResourceMutationAuthority authority,
                                                          ProtocolEnvelope<Map<String, Object>> request) {
        ConnectionInfo connection = authenticatedConnection("client");
        return authority.mutate(connection, session(connection, "client"), request,
            ((ProtocolBody.ResourceRequest) request.body()).operation());
    }

    @SuppressWarnings("unchecked")
    private static ResourceDocument<Map<String, Object>> document(ProtocolEnvelopeDispatchResult result) {
        ProtocolBody.ResourceDocumentResponse body = assertInstanceOf(ProtocolBody.ResourceDocumentResponse.class,
            result.response().body());
        return (ResourceDocument<Map<String, Object>>) (ResourceDocument<?>) body.document();
    }

    private static ConnectionInfo authenticatedConnection(String clientId) {
        ConnectionInfo connection = new ConnectionInfo(null, new FrameSender() {
            @Override
            public void send(byte[] frame) {
            }

            @Override
            public void close(int code, String reason) {
            }
        }, 1);
        connection.setClientId(clientId);
        connection.setClientVersion("2.1.0");
        connection.setState(ConnectionState.AUTHENTICATED);
        connection.setProtocolResourceAccess(true);
        return connection;
    }

    private static Session session(ConnectionInfo connection, String clientId) {
        return new Session("session", clientId, connection, new ClientIdentity(clientId, "2.1.0"));
    }

    private static FlowResourceMutationListener failingMutationListener() {
        return new FlowResourceMutationListener() {
            @Override
            public void saved(String type, String resourceId, String payload) {
                throw new IllegalStateException("callback lost");
            }

            @Override
            public void deleted(String type, String resourceId) {
                throw new IllegalStateException("callback lost");
            }
        };
    }

    private static FlowResourceCommitListener failingCommitListener() {
        return new FlowResourceCommitListener() {
            @Override
            public void saved(String type, String resourceId, String payload) {
                throw new IllegalStateException("callback lost");
            }

            @Override
            public void deleted(String type, String resourceId) {
                throw new IllegalStateException("callback lost");
            }
        };
    }
}
