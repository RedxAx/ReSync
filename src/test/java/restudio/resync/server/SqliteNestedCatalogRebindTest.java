package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.ConnectionState;
import restudio.resync.core.Session;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.FunctionBinding;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.CanonicalPayload;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourceSaveRequest;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.protocol.FrameSender;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.security.ClientIdentity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteNestedCatalogRebindTest {
    private static final ServerId SERVER = ServerId.deterministic("sqlite-nested-catalog-rebind-test");
    private static final CatalogBinding SOURCE = binding(14L, "a");
    private static final CatalogBinding TARGET = binding(15L, "b");
    private static final ServerResourceLocator PARENT = resource("a-parent");
    private static final ServerResourceLocator CHILD = resource("z-child");
    private static final CoreGraphStorageBoundary BOUNDARY = new CoreGraphStorageBoundary();

    @Test
    void advancesProvenChildPinsBeforeTheParentAndReplaysWithoutNewMutations(@TempDir Path root) throws Exception {
        Core core = new Core();
        Path database = seed(root, core);
        try (var authority = open(database, core)) {
            assertEquals(6L, authority.load(CHILD).revision());
            assertEquals(2L, authority.load(PARENT).revision());
            FunctionBinding pin = core.current.get(PARENT).functionSourceDocument().graph().functions().getFirst();
            assertEquals(6L, pin.revision());
            assertEquals("6", pin.unknown().get("catalogRevision").toString());
            assertEquals("parent", core.current.get(PARENT).functionSourceDocument().graph().unknown().get("body"));
            assertEquals(List.of(CHILD, PARENT), core.writes);
        }
        UUID parentMutation = UUID.fromString(core.current.get(PARENT).envelope().assetMutationId());
        try (var authority = open(database, core)) {
            assertEquals(parentMutation, authority.load(PARENT).mutationId());
            assertEquals(2, core.writes.size());
        }
        assertEquals(2L, count(database, "SELECT COUNT(*) FROM core_catalog_evolution_receipt"));
        assertEquals(2L, count(database, "SELECT COUNT(*) FROM resource_mutation_receipt WHERE status = 'APPLIED'"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement("SELECT response_resource FROM resource_mutation_receipt ORDER BY sequence")) {
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(CHILD.canonicalText(), result.getString(1));
                assertTrue(result.next());
                assertEquals(PARENT.canonicalText(), result.getString(1));
            }
        }
    }

    @Test
    void recoversTheSameParentCandidateBeforeAndAfterAnInterruptedExternalWrite(@TempDir Path root) throws Exception {
        for (int stage : List.of(1, 2)) {
            Core core = new Core();
            Path database = seed(root.resolve("stage-" + stage), core);
            core.failure = stage;
            assertThrows(IllegalStateException.class, () -> open(database, core));
            assertEquals(1L, count(database, "SELECT COUNT(*) FROM resource_mutation_receipt WHERE status = 'PENDING'"));
            String mutation;
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.prepareStatement("SELECT mutation_id FROM resource_mutation_receipt WHERE status = 'PENDING'")) {
                try (var result = statement.executeQuery()) {
                    assertTrue(result.next());
                    mutation = result.getString(1);
                }
            }
            core.failure = 0;
            try (var authority = open(database, core)) {
                assertTrue(authority.durable());
                assertEquals(mutation, authority.load(PARENT).mutationId().toString());
                assertEquals(6L, core.current.get(PARENT).functionSourceDocument().graph().functions().getFirst().revision());
            }
            assertEquals(2L, count(database, "SELECT COUNT(*) FROM resource_mutation_receipt WHERE status = 'APPLIED'"));
            assertEquals(0L, count(database, "SELECT COUNT(*) FROM resource_mutation_receipt WHERE status = 'PENDING'"));
            assertEquals(List.of(CHILD, PARENT), core.writes);
        }
    }

    @Test
    void rejectsDeletedTamperedAndDowngradedProofsDuringRestart(@TempDir Path root) throws Exception {
        List<String> edits = List.of(
            "DELETE FROM core_catalog_evolution_receipt WHERE mutation_id = (SELECT mutation_id FROM resource_mutation_receipt WHERE response_resource = '" + CHILD.canonicalText() + "')",
            "UPDATE core_catalog_evolution_receipt SET source_envelope = '{}'",
            "UPDATE core_catalog_evolution_receipt SET proof_hash = '" + "0".repeat(64) + "'",
            "UPDATE resource_mutation_receipt SET actor_id = '" + CoreCatalogCompatibilityRebind.ACTOR + "'");
        for (int index = 0; index < edits.size(); index++) {
            Core core = new Core();
            Path database = seed(root.resolve("tamper-" + index), core);
            try (var authority = open(database, core)) {
                assertEquals(2L, authority.load(PARENT).revision());
            }
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.createStatement()) {
                statement.executeUpdate(edits.get(index));
            }
            assertThrows(IllegalStateException.class, () -> open(database, core));
            assertEquals(2L, core.current.get(PARENT).envelope().assetRevision());
            assertEquals(2, core.writes.size());
        }
    }

    @Test
    void realChildEditsCannotAdvanceAnOlderParentPin(@TempDir Path root) throws Exception {
        Core core = new Core();
        Path database = root.resolve("runtime/resource-mutations.db");
        try (var authority = open(database, core)) {
            authority.load(PARENT);
            authority.load(CHILD);
            save(authority, core, CHILD, "edited");
        }
        core.active = TARGET;
        try (var authority = open(database, core)) {
            assertEquals(7L, authority.load(CHILD).revision());
            assertEquals(1L, authority.load(PARENT).revision());
            assertEquals(5L, core.current.get(PARENT).functionSourceDocument().graph().functions().getFirst().revision());
            assertEquals(SOURCE, core.current.get(PARENT).functionSourceDocument().graph().catalogBinding());
        }
    }

    @Test
    void laterRealChildEditsDoNotChangeAnAlreadyAppliedParentProof(@TempDir Path root) throws Exception {
        Core core = new Core();
        Path database = seed(root, core);
        try (var authority = open(database, core)) {
            save(authority, core, CHILD, "later edit");
        }
        try (var authority = open(database, core)) {
            assertTrue(authority.durable());
            assertEquals(7L, authority.load(CHILD).revision());
            assertEquals(2L, authority.load(PARENT).revision());
            assertEquals(6L, core.current.get(PARENT).functionSourceDocument().graph().functions().getFirst().revision());
        }
    }

    @Test
    void anActiveTargetChangeCannotCommitAParentAgainstTheCapturedCatalog(@TempDir Path root) throws Exception {
        Core core = new Core();
        Path database = seed(root, core);
        core.changeTarget = true;
        assertThrows(IllegalStateException.class, () -> open(database, core));
        assertEquals(1L, core.current.get(PARENT).envelope().assetRevision());
        assertEquals(5L, core.current.get(PARENT).functionSourceDocument().graph().functions().getFirst().revision());
        assertEquals(List.of(CHILD), core.writes);
        assertEquals(1L, count(database, "SELECT COUNT(*) FROM resource_mutation_receipt WHERE status = 'APPLIED'"));
    }

    @Test
    void missingAndCyclicDependenciesRemainUnchanged(@TempDir Path root) {
        for (boolean cycle : List.of(false, true)) {
            Core core = new Core();
            if (cycle) {
                core.current.put(CHILD, source(CHILD, 5L, SOURCE, "child", List.of(new FunctionBinding(PARENT, 1L, List.of(), List.of())), UUID.randomUUID()));
            } else {
                core.current.remove(CHILD);
            }
            core.active = TARGET;
            try (var authority = open(root.resolve("case-" + cycle + "/runtime/resource-mutations.db"), core)) {
                assertEquals(1L, authority.load(PARENT).revision());
                assertTrue(core.writes.isEmpty());
            }
        }
    }

    private static Path seed(Path root, Core core) {
        Path database = root.resolve("runtime/resource-mutations.db");
        try (var authority = open(database, core)) {
            authority.load(CHILD);
            authority.load(PARENT);
        }
        core.active = TARGET;
        return database;
    }

    private static SqliteProtocolResourceMutationAuthority open(Path database, Core core) {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.addCoreMutationListener(transition -> {
            if (core.changeTarget && transition.locator().equals(CHILD)) {
                core.active = binding(16L, "c");
            }
        });
        return new SqliteProtocolResourceMutationAuthority(registry, SERVER, database, core,
            ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L), registry, null);
    }

    private static long count(Path database, String query) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement(); var result = statement.executeQuery(query)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    @SuppressWarnings("unchecked")
    private static void save(SqliteProtocolResourceMutationAuthority authority, Core core, ServerResourceLocator resource, String body) {
        CoreGraphStorageBoundary.Decoded previous = core.current.get(resource);
        UUID mutation = UUID.randomUUID();
        CoreGraphStorageBoundary.Decoded candidate = source(resource, previous.envelope().assetRevision() + 1L,
            core.active, body, previous.functionSourceDocument().graph().functions(), mutation);
        Map<String, Object> value = (Map<String, Object>) CanonicalJson.parse(new String(BOUNDARY.encode(candidate), StandardCharsets.UTF_8));
        CanonicalPayload<Map<String, Object>> payload = ResourcePayloadCodecs.json().canonicalize(value);
        ResourceSaveRequest<Map<String, Object>> operation = new ResourceSaveRequest<>(resource, previous.envelope().assetRevision(), payload, mutation);
        ProtocolEnvelope<Map<String, Object>> request = new ProtocolEnvelope<>(ProtocolEnvelope.Kind.REQUEST,
            ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_MINIMUM_VERSION, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            SERVER, resource, operation.expectedRevision(), 1L, mutation, ContractRef.of(OwnerId.of("restudio.resync"), new OperationId("resource.save")),
            Set.of(ContractRef.of(OwnerId.of("restudio.resync"), new CapabilityId("resources"))),
            ContractRef.of(OwnerId.of("restudio.resync"), new ResourceTypeId("resource.document")), null, payload.checksum(),
            false, null, null, null, null, null, 0, ProtocolEnvelope.Status.ACCEPTED, List.of(), Map.of(), new ProtocolBody.ResourceRequest(operation));
        ConnectionInfo connection = new ConnectionInfo(null, new FrameSender() {
            @Override
            public void send(byte[] frame) {
            }

            @Override
            public void close(int code, String reason) {
            }
        }, 1);
        connection.setClientId("client");
        connection.setClientVersion("2.1.0");
        connection.setState(ConnectionState.AUTHENTICATED);
        connection.setProtocolResourceAccess(true);
        Session session = new Session("session", "client", connection, new ClientIdentity("client", "2.1.0"));
        ProtocolEnvelopeDispatchResult result = authority.mutate(connection, session, request, operation);
        assertTrue(result.handled(), result.message());
        assertFalse(result.response().status() == ProtocolEnvelope.Status.REJECTED);
        assertEquals(body, core.current.get(resource).functionSourceDocument().graph().unknown().get("body"));
    }

    private static CoreGraphStorageBoundary.Decoded source(ServerResourceLocator resource, long revision, CatalogBinding catalog,
            String body, List<FunctionBinding> functions, UUID mutation) {
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, revision, catalog, Set.of(), List.of(),
            List.of(), List.of(), List.of(), functions, OpaqueData.of(Map.of("body", body)));
        FunctionSourceDocument source = new FunctionSourceDocument(new FunctionSignature(new FunctionLocator(resource),
            new FunctionRevision(revision), List.of(), List.of(), Map.of()), graph, OpaqueData.empty());
        return BOUNDARY.decode(BOUNDARY.encode(source, new CoreGraphStorageBoundary.AssetMetadata("function", revision,
            mutation, ResourceActivationState.ACTIVE), resource), resource);
    }

    private static ServerResourceLocator resource(String id) {
        return new ServerResourceLocator(SERVER, ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("function")), id);
    }

    private static CatalogBinding binding(long generation, String character) {
        return new CatalogBinding(generation, new ContentHash(character.repeat(64)), new ContentHash(character.repeat(64)));
    }

    private static final class Core implements CoreGraphResourceAuthority {
        private final Map<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> current = new LinkedHashMap<>();
        private final List<ServerResourceLocator> writes = new ArrayList<>();
        private CatalogBinding active = SOURCE;
        private int failure;
        private boolean changeTarget;

        private Core() {
            current.put(PARENT, source(PARENT, 1L, SOURCE, "parent", List.of(new FunctionBinding(CHILD, 5L,
                List.of(), List.of(), OpaqueData.of(Map.of("catalogRevision", 5L, "preserve", true)))), UUID.randomUUID()));
            current.put(CHILD, source(CHILD, 5L, SOURCE, "child", List.of(), UUID.randomUUID()));
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public Optional<CoreGraphStorageBoundary.Decoded> load(ServerResourceLocator resource) {
            return Optional.ofNullable(current.get(resource));
        }

        @Override
        public Optional<CatalogBinding> activeCatalogBinding() {
            return Optional.of(active);
        }

        @Override
        public List<CoreGraphResourceState> list(String type) {
            return current.values().stream().filter(value -> type.equals(value.envelope().resourceType())).map(CoreGraphResourceState::live).toList();
        }

        @Override
        public Optional<CoreGraphResourceState> state(ServerResourceLocator resource) {
            return load(resource).map(CoreGraphResourceState::live);
        }

        @Override
        public void validateSave(ServerResourceLocator resource, CoreGraphStorageBoundary.Decoded candidate) {
            if (!active.equals(candidate.functionSourceDocument().graph().catalogBinding())) {
                throw new IllegalArgumentException("Candidate Does Not Match The Current Catalog");
            }
            for (FunctionBinding dependency : candidate.functionSourceDocument().graph().functions()) {
                CoreGraphStorageBoundary.Decoded child = current.get(dependency.function());
                if (child == null || child.envelope().assetRevision() != dependency.revision()) {
                    throw new IllegalArgumentException("Function Dependency Does Not Match Its Exact Current Revision");
                }
            }
        }

        @Override
        public CoreGraphStorageBoundary.Decoded save(ServerResourceLocator resource, byte[] bytes, UUID mutation,
                long expectedRevision, ContentHash checksum) {
            CoreGraphStorageBoundary.Decoded candidate = BOUNDARY.decode(bytes, resource);
            validateSave(resource, candidate);
            CoreGraphStorageBoundary.Decoded previous = current.get(resource);
            if (previous.envelope().assetRevision() != expectedRevision || candidate.envelope().assetRevision() != expectedRevision + 1L
                || !mutation.toString().equals(candidate.envelope().assetMutationId()) || !checksum.equals(candidate.envelope().assetHash())) {
                throw new IllegalStateException("External Save Identity Does Not Match Its Exact Candidate");
            }
            if (resource.equals(PARENT) && failure == 1) {
                throw new IllegalStateException("Interrupted Before External Write");
            }
            current.put(resource, candidate);
            writes.add(resource);
            if (resource.equals(PARENT) && failure == 2) {
                throw new IllegalStateException("Interrupted After External Write");
            }
            return candidate;
        }

        @Override
        public CoreGraphStorageBoundary.CoreGraphTombstone delete(ServerResourceLocator resource, UUID mutation,
                long expectedRevision, ContentHash checksum) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CoreGraphStorageBoundary.Decoded activate(ServerResourceLocator resource, ResourceActivationState state,
                UUID mutation, long expectedRevision, ContentHash checksum) {
            throw new UnsupportedOperationException();
        }
    }
}
