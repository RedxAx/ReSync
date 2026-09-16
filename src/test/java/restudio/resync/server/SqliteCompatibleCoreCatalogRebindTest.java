package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.modules.flow.FlowResourceRegistry;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteCompatibleCoreCatalogRebindTest {
    private static final ServerId SERVER = ServerId.deterministic("sqlite-compatible-core-catalog-rebind-test");
    private static final CatalogBinding SOURCE = new CatalogBinding(54L, new ContentHash("a".repeat(64)),
        new ContentHash("b".repeat(64)));
    private static final CatalogBinding TARGET = new CatalogBinding(55L, new ContentHash("d".repeat(64)),
        new ContentHash("c".repeat(64)));
    private static final UUID SOURCE_MUTATION = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void advancesReceiptBoundHistoricalGraphOnceAndReplaysAsSteadyState(@TempDir Path root) throws Exception {
        for (String type : List.of("command", "flow", "function")) {
            assertForwardRebindAndReplay(root.resolve(type), type, true);
        }
    }

    @Test
    void advancesReceiptlessHistoricalGraphOnceAndReplaysAsSteadyState(@TempDir Path root) throws Exception {
        for (String type : List.of("command", "flow", "function")) {
            assertForwardRebindAndReplay(root.resolve("receiptless-" + type), type, false);
        }
    }

    @Test
    void settlesReceiptlessLiveCoreAlreadyOnTheFinalBinding(@TempDir Path root) throws Exception {
        for (String type : List.of("command", "flow", "function")) {
            Path database = root.resolve(type).resolve("runtime/resource-mutations.db");
            ServerResourceLocator resource = resource(type);
            TestCoreAuthority core = new TestCoreAuthority(source(type), SOURCE, true);
            try (SqliteProtocolResourceMutationAuthority authority = openDeferred(database, core)) {
                authority.settleCatalogBinding(SOURCE);
                assertTrue(authority.durable());
                assertEquals(6L, authority.load(resource).revision());
                assertEquals(SOURCE, CoreCatalogCompatibilityRebind.graph(core.current).catalogBinding());
            }
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var receipt = connection.prepareStatement("SELECT COUNT(*) FROM resource_mutation_receipt")) {
                try (var result = receipt.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(0, result.getInt(1));
                }
            }
        }
    }

    private static void assertForwardRebindAndReplay(Path root, String type, boolean seedReceipt) throws Exception {
        Path database = root.resolve("runtime/resource-mutations.db");
        ServerResourceLocator resource = resource(type);
        TestCoreAuthority core = new TestCoreAuthority(source(type), SOURCE, true);
        try (SqliteProtocolResourceMutationAuthority authority = open(database, core)) {
            assertEquals(6L, authority.load(resource).revision());
        }
        if (seedReceipt) {
            seedSourceReceipt(database, resource);
        }
        core.active = TARGET;

        UUID rebindMutation;
        try (SqliteProtocolResourceMutationAuthority authority = open(database, core)) {
            var loaded = authority.load(resource);
            assertEquals(7L, loaded.revision());
            assertEquals(7L, CoreCatalogCompatibilityRebind.graph(core.current).revision());
            assertEquals(TARGET, CoreCatalogCompatibilityRebind.graph(core.current).catalogBinding());
            if ("function".equals(type)) {
                assertEquals(7L, core.current.functionSourceDocument().signature().revision().value());
            }
            rebindMutation = loaded.mutationId();
        }

        try (SqliteProtocolResourceMutationAuthority authority = open(database, core)) {
            assertEquals(7L, authority.load(resource).revision());
            assertEquals(rebindMutation, authority.load(resource).mutationId());
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var receipt = connection.prepareStatement("SELECT COUNT(*), MIN(status), MAX(status) "
                 + "FROM resource_mutation_receipt WHERE actor_id = ?")) {
            receipt.setString(1, CoreCatalogCompatibilityRebind.ACTOR);
            try (var result = receipt.executeQuery()) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
                assertEquals("APPLIED", result.getString(2));
                assertEquals("APPLIED", result.getString(3));
            }
        }
        try (SqliteProtocolResourceMutationAuthority authority = openDeferred(database, core)) {
            authority.settleCatalogBinding(TARGET);
            assertTrue(authority.durable());
            assertEquals(7L, authority.load(resource).revision());
            assertEquals(rebindMutation, authority.load(resource).mutationId());
        }
    }

    @Test
    void leavesAValidatorRejectedHistoricalGraphUntouched(@TempDir Path root) throws Exception {
        Path database = root.resolve("runtime/resource-mutations.db");
        ServerResourceLocator resource = resource("command");
        TestCoreAuthority core = new TestCoreAuthority(source("command"), SOURCE, true);
        try (SqliteProtocolResourceMutationAuthority authority = open(database, core)) {
            authority.load(resource);
        }
        seedSourceReceipt(database, resource);
        core.active = TARGET;
        core.accept = false;

        try (SqliteProtocolResourceMutationAuthority authority = open(database, core)) {
            assertEquals(6L, authority.load(resource).revision());
            assertEquals(SOURCE, CoreCatalogCompatibilityRebind.graph(core.current).catalogBinding());
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var receipt = connection.prepareStatement(
                 "SELECT COUNT(*) FROM resource_mutation_receipt WHERE actor_id = ?")) {
            receipt.setString(1, CoreCatalogCompatibilityRebind.ACTOR);
            try (var result = receipt.executeQuery()) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
        }
    }

    private static SqliteProtocolResourceMutationAuthority open(Path database, TestCoreAuthority core) {
        return open(database, core, SqliteProtocolResourceMutationAuthority.CatalogStartup.IMMEDIATE);
    }

    private static SqliteProtocolResourceMutationAuthority openDeferred(Path database, TestCoreAuthority core) {
        return open(database, core, SqliteProtocolResourceMutationAuthority.CatalogStartup.DEFERRED);
    }

    private static SqliteProtocolResourceMutationAuthority open(Path database, TestCoreAuthority core,
                                                                SqliteProtocolResourceMutationAuthority.CatalogStartup startup) {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.addCoreMutationListener(ignored -> {
        });
        return new SqliteProtocolResourceMutationAuthority(registry, SERVER, database, core,
            ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L), registry, null, startup);
    }

    private static void seedSourceReceipt(Path database, ServerResourceLocator resource) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var state = connection.prepareStatement("SELECT * FROM resource_mutation_state WHERE resource = ?");
             var receipt = connection.prepareStatement("""
                 INSERT INTO resource_mutation_receipt(mutation_id, actor_id, fingerprint, operation,
                     requested_resource, response_resource, source_resource, target_resource, expected_revision,
                     precondition_hash, status, result_revision, result_mutation_id, result_hash, result_deleted,
                     result_payload, target_activation_state, result_activation_state, precondition_asset_hash,
                     precondition_core_payload_hash, precondition_core_payload_kind, result_asset_hash,
                     result_core_payload_hash, result_core_payload_kind, transition_envelope, transition_hash,
                     transition_published, sequence, error_code, error_message, created_at, updated_at)
                 VALUES(?, 'fixture', 'fixture', 'SAVE', ?, ?, NULL, ?, ?, '', 'APPLIED', ?, ?, ?, 0, NULL,
                     NULL, ?, NULL, NULL, NULL, ?, ?, ?, NULL, NULL, 1, 1, '', '', 1, 1)
                 """)) {
            state.setString(1, resource.canonicalText());
            try (var row = state.executeQuery()) {
                assertTrue(row.next());
                receipt.setString(1, SOURCE_MUTATION.toString());
                receipt.setString(2, resource.canonicalText());
                receipt.setString(3, resource.canonicalText());
                receipt.setString(4, resource.canonicalText());
                receipt.setLong(5, 5L);
                receipt.setLong(6, row.getLong("revision"));
                receipt.setString(7, SOURCE_MUTATION.toString());
                receipt.setString(8, row.getString("payload_hash"));
                receipt.setString(9, row.getString("activation_state"));
                receipt.setString(10, row.getString("asset_hash"));
                receipt.setString(11, row.getString("core_payload_hash"));
                receipt.setString(12, row.getString("core_payload_kind"));
                assertEquals(1, receipt.executeUpdate());
            }
        }
    }

    private static CoreGraphStorageBoundary.Decoded source(String type) {
        ServerResourceLocator resource = resource(type);
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, 6L, SOURCE, Set.of(), List.of(),
            List.of(), List.of(), List.of(), OpaqueData.of(Map.of("preserve", true)));
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        CoreGraphStorageBoundary.AssetMetadata metadata = new CoreGraphStorageBoundary.AssetMetadata(type, 6L,
            SOURCE_MUTATION, ResourceActivationState.ACTIVE);
        byte[] encoded = "function".equals(type)
            ? boundary.encode(new FunctionSourceDocument(new FunctionSignature(new FunctionLocator(resource),
                new FunctionRevision(6L), List.of(), List.of(), Map.of()), graph, OpaqueData.empty()), metadata, resource)
            : boundary.encode(graph, metadata, resource);
        return boundary.decode(encoded, resource);
    }

    private static ServerResourceLocator resource(String type) {
        return new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(type)), "historical");
    }

    private static final class TestCoreAuthority implements CoreGraphResourceAuthority {
        private final CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        private CoreGraphStorageBoundary.Decoded current;
        private CatalogBinding active;
        private boolean accept;

        private TestCoreAuthority(CoreGraphStorageBoundary.Decoded current, CatalogBinding active, boolean accept) {
            this.current = current;
            this.active = active;
            this.accept = accept;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public Optional<CoreGraphStorageBoundary.Decoded> load(ServerResourceLocator resource) {
            return Optional.ofNullable(current);
        }

        @Override
        public Optional<CatalogBinding> activeCatalogBinding() {
            return Optional.of(active);
        }

        @Override
        public List<CoreGraphResourceState> list(String type) {
            return current != null && type.equals(current.envelope().resourceType())
                ? List.of(CoreGraphResourceState.live(current)) : List.of();
        }

        @Override
        public Optional<CoreGraphResourceState> state(ServerResourceLocator resource) {
            return Optional.ofNullable(current).map(CoreGraphResourceState::live);
        }

        @Override
        public void validateSave(ServerResourceLocator resource, CoreGraphStorageBoundary.Decoded canonicalEnvelope) {
            if (!accept || !active.equals(CoreCatalogCompatibilityRebind.graph(canonicalEnvelope).catalogBinding())) {
                throw new IllegalArgumentException("The candidate is not resolvable under the active publication");
            }
        }

        @Override
        public CoreGraphStorageBoundary.Decoded save(ServerResourceLocator resource, byte[] canonicalEnvelope,
                                                      UUID mutationId, long expectedRevision,
                                                      ContentHash payloadChecksum) {
            CoreGraphStorageBoundary.Decoded candidate = boundary.decode(canonicalEnvelope, resource);
            validateSave(resource, candidate);
            if (current.envelope().assetRevision() != expectedRevision
                || candidate.envelope().assetRevision() != expectedRevision + 1L
                || !mutationId.toString().equals(candidate.envelope().assetMutationId())
                || !payloadChecksum.equals(candidate.envelope().assetHash())) {
                throw new IllegalStateException("The compatible Core rebind save identity is invalid");
            }
            current = candidate;
            return current;
        }

        @Override
        public CoreGraphStorageBoundary.CoreGraphTombstone delete(ServerResourceLocator resource, UUID mutationId,
                                                                   long expectedRevision,
                                                                   ContentHash payloadChecksum) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CoreGraphStorageBoundary.Decoded activate(ServerResourceLocator resource,
                                                          ResourceActivationState activationState, UUID mutationId,
                                                          long expectedRevision, ContentHash payloadChecksum) {
            throw new UnsupportedOperationException();
        }
    }
}
