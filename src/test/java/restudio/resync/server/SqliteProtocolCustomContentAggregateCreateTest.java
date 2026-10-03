package restudio.resync.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.CustomContentGraphAdapter;
import restudio.flow.data.GuiDefinition;
import restudio.flow.data.ScoreboardDefinition;
import restudio.flow.data.TabDefinition;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.ConnectionState;
import restudio.resync.core.Session;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.customcontent.ItemAttributeSchemaService;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.flow.protocol.CanonicalPayload;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ResourceCreateRequest;
import restudio.resync.flow.protocol.ResourceOperation;
import restudio.resync.flow.protocol.ResourcePresentationIntent;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceMutationStamp;
import restudio.resync.modules.flow.FlowResourcePacketRouter;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.protocol.FrameSender;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.security.ClientIdentity;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteProtocolCustomContentAggregateCreateTest {
    private static final Gson GSON = new Gson();
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final ServerId SERVER = ServerId.deterministic("custom-content-aggregate-create");

    @Test
    void mismatchedGuiDefaultsRejectBeforeCreatingAnAsset(@TempDir Path temporary) throws Exception {
        AssetPersistenceGate gate = new AssetPersistenceGate(temporary);
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(temporary.resolve("assets"), GSON)) {
            FlowStorage storage = new FlowStorage(temporary.toFile(), LegacyRuntimeActivationGate.runtime(temporary),
                gate, SERVER, coordinator);
            long sequence = coordinator.read(snapshot -> snapshot.rootSequence());
            AggregateResourceCreateStorage.PreCommitRejection rejection = assertThrows(
                AggregateResourceCreateStorage.PreCommitRejection.class,
                () -> storage.createGui(new GuiDefinition("menu", "Main Menu", 3), UUID.randomUUID(), 0L,
                    new ResourcePresentationIntent("Main Menu", "Interfaces/Gui/menu.json", 0), "old-client-hash"));
            assertEquals("RESOURCE_PAYLOAD_INVALID", rejection.errorCode());
            assertEquals(sequence, coordinator.read(snapshot -> snapshot.rootSequence()).longValue());
            assertFalse(Files.exists(temporary.resolve("assets/Interfaces/Gui/menu.json")));
        } finally {
            gate.quiesce();
        }
    }

    @Test
    void protocolCreateCommitsRegisteredPayloadsAndPresentationUnderOneDurableMutation(@TempDir Path temporary) throws Exception {
        AssetPersistenceGate gate = new AssetPersistenceGate(temporary);
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(temporary.resolve("assets"), GSON)) {
            ItemAttributeSchemaService attributes = new ExactPayloadAttributes();
            FlowStorage flowStorage = new FlowStorage(temporary.toFile(), LegacyRuntimeActivationGate.runtime(temporary),
                gate, SERVER, coordinator);
            CustomContentStorage contentStorage = new CustomContentStorage(null, temporary,
                attributes, LegacyRuntimeActivationGate.runtime(temporary), gate, coordinator);
            try {
                AtomicReference<FlowResourceMutationStamp> admittedIdentity = new AtomicReference<>();
                contentStorage.setGraphAdmission((definition, intended) -> admittedIdentity.set(intended));
                FlowResourceRegistry registry = new FlowResourceRegistry();
                new FlowResourcePacketRouter(flowStorage, contentStorage, null, null, null, null, null,
                    registry, ignored -> {
                    }, attributes);
                ServerResourceLocator contentResource = resource(ReSyncResourceCatalog.CUSTOM_CONTENT, "steel-wand");
                assertTrue(registry.available(contentResource));

                try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(
                    registry, SERVER, temporary.resolve("resource-mutations.db"), CoreGraphResourceAuthority.unavailable(),
                    ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L), registry)) {
                    ServerResourceLocator metadataResource = resource(ReSyncResourceCatalog.PROJECT_METADATA,
                        SERVER.canonicalText());
                    Map<String, Object> metadataPayload = Map.of("serverId", SERVER.canonicalText(), "resources", List.of());
                    UUID metadataMutation = UUID.fromString("10000000-0000-4000-8000-000000000001");
                    ProtocolEnvelopeDispatchResult metadataResult = mutate(authority, envelope(metadataResource,
                        create(metadataResource, metadataPayload, metadataMutation, null)));
                    assertTrue(metadataResult.handled(), metadataResult.message());

                    CustomContentDefinition definition = CustomContentGraphAdapter.toDefinition(
                        CustomContentGraphAdapter.createContentGraph("steel-wand", "item", "Steel Wand"));
                    definition.setComponents(Map.of("minecraft:enchantment_glint_override", false));
                    FlowResourceAdapter<CustomContentDefinition> adapter = adapter(registry,
                        ReSyncResourceCatalog.CUSTOM_CONTENT);
                    Map<String, Object> contentPayload = GSON.fromJson(adapter.serialize(definition), Map.class);
                    Map<String, Object> graphPayload = (Map<String, Object>) contentPayload.get("graph");
                    Map<String, Object> nodePayloads = (Map<String, Object>) graphPayload.get("nodes");
                    nodePayloads.values().forEach(node -> ((Map<String, Object>) node).remove("handlerConfig"));
                    CustomContentDefinition storageValue = adapter.deserialize(GSON.toJson(contentPayload));
                    adapter.validate(storageValue);
                    UUID contentMutation = UUID.fromString("20000000-0000-4000-8000-000000000002");
                    ResourcePresentationIntent presentation = new ResourcePresentationIntent(
                        "Steel Wand", "Content/Items/steel-wand.json", 8);
                    ResourceCreateRequest<Map<String, Object>> contentCreate = create(contentResource, contentPayload,
                        contentMutation, presentation);
                    ProtocolEnvelope<Map<String, Object>> contentRequest = envelope(contentResource, contentCreate);
                    ProtocolEnvelopeDispatchResult contentResult = mutate(authority, contentRequest);

                    assertTrue(contentResult.handled(), contentResult.message());
                    assertNotNull(contentStorage.get("steel-wand"));
                    assertEquals(Map.of("minecraft:enchantment_glint_override", false),
                        contentStorage.get("steel-wand").getComponents());
                    assertEquals(contentMutation, contentStorage.readMutationStamp("steel-wand").mutationId());
                    assertEquals(contentMutation, admittedIdentity.get().mutationId());
                    assertEquals(1L, admittedIdentity.get().revision());
                    assertEquals(contentCreate.payloadHash().canonicalText(), admittedIdentity.get().payloadHash());
                    assertEquals(contentMutation, adapter(registry, ReSyncResourceCatalog.PROJECT_METADATA)
                        .readMutationStamp(SERVER.canonicalText()).mutationId());
                    assertTrue(Files.isRegularFile(temporary.resolve("assets/Content/Items/steel-wand.json")));
                    JsonObject storedContent = GSON.fromJson(
                        Files.readString(temporary.resolve("assets/Content/Items/steel-wand.json")), JsonObject.class);
                    storedContent.getAsJsonObject("graph").getAsJsonObject("nodes").entrySet().forEach(entry ->
                        assertFalse(entry.getValue().getAsJsonObject().has("handlerConfig")));
                    JsonObject metadata = GSON.fromJson(Files.readString(temporary.resolve("assets/project.json")),
                        JsonObject.class);
                    JsonObject entry = metadata.getAsJsonArray("resources").asList().stream()
                        .map(element -> element.getAsJsonObject())
                        .filter(element -> ReSyncResourceCatalog.CUSTOM_CONTENT.equals(element.get("type").getAsString())
                            && "steel-wand".equals(element.get("id").getAsString()))
                        .findFirst().orElseThrow();
                    assertEquals("Steel Wand", entry.get("displayName").getAsString());
                    assertEquals("Content/Items/steel-wand.json", entry.get("path").getAsString());
                    assertEquals(8, entry.get("sortOrder").getAsInt());
                    long contentSequence = coordinator.read(snapshot -> snapshot.rootSequence());
                    ProtocolEnvelopeDispatchResult contentReplay = mutate(authority, contentRequest);
                    assertTrue(contentReplay.handled(), contentReplay.message());
                    assertEquals(contentSequence, coordinator.read(snapshot -> snapshot.rootSequence()).longValue());
                    assertEquals(contentStorage.readMutationStamp("steel-wand").payloadHash(),
                        contentCreate.payloadHash().canonicalText());
                    assertTrue(contentStorage.create(storageValue, contentMutation, 0L, presentation,
                        contentCreate.payloadHash().canonicalText()).replayed());
                    assertEquals(contentSequence, coordinator.read(snapshot -> snapshot.rootSequence()).longValue());

                    CustomContentDefinition rejectedDefinition = CustomContentGraphAdapter.toDefinition(
                        CustomContentGraphAdapter.createContentGraph("rejected-item", "item", "Rejected Item"));
                    Map<String, Object> rejectedPayload = GSON.fromJson(adapter.serialize(rejectedDefinition), Map.class);
                    UUID rejectedMutation = UUID.fromString("25000000-0000-4000-8000-000000000002");
                    ResourceCreateRequest<Map<String, Object>> rejectedCreate = create(
                        resource(ReSyncResourceCatalog.CUSTOM_CONTENT, "rejected-item"), rejectedPayload,
                        rejectedMutation, new ResourcePresentationIntent(
                            "Rejected Item", "Content/Items/rejected-item.json", 9));
                    ProtocolEnvelope<Map<String, Object>> rejectedRequest = envelope(
                        resource(ReSyncResourceCatalog.CUSTOM_CONTENT, "rejected-item"), rejectedCreate);
                    contentStorage.setGraphAdmission((value, intended) -> {
                        throw new IllegalArgumentException("The item graph cannot run");
                    });
                    ProtocolEnvelopeDispatchResult rejectedResult = mutate(authority, rejectedRequest);
                    assertFalse(rejectedResult.handled());
                    assertEquals(ProtocolRejectionCode.RESOURCE_OPERATION_FAILED, rejectedResult.rejectionCode());
                    assertEquals(ProtocolRejectionCode.RESOURCE_OPERATION_FAILED.wireValue(), rejectedResult.code());
                    assertEquals(ProtocolRejectionCode.RESOURCE_OPERATION_FAILED.wireValue(), rejectedResult.structured().get("rejectionCode"));
                    assertEquals("The item graph cannot run", rejectedResult.message());
                    assertFalse(rejectedResult.message().contains("durable recovery"));
                    assertEquals(rejectedResult, mutate(authority, rejectedRequest));
                    assertEquals(contentSequence, coordinator.read(snapshot -> snapshot.rootSequence()).longValue());
                    assertFalse(Files.exists(temporary.resolve("assets/Content/Items/rejected-item.json")));
                    contentStorage.setGraphAdmission((value, intended) -> {
                    });

                    assertTypedCreate(authority, registry, coordinator, flowStorage, ReSyncResourceCatalog.GUI, "menu",
                        new GuiDefinition("menu", "Main Menu", 3), "Main Menu", "Interfaces/Gui/menu.json", 3,
                        UUID.fromString("30000000-0000-4000-8000-000000000003"), temporary);
                    assertTypedCreate(authority, registry, coordinator, flowStorage, ReSyncResourceCatalog.SCOREBOARD, "sidebar",
                        new ScoreboardDefinition("sidebar", "Status"), "Status", "Interfaces/Scoreboards/sidebar.json", 4,
                        UUID.fromString("40000000-0000-4000-8000-000000000004"), temporary);
                    assertTypedCreate(authority, registry, coordinator, flowStorage, ReSyncResourceCatalog.TAB, "players",
                        new TabDefinition("players"), "Players", "Interfaces/Tabs/players.json", 5,
                        UUID.fromString("50000000-0000-4000-8000-000000000005"), temporary);
                }
            } finally {
                gate.quiesce();
                contentStorage.close();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void assertTypedCreate(SqliteProtocolResourceMutationAuthority authority,
                                               FlowResourceRegistry registry,
                                               AssetTransactionCoordinator coordinator,
                                               FlowStorage storage,
                                               String type,
                                               String id,
                                               T value,
                                               String displayName,
                                               String path,
                                               int sortOrder,
                                               UUID mutationId,
                                               Path temporary) throws Exception {
        ServerResourceLocator resource = resource(type, id);
        FlowResourceAdapter<T> adapter = adapter(registry, type);
        assertTrue(registry.available(resource));
        Map<String, Object> payload = GSON.fromJson(adapter.serialize(value), Map.class);
        T storageValue = adapter.deserialize(GSON.toJson(payload));
        adapter.validate(storageValue);
        ResourcePresentationIntent presentation = new ResourcePresentationIntent(displayName, path, sortOrder);
        ResourcePresentationIntent canonicalPresentation =
            SqliteProtocolResourceMutationAuthority.canonicalCreatePresentation(resource, presentation);
        ResourceCreateRequest<Map<String, Object>> create = create(resource, payload, mutationId, presentation);
        ProtocolEnvelope<Map<String, Object>> request = envelope(resource, create);

        ProtocolEnvelopeDispatchResult result = mutate(authority, request);

        assertTrue(result.handled(), result.message());
        FlowResourceMutationStamp stamp = adapter.readMutationStamp(id);
        assertNotNull(stamp);
        assertEquals(mutationId, stamp.mutationId());
        assertEquals(1L, stamp.revision());
        assertEquals(mutationId, adapter(registry, ReSyncResourceCatalog.PROJECT_METADATA)
            .readMutationStamp(SERVER.canonicalText()).mutationId());
        assertTrue(Files.isRegularFile(temporary.resolve("assets").resolve(canonicalPresentation.path())));
        JsonObject metadata = GSON.fromJson(Files.readString(temporary.resolve("assets/project.json")), JsonObject.class);
        JsonObject entry = metadata.getAsJsonArray("resources").asList().stream()
            .map(element -> element.getAsJsonObject())
            .filter(element -> type.equals(element.get("type").getAsString())
                && id.equals(element.get("id").getAsString()))
            .findFirst().orElseThrow();
        assertEquals(displayName, entry.get("displayName").getAsString());
        assertEquals(canonicalPresentation.path(), entry.get("path").getAsString());
        assertEquals(sortOrder, entry.get("sortOrder").getAsInt());

        long sequence = coordinator.read(snapshot -> snapshot.rootSequence());
        String requestPayloadHash = create.payloadHash().canonicalText();
        assertEquals(stamp.payloadHash(), requestPayloadHash);
        FlowStorage.TypedAggregateCreate storageReplay = switch (type) {
            case ReSyncResourceCatalog.GUI -> storage.createGui((GuiDefinition) storageValue, mutationId, 0L,
                canonicalPresentation, requestPayloadHash);
            case ReSyncResourceCatalog.SCOREBOARD -> storage.createScoreboard((ScoreboardDefinition) storageValue,
                mutationId, 0L, canonicalPresentation, requestPayloadHash);
            case ReSyncResourceCatalog.TAB -> storage.createTab((TabDefinition) storageValue, mutationId, 0L,
                canonicalPresentation, requestPayloadHash);
            default -> throw new IllegalArgumentException("Unsupported typed aggregate test resource: " + type);
        };
        assertTrue(storageReplay.replayed());
        assertEquals(sequence, coordinator.read(snapshot -> snapshot.rootSequence()).longValue());
        ProtocolEnvelopeDispatchResult replay = mutate(authority, request);
        assertTrue(replay.handled(), replay.message());
        assertEquals(sequence, coordinator.read(snapshot -> snapshot.rootSequence()).longValue());
        assertEquals(stamp, adapter.readMutationStamp(id));
    }

    private static ResourceCreateRequest<Map<String, Object>> create(ServerResourceLocator resource,
                                                                      Map<String, Object> payload,
                                                                      UUID mutationId,
                                                                      ResourcePresentationIntent presentation) {
        CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(payload);
        return new ResourceCreateRequest<>(resource, canonical, mutationId, presentation);
    }

    private static ProtocolEnvelope<Map<String, Object>> envelope(ServerResourceLocator resource,
                                                                   ResourceOperation operation) {
        ResourceCreateRequest<?> create = (ResourceCreateRequest<?>) operation;
        Set<ContractRef<CapabilityId>> capabilities = create.presentation() == null
            ? Set.of(ContractRef.of(OWNER, CapabilityId.of("resources")))
            : Set.of(ContractRef.of(OWNER, CapabilityId.of("resources")),
                ReSyncProtocolContract.RESOURCE_CREATE_PRESENTATION_CAPABILITY);
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.REQUEST,
            ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION, UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), SERVER, resource, 0L, 1L,
            create.mutationId(), ContractRef.of(OWNER, OperationId.of("resource.create")), capabilities,
            ContractRef.of(OWNER, ResourceTypeId.of("resource.document")), null, create.payloadHash(), false,
            null, null, null, null, null, 0, ProtocolEnvelope.Status.ACCEPTED, List.of(), Map.of(),
            new ProtocolBody.ResourceRequest(operation));
    }

    private static ProtocolEnvelopeDispatchResult mutate(SqliteProtocolResourceMutationAuthority authority,
                                                           ProtocolEnvelope<Map<String, Object>> request) {
        ConnectionInfo connection = new ConnectionInfo(null, new FrameSender() {
            @Override
            public void send(byte[] frame) {
            }

            @Override
            public void close(int code, String reason) {
            }
        }, 1);
        connection.setClientId("custom-content-test");
        connection.setClientVersion("2.1.0");
        connection.setState(ConnectionState.AUTHENTICATED);
        connection.setProtocolResourceAccess(true);
        Session session = new Session("custom-content-session", "custom-content-test", connection,
            new ClientIdentity("custom-content-test", "2.1.0"));
        return authority.mutate(connection, session, request,
            ((ProtocolBody.ResourceRequest) request.body()).operation());
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER, ContractRef.of(OWNER, ResourceTypeId.of(type)), id);
    }

    @SuppressWarnings("unchecked")
    private static <T> FlowResourceAdapter<T> adapter(FlowResourceRegistry registry, String type) {
        return (FlowResourceAdapter<T>) registry.get(type);
    }

    private static final class ExactPayloadAttributes extends ItemAttributeSchemaService {
        @Override
        public Map<String, Object> customComponentsForMaterial(String materialName, Map<String, Object> components) {
            return Map.of();
        }

        @Override
        public List<Map<String, Object>> validate(String materialName, Map<String, Object> components) {
            return List.of();
        }
    }
}
