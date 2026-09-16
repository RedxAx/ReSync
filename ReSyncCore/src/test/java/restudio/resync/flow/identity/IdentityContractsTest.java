package restudio.resync.flow.identity;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.contract.identity.Revision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityContractsTest {
    private static final OwnerId OWNER = new OwnerId("restudio.resync");
    private static final ContractRef<ResourceTypeId> TASK_TYPE = new ContractRef<>(new OwnerId("example.reference"), new ResourceTypeId("task"));
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));

    @Test
    void identifiersRejectNonCanonicalValues() {
        assertThrows(IllegalArgumentException.class, () -> new OwnerId("Restudio"));
        assertThrows(IllegalArgumentException.class, () -> new NodeId("node id"));
        assertThrows(IllegalArgumentException.class, () -> new PinId("e\u0301"));
        assertThrows(IllegalArgumentException.class, () -> new OperationId("Run Operation"));
        assertThrows(IllegalArgumentException.class, () -> new ProviderId("Runtime Provider"));
        assertThrows(IllegalArgumentException.class, () -> new ResourceTypeId("restudio.resync/task"));
        assertThrows(IllegalArgumentException.class, () -> ServerId.parseCanonicalText("11111111-1111-4111-8111-11111111111A"));
    }

    @Test
    void handlerOperationsAndProvidersUseLocalCanonicalIdentity() {
        OperationId operation = OperationId.of("execute");
        ProviderId provider = ProviderId.of("runtime");

        assertEquals("execute", operation.canonicalText());
        assertEquals("runtime", provider.canonicalText());
        assertEquals("execute", operation.toString());
        assertEquals("runtime", provider.toString());
        assertEquals(-1, OperationId.of("a").compareTo(operation));
        assertEquals(-1, ProviderId.of("a").compareTo(provider));
        assertEquals("example.reference/execute", new ContractRef<>(new OwnerId("example.reference"), operation).canonicalText());
        assertEquals("example.reference/runtime", new ContractRef<>(new OwnerId("example.reference"), provider).canonicalText());
    }

    @Test
    void typedReferencesKeepNamespaceOutOfLocalIdentity() {
        ContractRef<NodeId> first = new ContractRef<>(OWNER, new NodeId("task"));
        ContractRef<NodeId> second = new ContractRef<>(new OwnerId("example.reference"), new NodeId("task"));

        assertEquals("restudio.resync/task", first.canonicalText());
        assertEquals(first, ContractRef.parseCanonicalText(first.canonicalText(), NodeId::new));
        assertNotEquals(first, second);
        assertEquals("task", first.id().value());
    }

    @Test
    void canonicalOrderingUsesNamespaceThenLocalText() {
        List<ContractRef<NodeId>> ordered = CanonicalText.sorted(List.of(
                new ContractRef<>(new OwnerId("zeta"), new NodeId("a")),
                new ContractRef<>(new OwnerId("alpha"), new NodeId("z")),
                new ContractRef<>(new OwnerId("alpha"), new NodeId("a"))),
                reference -> reference.canonicalText());

        assertEquals(List.of("alpha/a", "alpha/z", "zeta/a"), ordered.stream().map(ContractRef::canonicalText).toList());
    }

    @Test
    void serverResourceLocatorRoundTripsCompleteTypedIdentity() {
        ServerResourceLocator locator = new ServerResourceLocator(SERVER, TASK_TYPE, "task-17");
        ServerResourceLocator decoded = ServerResourceLocator.parseCanonicalText(locator.canonicalText());

        assertEquals(locator, decoded);
        assertEquals(SERVER, locator.serverId());
        assertEquals(TASK_TYPE, locator.type());
        assertEquals("task-17", locator.id());
        assertEquals("11111111-1111-4111-8111-111111111111/example.reference/task/task-17", locator.canonicalText());
        assertNotEquals(locator, new ServerResourceLocator(new ServerId(UUID.fromString("22222222-2222-4222-8222-222222222222")), TASK_TYPE, "task-17"));
        assertNotEquals(locator, new ServerResourceLocator(
                SERVER,
                new ContractRef<>(new OwnerId("example.reference"), new ResourceTypeId("other")),
                "task-17"));
    }

    @Test
    void unknownIdentityFieldsRemainRecursiveWithoutChangingIdentity() {
        ServerResourceLocator nested = new ServerResourceLocator(SERVER, TASK_TYPE, "nested");
        ContractRef<ResourceTypeId> known = new ContractRef<>(TASK_TYPE.owner(), TASK_TYPE.id());
        ContractRef<ResourceTypeId> extended = new ContractRef<>(TASK_TYPE.owner(), TASK_TYPE.id(), Map.of("future", Map.of("locator", nested)));

        assertEquals(known, extended);
        assertEquals(nested.canonicalValue(), ((Map<?, ?>) extended.canonicalValue().get("future")).get("locator"));

        ResourceKey key = new ResourceKey(TASK_TYPE, "task-17", Map.of("futureKey", true));
        ServerResourceLocator locator = new ServerResourceLocator(SERVER, key, Map.of("futureLocator", List.of(key)));
        assertEquals("task-17", locator.id());
        assertTrue(locator.canonicalValue().containsKey("key"));
        assertTrue(locator.canonicalValue().containsKey("futureLocator"));
    }

    @Test
    void functionParameterIdentityRoundTripsAsCanonicalUuid() {
        FunctionParameterId parameter = new FunctionParameterId(UUID.fromString("33333333-3333-4333-8333-333333333333"));

        assertEquals(parameter, FunctionParameterId.parseCanonicalText(parameter.canonicalText()));
        assertThrows(IllegalArgumentException.class, () -> FunctionParameterId.parseCanonicalText("33333333-3333-4333-8333-33333333333A"));
    }

    @Test
    void replacementUuidIdentitiesRoundTripAndOrderCanonically() {
        UUID uuid = UUID.fromString("44444444-4444-4444-8444-444444444444");
        UUID later = UUID.fromString("55555555-5555-4555-8555-555555555555");
        Map<String, UuidIdentity> identities = Map.of(
                "correlation", CorrelationId.of(uuid),
                "trace", TraceId.of(uuid),
                "lease", LeaseId.of(uuid),
                "snapshot", SnapshotId.of(uuid));

        assertEquals(CorrelationId.of(uuid), CorrelationId.parseCanonicalText(uuid.toString()));
        assertEquals(TraceId.of(uuid), TraceId.parseCanonicalText(uuid.toString()));
        assertEquals(LeaseId.of(uuid), LeaseId.parseCanonicalText(uuid.toString()));
        assertEquals(SnapshotId.of(uuid), SnapshotId.parseCanonicalText(uuid.toString()));
        assertTrue(CorrelationId.of(uuid).compareTo(CorrelationId.of(later)) < 0);
        assertTrue(TraceId.of(uuid).compareTo(TraceId.of(later)) < 0);
        assertTrue(LeaseId.of(uuid).compareTo(LeaseId.of(later)) < 0);
        assertTrue(SnapshotId.of(uuid).compareTo(SnapshotId.of(later)) < 0);
        assertEquals(4, identities.size());
        assertThrows(IllegalArgumentException.class, () -> SnapshotId.parseCanonicalText("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"));
    }

    @Test
    void catalogBindingRoundTripsGenerationAndHashes() {
        CatalogBinding binding = new CatalogBinding(
                3L,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

        assertEquals(binding, CatalogBinding.parseCanonicalText(binding.canonicalText()));
        assertThrows(IllegalArgumentException.class, () -> new CatalogBinding(0L, binding.catalogChecksum(), binding.bindingManifestHash()));
        assertThrows(IllegalArgumentException.class, () -> new ContentHash("Aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
    }

    @Test
    void identityCodecRoundTripsUnknownReferenceAndLocatorFields() {
        ContractRef<ResourceTypeId> reference = new ContractRef<>(
                TASK_TYPE.owner(),
                TASK_TYPE.id(),
                Map.of("future", Map.of("enabled", true, "values", List.of("a", 2))));
        JsonValue.JsonObject referenceJson = IdentityCodec.encode(reference);
        ContractRef<ResourceTypeId> decodedReference = IdentityCodec.decodeReference(referenceJson, ResourceTypeId::new);

        assertEquals(reference, decodedReference);
        assertEquals(referenceJson.canonicalText(), IdentityCodec.encode(decodedReference).canonicalText());

        ServerResourceLocator locator = new ServerResourceLocator(
                SERVER,
                new ResourceKey(reference, "task-17", Map.of("keyFuture", "preserved")),
                Map.of("locatorFuture", List.of(false, Map.of("nested", "value"))));
        JsonValue.JsonObject locatorJson = IdentityCodec.encode(locator);
        ServerResourceLocator decodedLocator = IdentityCodec.decodeLocator(locatorJson);

        assertEquals(locator, decodedLocator);
        assertEquals(locatorJson.canonicalText(), IdentityCodec.encode(decodedLocator).canonicalText());
    }

    @Test
    void revisionCodecPreservesUnknownFieldsAndRejectsInvalidValues() {
        Revision revision = new Revision(7L, Map.of("future", Map.of("reason", "migration")));
        Revision decoded = IdentityCodec.decodeRevision(IdentityCodec.encode(revision));

        assertEquals(revision, decoded);
        assertEquals(8L, revision.next().value());
        assertEquals(revision.canonicalValue(), decoded.canonicalValue());
        assertThrows(IllegalArgumentException.class, () -> new Revision(-1L));
        assertThrows(IllegalArgumentException.class, () -> IdentityCodec.decodeRevision(JsonValue.parse("{\"revision\":1.5}")));
        assertThrows(IllegalArgumentException.class, () -> IdentityCodec.decodeRevision(JsonValue.parse("{\"revision\":9223372036854775808}")));
    }

    @Test
    void revisionCodecAcceptsCanonicalIntegralValuesAfterNumericNormalization() {
        for (long expected : List.of(10L, 100L, 1000L)) {
            assertEquals(expected, IdentityCodec.decodeRevision("{\"revision\":" + expected + "}").value());
        }

        assertThrows(IllegalArgumentException.class, () -> IdentityCodec.decodeRevision("{\"revision\":1.5}"));
        assertThrows(IllegalArgumentException.class, () -> IdentityCodec.decodeRevision("{\"revision\":9223372036854775808}"));
        assertThrows(IllegalArgumentException.class, () -> IdentityCodec.decodeRevision("{\"revision\":1e2}"));
        assertThrows(IllegalArgumentException.class, () -> IdentityCodec.decodeRevision("{\"revision\":1.0}"));
    }

    @Test
    void uuidFactoriesSeparateInteractiveAndDeterministicIdentities() {
        ConnectionId interactive = ConnectionId.interactive();
        ConnectionId first = ConnectionId.deterministic("source|connection");
        ConnectionId second = ConnectionId.deterministic("source|connection");
        ConnectionId differentDomain = ConnectionId.deterministic(UuidIdentity.MIGRATION_NAMESPACE, "other-domain", "source|connection");

        assertEquals(4, interactive.value().version());
        assertEquals(5, first.value().version());
        assertEquals(first, second);
        assertNotEquals(first, differentDomain);
        assertEquals(first, ConnectionId.parseCanonicalText(first.canonicalText()));
        assertThrows(IllegalArgumentException.class, () -> ConnectionId.of(UUID.fromString("11111111-1111-3111-8111-111111111111")));
        assertThrows(IllegalArgumentException.class, () -> IdentityCodec.deterministicUuid(UuidIdentity.MIGRATION_NAMESPACE, "Bad Domain", "name"));
    }

    @Test
    void codecsRequireCanonicalInputBytes() {
        ContractRef<ResourceTypeId> reference = ContractRef.of(new OwnerId("example.reference"), ResourceTypeId.of("task"));
        byte[] bytes = IdentityCodec.encodeBytes(reference);
        Map<String, Object> cycle = new java.util.HashMap<>();
        cycle.put("self", cycle);

        assertEquals(reference, IdentityCodec.decodeReference(IdentityCodec.decode(bytes), ResourceTypeId::new));
        assertThrows(IllegalArgumentException.class, () -> CanonicalCodec.decode(("{ \"localId\":\"task\",\"ownerId\":\"example.reference\" }").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> new ContractRef<>(new OwnerId("example.reference"), ResourceTypeId.of("task"), cycle));
    }
}
