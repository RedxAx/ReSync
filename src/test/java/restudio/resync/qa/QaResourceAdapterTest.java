package restudio.resync.qa;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.ConnectionState;
import restudio.resync.core.Session;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogCapabilityDescriptor;
import restudio.resync.flow.catalog.CatalogCategoryDescriptor;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogContractRange;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.protocol.CanonicalPayload;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolEnvelopeCodec;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.flow.protocol.ResourceCreateRequest;
import restudio.resync.flow.protocol.ResourceDocument;
import restudio.resync.flow.protocol.ResourceOperation;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.flow.runtime.RuntimeBinding;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeProviderDescriptor;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypeValueCodec;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceMutationStamp;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.protocol.FrameSender;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.security.ClientIdentity;
import restudio.resync.server.AuthorityEpoch;
import restudio.resync.server.CoreGraphResourceAuthority;
import restudio.resync.server.FlowResourceProtocolEnvelopeHandler;
import restudio.resync.server.ProtocolEnvelopeDispatchResult;
import restudio.resync.server.ProtocolRequestAuthority;
import restudio.resync.server.ProtocolResourceAuthorizer;
import restudio.resync.server.SqliteProtocolResourceMutationAuthority;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaResourceAdapterTest {
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final ContractRef<ResourceTypeId> TYPE = ContractRef.of(OWNER, ResourceTypeId.of(ReSyncResourceCatalog.GUI));
    private static final CatalogSnapshot CATALOG = CatalogSnapshot.empty(ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION);
    private static final ProtocolEnvelopeCodec<Map<String, Object>> ENVELOPES = new ProtocolEnvelopeCodec<>(ResourcePayloadCodecs.json());
    private static final BiFunction<Integer, Runnable, CompletionStage<Void>> INLINE = (bytes, action) -> {
        action.run();
        return CompletableFuture.completedFuture(null);
    };

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void typedMutationsReadsRevisionConflictsAndTombstonesUseTheDurableAuthority(@TempDir Path directory) {
        Fixture fixture = new Fixture(directory, AuthorityEpoch.fixed(1L));
        CommandSender actor = sender("alice", true, false);
        try (fixture) {
            QaResourceAdapter qa = fixture.adapter(INLINE);
            Map<String, Object> create = payloadInput("main", "Draft");
            ResourceDocument<?> created = document(invoke(qa, actor, "resource.create", create));
            assertEquals(1L, created.revision());
            assertEquals(create.get("mutationId"), created.mutationId().toString());
            assertEquals("Draft", fixture.values.get("main").get("name").getAsString());

            Map<String, Object> stale = new LinkedHashMap<>(payloadInput("main", "Changed"));
            stale.put("expectedRevision", 0L);
            ProtocolEnvelope<Map<String, Object>> conflict = response(invoke(qa, actor, "resource.save", stale));
            assertEquals(ProtocolEnvelope.Status.CONFLICT, conflict.status());
            assertEquals("Draft", fixture.values.get("main").get("name").getAsString());

            Map<String, Object> save = new LinkedHashMap<>(payloadInput("main", "Changed"));
            save.put("expectedRevision", 1L);
            assertEquals(2L, document(invoke(qa, actor, "resource.save", save)).revision());
            ResourceDocument<?> loaded = document(invoke(qa, actor, "resource.load", Map.of("type", ReSyncResourceCatalog.GUI, "id", "main")));
            assertEquals(2L, loaded.revision());
            assertEquals("Changed", ((Map<?, ?>) loaded.payload()).get("name"));
            ProtocolBody.ResourcePageResponse page = assertInstanceOf(ProtocolBody.ResourcePageResponse.class,
                response(invoke(qa, actor, "resource.list", Map.of("type", ReSyncResourceCatalog.GUI))).body());
            assertEquals(1, page.page().items().size());

            ResourceDocument<?> duplicated = document(invoke(qa, actor, "resource.duplicate", Map.of("type", ReSyncResourceCatalog.GUI,
                "sourceId", "main", "targetId", "copy", "expectedRevision", 2L, "mutationId", UUID.randomUUID().toString())));
            assertEquals("copy", duplicated.resource().id());
            assertEquals(1L, duplicated.revision());
            ResourceDocument<?> inactive = document(invoke(qa, actor, "resource.activate", Map.of("type", ReSyncResourceCatalog.GUI,
                "id", "main", "expectedRevision", 2L, "activationState", "inactive", "mutationId", UUID.randomUUID().toString())));
            assertEquals(3L, inactive.revision());
            assertFalse(fixture.values.get("main").get("enabled").getAsBoolean());
            ResourceDocument<?> deleted = document(invoke(qa, actor, "resource.delete", Map.of("type", ReSyncResourceCatalog.GUI,
                "id", "main", "expectedRevision", 3L, "mutationId", UUID.randomUUID().toString())));
            assertEquals(4L, deleted.revision());
            assertTrue(deleted.deleted());
            assertNull(fixture.values.get("main"));
        }
    }

    @Test
    void operatorReceiptsRemainBoundToTheActorAndReplayAfterRestart(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        FixtureAdapter storage = new FixtureAdapter(values);
        Map<String, Object> create = payloadInput("replay", "Draft");
        CommandSender alice = sender("alice", true, false);
        try (Fixture first = new Fixture(directory, AuthorityEpoch.fixed(1L), storage)) {
            ResourceDocument<?> document = document(invoke(first.adapter(INLINE), alice, "resource.create", create));
            assertEquals(1L, document.revision());
        }
        try (Fixture second = new Fixture(directory, AuthorityEpoch.fixed(1L), storage)) {
            assertEquals(1L, document(invoke(second.adapter(INLINE), alice, "resource.create", create)).revision());
            Map<String, Object> denied = invoke(second.adapter(INLINE), sender("bob", true, false), "resource.create", create);
            assertEquals(ProtocolRejectionCode.RESOURCE_MUTATION_ACTOR_CONFLICT.wireValue(), denied.get("rejectionCode"));
            assertEquals(1L, storage.stamps.get("replay").revision());
        }
    }

    @Test
    void operatorGrantsBindTheExactRequestAndAreConsumedOnce(@TempDir Path directory) {
        try (Fixture fixture = new Fixture(directory, AuthorityEpoch.fixed(1L))) {
            ProtocolEnvelope<Map<String, Object>> request = createRequest("grant", UUID.randomUUID(), 1L);
            ProtocolRequestAuthority.OperatorGrant grant = ProtocolRequestAuthority.admitOperator(sender("alice", true, false), request);
            ProtocolEnvelope<Map<String, Object>> substituted = ENVELOPES.decode(ENVELOPES.encode(request));
            ResourceOperation operation = ((ProtocolBody.ResourceRequest) request.body()).operation();
            assertEquals(ProtocolRejectionCode.AUTHORIZATION_DENIED,
                fixture.authority.mutateOperator(grant, substituted, ((ProtocolBody.ResourceRequest) substituted.body()).operation()).rejectionCode());
            assertTrue(fixture.authority.mutateOperator(grant, request, operation).handled());
            assertEquals(ProtocolRejectionCode.AUTHORIZATION_DENIED, fixture.authority.mutateOperator(grant, request, operation).rejectionCode());
            assertEquals(1L, fixture.storage.stamps.get("grant").revision());
        }
    }

    @Test
    void deniedConsoleAndDetachedThreadCannotAdmitOperatorWork(@TempDir Path directory) throws Exception {
        CommandSender deniedConsole = sender("console", false, true);
        assertNull(ProtocolRequestAuthority.trustedOperatorId(deniedConsole));
        CompletableFuture<String> detachedIdentity = new CompletableFuture<>();
        Thread detached = new Thread(() -> detachedIdentity.complete(ProtocolRequestAuthority.trustedOperatorId(sender("alice", true, false))));
        detached.start();
        detached.join(2000L);
        assertTrue(detachedIdentity.isDone());
        assertNull(detachedIdentity.join());
        try (Fixture fixture = new Fixture(directory, AuthorityEpoch.fixed(1L))) {
            QaResourceAdapter qa = fixture.adapter(INLINE);
            for (String operation : List.of("catalog.inspect", "resource.discover", "resource.create", "resource.rename", "payload.canonicalize")) {
                Map<String, Object> denied = invoke(qa, deniedConsole, operation, payloadInput("denied", "Draft"));
                assertEquals(ProtocolRejectionCode.AUTHORIZATION_DENIED.wireValue(), denied.get("rejectionCode"));
            }
            assertTrue(fixture.values.isEmpty());
        }
    }

    @Test
    void resultWaitsForPhysicalWorkerExitAndInputIsImmutableAfterAdmission(@TempDir Path directory) {
        ControlledWorker worker = new ControlledWorker();
        try (Fixture fixture = new Fixture(directory, AuthorityEpoch.fixed(1L))) {
            QaResourceAdapter qa = fixture.adapter(worker);
            Map<String, Object> payload = new LinkedHashMap<>(Map.of("id", "physical", "name", "Admitted"));
            Map<String, Object> input = new LinkedHashMap<>(Map.of("type", ReSyncResourceCatalog.GUI, "id", "physical", "payload", payload,
                "payloadChecksum", ResourcePayloadCodecs.json().hashPayload(payload).canonicalText(), "mutationId", UUID.randomUUID().toString()));
            CompletableFuture<Map<String, Object>> pending = qa.invoke(sender("alice", true, false), "resource.create", input).toCompletableFuture();
            payload.put("name", "Too Late");
            input.put("id", "other");
            assertFalse(pending.isDone());
            worker.run();
            assertEquals("Admitted", fixture.values.get("physical").get("name").getAsString());
            assertFalse(pending.isDone());
            worker.exit.complete(null);
            assertEquals(1L, document(pending.join()).revision());
            assertThrows(UnsupportedOperationException.class, () -> pending.join().put("status", "tampered"));
        }
    }

    @Test
    void queuedRequestsRejectChangedEpochCatalogAndInstalledHandler(@TempDir Path directory) {
        AtomicLong epoch = new AtomicLong(1L);
        try (Fixture fixture = new Fixture(directory, new AuthorityEpoch(epoch::get))) {
            for (int changed = 0; changed < 3; changed++) {
                ControlledWorker worker = new ControlledWorker();
                QaResourceAdapter qa = fixture.adapter(worker);
                CompletableFuture<Map<String, Object>> pending = qa.invoke(sender("alice", true, false), "resource.create",
                    payloadInput("stale-" + changed, "Draft")).toCompletableFuture();
                if (changed == 0) epoch.incrementAndGet();
                if (changed == 1) fixture.catalog.set(CATALOG.withGeneration(2L));
                if (changed == 2) fixture.handler.set(null);
                worker.run();
                worker.exit.complete(null);
                assertEquals(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT.wireValue(), pending.join().get("rejectionCode"));
            }
            assertTrue(fixture.values.isEmpty());
        }
    }

    @Test
    void checksumAndIntegerValidationFailBeforeStorageAndCanonicalizeUsesTheRealPayloadCodec(@TempDir Path directory) {
        try (Fixture fixture = new Fixture(directory, AuthorityEpoch.fixed(1L))) {
            QaResourceAdapter qa = fixture.adapter(INLINE);
            CommandSender actor = sender("alice", true, false);
            Map<String, Object> invalid = new LinkedHashMap<>(payloadInput("invalid", "Draft"));
            invalid.put("payloadChecksum", "a".repeat(64));
            assertEquals(ProtocolRejectionCode.INVALID_PAYLOAD.wireValue(), invoke(qa, actor, "resource.create", invalid).get("rejectionCode"));
            invalid = new LinkedHashMap<>(payloadInput("invalid", "Draft"));
            invalid.put("expectedRevision", 1.5);
            assertEquals(ProtocolRejectionCode.INVALID_PAYLOAD.wireValue(), invoke(qa, actor, "resource.save", invalid).get("rejectionCode"));
            Map<String, Object> payload = Map.of("id", "pure", "name", "A", "pins", List.of(Map.of("value", 1)));
            Map<String, Object> canonical = invoke(qa, actor, "payload.canonicalize", Map.of("payload", payload));
            assertEquals(ResourcePayloadCodecs.json().hashPayload(payload).canonicalText(), canonical.get("payloadChecksum"));
            assertThrows(UnsupportedOperationException.class, () -> ((Map<String, Object>) canonical.get("payload")).put("name", "tampered"));
            assertTrue(fixture.values.isEmpty());
        }
    }

    @Test
    void remoteAuthenticatedClientCannotImpersonateLocalOperatorReceiptIdentity(@TempDir Path directory) {
        try (Fixture fixture = new Fixture(directory, AuthorityEpoch.fixed(1L))) {
            ProtocolEnvelope<Map<String, Object>> request = createRequest("reserved", UUID.randomUUID(), 1L);
            String localIdentity = ProtocolRequestAuthority.trustedOperatorId(sender("alice", true, false));
            ConnectionInfo connection = new ConnectionInfo(null, new FrameSender() {
                @Override
                public void send(byte[] frame) {
                }

                @Override
                public void close(int code, String reason) {
                }
            }, 1);
            connection.setClientId(localIdentity);
            connection.setState(ConnectionState.AUTHENTICATED);
            connection.setProtocolResourceAccess(true);
            Session session = new Session("reserved-session", localIdentity, connection, new ClientIdentity(localIdentity, "2.1.0"));
            ProtocolEnvelopeDispatchResult denied = fixture.authority.mutate(connection, session, request,
                ((ProtocolBody.ResourceRequest) request.body()).operation());
            assertEquals(ProtocolRejectionCode.AUTHORIZATION_DENIED, denied.rejectionCode());
            assertTrue(fixture.values.isEmpty());
        }
    }

    @Test
    void cyclicDeepAndNonfiniteDetachedInputsReturnStructuredRejections(@TempDir Path directory) {
        try (Fixture fixture = new Fixture(directory, AuthorityEpoch.fixed(1L))) {
            QaResourceAdapter qa = fixture.adapter(INLINE);
            CommandSender actor = sender("alice", true, false);
            Map<String, Object> cyclic = new LinkedHashMap<>();
            cyclic.put("payload", cyclic);
            Map<String, Object> deep = new LinkedHashMap<>();
            Map<String, Object> nested = deep;
            for (int depth = 0; depth < 70; depth++) {
                Map<String, Object> next = new LinkedHashMap<>();
                nested.put("child", next);
                nested = next;
            }
            for (Map<String, Object> invalid : List.<Map<String, Object>>of(cyclic, deep, Map.of("payload", Map.of("value", Double.NaN)))) {
                assertEquals(ProtocolRejectionCode.INVALID_PAYLOAD.wireValue(), invoke(qa, actor, "payload.canonicalize", invalid).get("rejectionCode"));
            }
            assertTrue(fixture.values.isEmpty());
        }
    }

    @Test
    void catalogInspectionReturnsAdmittedFunctionIdentityAndExactPinMetadata(@TempDir Path directory) {
        CatalogSnapshot snapshot = functionCatalog();
        CatalogNodeDescriptor expected = snapshot.definition(ContractRef.of(OWNER, NodeId.of("private-function-child")))
            .orElseThrow().descriptor();
        try (Fixture fixture = new Fixture(directory, AuthorityEpoch.fixed(7L))) {
            fixture.catalog.set(snapshot);
            Map<String, Object> page = invoke(fixture.adapter(INLINE), sender("alice", true, false), "catalog.inspect",
                Map.of("owner", OWNER.value(), "functionId", "qa_async_child"));
            assertEquals("handled", page.get("status"), page.toString());
            assertEquals(SERVER.canonicalText(), page.get("serverId"));
            assertEquals(7L, ((Number) page.get("authorityEpoch")).longValue());
            assertEquals(snapshot.generation(), ((Number) page.get("catalogGeneration")).longValue());
            assertEquals(snapshot.contentChecksum().canonicalText(), page.get("catalogChecksum"));
            assertEquals(snapshot.bindingManifestHash().canonicalText(), page.get("bindingManifestHash"));
            List<?> nodes = assertInstanceOf(List.class, page.get("nodes"));
            assertEquals(1, nodes.size());
            Map<?, ?> node = assertInstanceOf(Map.class, nodes.getFirst());
            assertEquals(expected.reference(OWNER).canonicalValue(), node.get("identity"));
            assertEquals(expected.id().value(), node.get("nodeId"));
            assertEquals(expected.schemaVersion(), ((Number) node.get("schemaVersion")).intValue());
            assertEquals(expected.displayName(), node.get("displayName"));
            assertEquals(expected.description(), node.get("description"));
            assertEquals(expected.metadata(), node.get("metadata"));
            assertEquals(Map.of("capability", expected.handler().capability().canonicalValue(),
                "operation", expected.handler().operation().canonicalValue()), node.get("handler"));
            assertEquals("failure", ((Map<?, ?>) ((List<?>) node.get("branches")).getFirst()).get("id"));
            List<?> pins = assertInstanceOf(List.class, node.get("pins"));
            assertEquals(expected.pins().size(), pins.size());
            for (int index = 0; index < pins.size(); index++) {
                CatalogNodeDescriptor.Pin pin = expected.pins().get(index);
                Map<?, ?> actual = assertInstanceOf(Map.class, pins.get(index));
                assertEquals(pin.id().value(), actual.get("id"));
                assertEquals(TypeValueCodec.INSTANCE.encodeType(pin.type()), JsonValue.fromJava(actual.get("type")));
                assertEquals(pin.requirement() == CatalogNodeDescriptor.Requirement.REQUIRED, actual.get("required"));
                assertEquals(pin.editor().canonicalValue(), actual.get("editor"));
                assertEquals(Map.of("kind", "always"), actual.get("visibility"));
                if (pin.defaultValue() == null) {
                    assertNull(actual.get("default"));
                } else {
                    assertEquals(TypeValueCodec.INSTANCE.encode(pin.defaultValue()), JsonValue.fromJava(actual.get("default")));
                }
                Map<?, ?> presentation = assertInstanceOf(Map.class, actual.get("presentation"));
                assertEquals(pin.presentation().constraints(), presentation.get("constraints"));
                assertEquals(pin.presentation().visibleWhen(), presentation.get("visibleWhen"));
                assertEquals(pin.presentation().options().stream().map(TypeValueCodec.INSTANCE::encode).toList(),
                    ((List<?>) presentation.get("options")).stream().map(JsonValue::fromJava).toList());
            }
            assertThrows(UnsupportedOperationException.class, () -> page.put("status", "changed"));
            assertThrows(UnsupportedOperationException.class, nodes::clear);
            assertThrows(UnsupportedOperationException.class, () -> ((Map<?, ?>) node.get("metadata")).clear());
            assertTrue(fixture.values.isEmpty());
        }
    }

    @Test
    void catalogInspectionPagesAndFiltersRequireValidCurrentIdentity(@TempDir Path directory) {
        CatalogSnapshot snapshot = functionCatalog();
        try (Fixture fixture = new Fixture(directory, AuthorityEpoch.fixed(7L))) {
            fixture.catalog.set(snapshot);
            QaResourceAdapter qa = fixture.adapter(INLINE);
            CommandSender actor = sender("alice", true, false);
            Map<String, Object> first = invoke(qa, actor, "catalog.inspect", Map.of("limit", 1));
            assertEquals("1", first.get("nextCursor"));
            Map<String, Object> second = invoke(qa, actor, "catalog.inspect", Map.of("limit", 1, "cursor", first.get("nextCursor"),
                "serverId", first.get("serverId"), "authorityEpoch", first.get("authorityEpoch"),
                "catalogGeneration", first.get("catalogGeneration"), "catalogChecksum", first.get("catalogChecksum"),
                "bindingManifestHash", first.get("bindingManifestHash")));
            assertEquals("private-function-child", ((Map<?, ?>) ((List<?>) first.get("nodes")).getFirst()).get("nodeId"));
            assertEquals("private-function-parent", ((Map<?, ?>) ((List<?>) second.get("nodes")).getFirst()).get("nodeId"));
            assertNull(second.get("nextCursor"));
            Map<String, Object> exact = invoke(qa, actor, "catalog.inspect", Map.of("owner", OWNER.value(), "nodeId", "private-function-child"));
            assertEquals(1, ((List<?>) exact.get("nodes")).size());
            for (Map<String, Object> filter : List.<Map<String, Object>>of(Map.of("owner", "other.owner"),
                Map.of("functionId", "QA_async_child"), Map.of("nodeId", "missing"), Map.of("cursor", "10"))) {
                Map<String, Object> empty = invoke(qa, actor, "catalog.inspect", filter);
                assertEquals("handled", empty.get("status"), empty.toString());
                assertTrue(((List<?>) empty.get("nodes")).isEmpty());
                assertNull(empty.get("nextCursor"));
            }
            for (Map<String, Object> invalid : List.<Map<String, Object>>of(Map.of("limit", 0), Map.of("limit", 201),
                Map.of("limit", 1.5), Map.of("cursor", "-1"), Map.of("cursor", "2147483648"), Map.of("unrecognized", true),
                Map.of("nodeId", "custom_function:qa_async_child"), Map.of("functionId", "../child"),
                Map.of("functionId", "x".repeat(129)))) {
                assertEquals(ProtocolRejectionCode.INVALID_PAYLOAD.wireValue(), invoke(qa, actor, "catalog.inspect", invalid).get("rejectionCode"));
            }
            assertEquals(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT.wireValue(),
                invoke(qa, actor, "catalog.inspect", Map.of("catalogGeneration", snapshot.generation() + 1L)).get("rejectionCode"));
            assertEquals(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT.wireValue(),
                invoke(qa, actor, "catalog.inspect", Map.of("catalogChecksum", "a".repeat(64))).get("rejectionCode"));
            fixture.catalog.set(snapshot.withGeneration(snapshot.generation() + 1L));
            assertEquals(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT.wireValue(), invoke(qa, actor, "catalog.inspect",
                Map.of("cursor", first.get("nextCursor"), "catalogGeneration", first.get("catalogGeneration"))).get("rejectionCode"));
            assertTrue(fixture.values.isEmpty());
        }
    }

    @Test
    void catalogInspectionRejectsIdentityChangesUntilPhysicalWorkerExit(@TempDir Path directory) {
        CatalogSnapshot snapshot = functionCatalog();
        AtomicLong epoch = new AtomicLong(7L);
        try (Fixture fixture = new Fixture(directory, new AuthorityEpoch(epoch::get))) {
            fixture.catalog.set(snapshot);
            CommandSender actor = sender("alice", true, false);
            ControlledWorker current = new ControlledWorker();
            CompletableFuture<Map<String, Object>> accepted = fixture.adapter(current).invoke(actor, "catalog.inspect", Map.of()).toCompletableFuture();
            current.run();
            assertFalse(accepted.isDone());
            current.exit.complete(null);
            assertEquals("handled", accepted.join().get("status"));
            for (boolean afterCapture : List.of(false, true)) {
                ControlledWorker worker = new ControlledWorker();
                CompletableFuture<Map<String, Object>> pending = fixture.adapter(worker).invoke(actor, "catalog.inspect", Map.of()).toCompletableFuture();
                if (afterCapture) {
                    worker.run();
                    epoch.incrementAndGet();
                } else {
                    fixture.catalog.set(snapshot.withGeneration(snapshot.generation() + 1L));
                    worker.run();
                }
                assertFalse(pending.isDone());
                worker.exit.complete(null);
                assertEquals(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT.wireValue(), pending.join().get("rejectionCode"));
                assertFalse(pending.join().containsKey("nodes"));
            }
            assertTrue(fixture.values.isEmpty());
        }
    }

    private static CatalogSnapshot functionCatalog() {
        ContractRef<CapabilityId> capability = ContractRef.of(OWNER, CapabilityId.of("function-handler"), Map.of("contractLabel", "Function"));
        ContractRef<OperationId> operationId = ContractRef.of(OWNER, OperationId.of("custom_function_call"));
        ContractRef<ProviderId> provider = ContractRef.of(OWNER, ProviderId.of("qa-function-provider"));
        ContractRef<CapabilityId> editor = ContractRef.of(OWNER, CapabilityId.of("generic-editor"), Map.of("editorLabel", "Value"));
        TypeExpr string = new TypeExpr.Named(new TypeReference("builtin", "string", Map.of("displayHints", Map.of("label", "Text"))),
            List.of(), Map.of("sourceHint", "Parameter"));
        TypeExpr execution = TypeExpr.named(TypeReference.of("builtin", "execution"));
        TypeExpr optional = TypeExpr.optional(string);
        TypeExpr list = TypeExpr.list(string);
        List<CatalogNodeDescriptor.Pin> pins = List.of(
            new CatalogNodeDescriptor.Pin("flow", CatalogNodeDescriptor.Direction.INPUT, execution, "Flow",
                "Starts this function invocation.", CatalogNodeDescriptor.Requirement.REQUIRED, editor),
            new CatalogNodeDescriptor.Pin(PinId.of("function-input-22222222-2222-4222-8222-222222222222"), CatalogNodeDescriptor.Direction.INPUT,
                string, "Value", "The required value passed to this function.", CatalogNodeDescriptor.Requirement.REQUIRED, null,
                editor, null, null, null, null, new CatalogNodeDescriptor.PinPresentation("text", List.of(TypedValue.value(string, "Ready")),
                    Map.of("minLength", "1"), Map.of("source", "Function"))),
            new CatalogNodeDescriptor.Pin(PinId.of("function-input-33333333-3333-4333-8333-333333333333"), CatalogNodeDescriptor.Direction.INPUT,
                optional, "Label", "An optional label supplied to this function.", CatalogNodeDescriptor.Requirement.DEFAULTED,
                TypedValue.nullValue(optional, Map.of("origin", "Source")), editor, null, null, null),
            new CatalogNodeDescriptor.Pin(PinId.of("function-input-44444444-4444-4444-8444-444444444444"), CatalogNodeDescriptor.Direction.INPUT,
                list, "Tags", "The ordered tags supplied to this function.", CatalogNodeDescriptor.Requirement.DEFAULTED,
                TypedValue.value(list, List.of(), Map.of("origin", "Source")), editor, null, null, null),
            new CatalogNodeDescriptor.Pin("output_flow", CatalogNodeDescriptor.Direction.OUTPUT, execution, "Flow",
                "Continues after this function returns.", CatalogNodeDescriptor.Requirement.OPTIONAL, editor));
        RuntimeSemantics semantics = new RuntimeSemantics(RuntimeSemantics.Effect.PURE, RuntimeSemantics.ThreadMode.CURRENT,
            ContractRef.of(OWNER, CapabilityId.of("authorize")), RuntimeSemantics.Cancellation.NONE, 0, 0, 0,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC,
            RuntimeSemantics.Audit.NONE, RuntimeSemantics.Confirmation.NONE, RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC, Set.of(), Set.of("failure"), Set.of(),
            new RuntimeFailureContract(string, Set.of("RUNTIME.FAILURE"), Set.of("failure"), RuntimeFailureContract.CommitBoundary.NO_MUTATION),
            Set.of(), Set.of());
        RuntimeOperationDescriptor operation = new RuntimeOperationDescriptor(capability, operationId,
            pins.stream().map(pin -> new RuntimeOperationDescriptor.Pin(pin.id(), pin.direction() == CatalogNodeDescriptor.Direction.INPUT
                ? RuntimeOperationDescriptor.Direction.INPUT : RuntimeOperationDescriptor.Direction.OUTPUT, pin.type())).toList(), semantics);
        RuntimeBindingRegistry runtime = new RuntimeBindingRegistry();
        runtime.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN),
            List.of(RuntimeBinding.available(operation, provider, "1.0.0", invocation -> {
                throw new AssertionError("Catalog inspection must not execute a function");
            })));
        List<CatalogNodeDescriptor> definitions = new ArrayList<>();
        for (String name : List.of("child", "parent")) {
            definitions.add(CatalogNodeDescriptor.builder(NodeId.of("private-function-" + name)).domain("flow").family("function")
                .displayName("QA " + name).description("Calls the admitted QA " + name + " function.")
                .pins(pins).category(ContractRef.of(OWNER, CapabilityId.of("flow")))
                .branches(List.of(new CatalogNodeDescriptor.Branch("failure", "Failure", "The function reports a failure.",
                    List.of(new CatalogNodeDescriptor.Case("failure", "Failure", "The function enters the failure branch.")))))
                .handler(new CatalogNodeDescriptor.Handler(capability, operationId)).semantics(semantics).requiredCapabilities(Set.of(capability))
                .metadata(Map.of("customFunctionIdentity", Map.of("id", "qa_async_" + name, "owner", OWNER.value(), "namespace", "local"),
                    "sourceExtras", Map.of("presentation", List.of("Preserve", "Exactly")),
                    "authoredSource", Map.of("id", "private-function-" + name, "handlerCapability", capability.id().value(),
                        "sourceProvenance", Map.of("sourceUri", "qa-inspection-" + name, "rowIndex", 0,
                            "owner", OWNER.value(), "sourceHash", "a".repeat(64)))))
                .build());
        }
        CatalogVersion version = ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION;
        CatalogContribution contribution = CatalogContribution.builder(OWNER, "1.0.0", new CatalogContractRange(version, version),
                CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED, "qa-inspection", "1.0.0", "test", "test"))
            .categories(List.of(new CatalogCategoryDescriptor(CapabilityId.of("flow"), "Flow", "Flow operations.", 1)))
            .capabilities(List.of(new CatalogCapabilityDescriptor(CapabilityId.of("flow"), 1, false, InspectorFallback.GENERIC),
                new CatalogCapabilityDescriptor(capability.id(), 1, false, InspectorFallback.GENERIC),
                new CatalogCapabilityDescriptor(editor.id(), 1, false, InspectorFallback.GENERIC)))
            .definitions(definitions).runtimeRequirements(List.of(operation)).build();
        var compiled = new CatalogCompiler(version, CatalogBindingProof.live(runtime)).compile(List.of(contribution), 11L);
        assertTrue(compiled.accepted(), compiled.diagnostics().toString());
        return compiled.snapshot().orElseThrow();
    }

    private static Map<String, Object> invoke(QaResourceAdapter qa, CommandSender actor, String operation, Map<String, Object> input) {
        return qa.invoke(actor, operation, input).toCompletableFuture().join();
    }

    private static ProtocolEnvelope<Map<String, Object>> response(Map<String, Object> result) {
        assertEquals("handled", result.get("status"), result.toString());
        assertNotNull(result.get("response"));
        return ENVELOPES.decode(JsonValue.fromJava(result.get("response")));
    }

    private static ResourceDocument<?> document(Map<String, Object> result) {
        ProtocolEnvelope<Map<String, Object>> response = response(result);
        assertEquals(ProtocolEnvelope.Status.OK, response.status(), result.toString());
        return assertInstanceOf(ProtocolBody.ResourceDocumentResponse.class, response.body()).document();
    }

    private static Map<String, Object> payloadInput(String id, String name) {
        Map<String, Object> payload = Map.of("id", id, "name", name);
        return Map.of("type", ReSyncResourceCatalog.GUI, "id", id, "payload", payload,
            "payloadChecksum", ResourcePayloadCodecs.json().hashPayload(payload).canonicalText(), "mutationId", UUID.randomUUID().toString());
    }

    private static CommandSender sender(String name, boolean permission, boolean console) {
        return (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
            new Class<?>[] {console ? ConsoleCommandSender.class : CommandSender.class}, (proxy, method, arguments) -> switch (method.getName()) {
                case "hasPermission" -> permission;
                case "getName", "toString" -> name;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static ProtocolEnvelope<Map<String, Object>> createRequest(String id, UUID mutationId, long epoch) {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER, TYPE, id);
        CanonicalPayload<Map<String, Object>> payload = ResourcePayloadCodecs.json().canonicalize(Map.of("id", id, "name", "Draft"));
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.REQUEST, ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), SERVER, resource, 0L, epoch, mutationId,
            ContractRef.of(OWNER, OperationId.of("resource.create")), Set.of(ContractRef.of(OWNER, CapabilityId.of("resources"))),
            ContractRef.of(OWNER, ResourceTypeId.of("resource.document")), null, payload.checksum(), false, null,
            CATALOG.contentChecksum(), CATALOG.bindingManifestHash(), null, null, 0L, ProtocolEnvelope.Status.ACCEPTED,
            List.of(), Map.of(), new ProtocolBody.ResourceRequest(new ResourceCreateRequest<>(resource, payload, mutationId)));
    }

    private static final class ControlledWorker implements BiFunction<Integer, Runnable, CompletionStage<Void>> {
        private final Queue<Runnable> actions = new ArrayDeque<>();
        private final CompletableFuture<Void> exit = new CompletableFuture<>();

        @Override
        public CompletionStage<Void> apply(Integer requestBytes, Runnable action) {
            assertTrue(requestBytes > 0);
            actions.add(action);
            return exit;
        }

        private void run() {
            actions.remove().run();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Map<String, JsonObject> values;
        private final FixtureAdapter storage;
        private final FlowResourceRegistry registry = new FlowResourceRegistry();
        private final AtomicReference<CatalogSnapshot> catalog = new AtomicReference<>(CATALOG);
        private final AtomicReference<FlowResourceProtocolEnvelopeHandler> handler = new AtomicReference<>();
        private final AuthorityEpoch epoch;
        private final SqliteProtocolResourceMutationAuthority authority;

        private Fixture(Path directory, AuthorityEpoch epoch) {
            this(directory, epoch, new FixtureAdapter(new ConcurrentHashMap<>()));
        }

        private Fixture(Path directory, AuthorityEpoch epoch, FixtureAdapter storage) {
            this.epoch = epoch;
            this.storage = storage;
            this.values = storage.values;
            registry.register(storage);
            authority = new SqliteProtocolResourceMutationAuthority(registry, SERVER, directory.resolve("resource.db"),
                CoreGraphResourceAuthority.unavailable(), epoch);
            handler.set(new FlowResourceProtocolEnvelopeHandler(registry, SERVER, authority, ProtocolResourceAuthorizer.serverGranted(), epoch));
        }

        private QaResourceAdapter adapter(BiFunction<Integer, Runnable, CompletionStage<Void>> worker) {
            return new QaResourceAdapter(() -> registry, handler::get, () -> SERVER, catalog::get, epoch, Runnable::run, worker);
        }

        @Override
        public void close() {
            authority.close();
        }
    }

    private static final class FixtureAdapter implements FlowResourceAdapter<JsonObject> {
        private final Map<String, JsonObject> values;
        private final Map<String, FlowResourceMutationStamp> stamps = new ConcurrentHashMap<>();

        private FixtureAdapter(Map<String, JsonObject> values) {
            this.values = values;
        }

        @Override
        public ReSyncManagedResource descriptor() {
            return ReSyncResourceCatalog.byType(ReSyncResourceCatalog.GUI);
        }

        @Override
        public JsonObject get(String id) {
            JsonObject value = values.get(id);
            return value == null ? null : value.deepCopy();
        }

        @Override
        public List<String> listIds() {
            return new ArrayList<>(values.keySet());
        }

        @Override
        public JsonObject deserialize(String json) {
            return new Gson().fromJson(json, JsonObject.class);
        }

        @Override
        public String serialize(JsonObject value) {
            return value.toString();
        }

        @Override
        public String id(JsonObject value) {
            return value.get("id").getAsString();
        }

        @Override
        public boolean supportsAuthoritativeMutationIdentity() {
            return true;
        }

        @Override
        public void save(JsonObject value) {
            save(value, UUID.randomUUID(), revision(id(value)));
        }

        @Override
        public void save(JsonObject value, UUID mutationId, long expectedRevision) {
            String id = id(value);
            if (revision(id) != expectedRevision) {
                throw new IllegalStateException("Resource revision changed");
            }
            JsonObject copy = value.deepCopy();
            values.put(id, copy);
            Map<String, Object> payload = new Gson().fromJson(copy, Map.class);
            stamps.put(id, new FlowResourceMutationStamp(ReSyncResourceCatalog.GUI, id, expectedRevision + 1L, mutationId,
                ResourcePayloadCodecs.json().hashPayload(payload).canonicalText(), false));
        }

        @Override
        public void delete(String id) {
            delete(id, UUID.randomUUID(), revision(id));
        }

        @Override
        public void delete(String id, UUID mutationId, long expectedRevision) {
            FlowResourceMutationStamp previous = stamps.get(id);
            if (revision(id) != expectedRevision || previous == null || !values.containsKey(id)) {
                throw new IllegalStateException("Resource revision changed");
            }
            values.remove(id);
            stamps.put(id, new FlowResourceMutationStamp(ReSyncResourceCatalog.GUI, id, expectedRevision + 1L, mutationId, previous.payloadHash(), true));
        }

        @Override
        public FlowResourceMutationStamp readMutationStamp(String id) {
            return stamps.get(id);
        }

        @Override
        public JsonObject duplicate(JsonObject value, String targetId) {
            JsonObject copy = value.deepCopy();
            copy.addProperty("id", targetId);
            return copy;
        }

        @Override
        public Set<String> supportedOperations() {
            return Set.of("discover", "query", "get", "create", "validate", "save", "update", "delete", "duplicate", "activate");
        }

        private long revision(String id) {
            FlowResourceMutationStamp previous = stamps.get(id);
            return previous == null ? 0L : previous.revision();
        }
    }
}
