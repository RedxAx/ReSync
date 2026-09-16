package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.canonical.CanonicalJson;
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
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.migration.MigrationReportsPersistenceParticipant;
import restudio.resync.modules.flow.CoreResourceMutationTransition;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.storage.StorageSafety;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteCompletedCoreCatalogRebindForwardHeadTest {
    private static final ServerId SERVER = ServerId.deterministic("completed-core-catalog-forward-head-test");
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final CoreGraphStorageBoundary BOUNDARY = new CoreGraphStorageBoundary();

    @Test
    void preservesCompletedIncidentAcrossCompatibilityEvolutionAndLaterSave(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, "evolved-descendant");
        try {
            try (SqliteProtocolResourceMutationAuthority ignored = fixture.open()) {
            }
            ServerResourceLocator resource = fixture.resources().getFirst();
            appendCompatible(fixture, resource, CoreCatalogEvolution.load().sources().stream().sorted().toList().getLast());
            fixture.core().evolution = CoreCatalogEvolutionTest.proof();
            try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
                assertEquals(7L, authority.load(resource).revision());
            }
            appendLive(fixture, resource, "SAVE", ResourceActivationState.ACTIVE);
            try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
                assertEquals(8L, authority.load(resource).revision());
                assertEquals(CoreCatalogEvolutionTest.TARGET, fixture.core().require(resource).graphDocument().catalogBinding());
            }
        } finally {
            fixture.close();
        }
    }

    @Test
    void acceptsPrunedAuthenticatedCompatibilityBeforeLaterOrdinaryReceipts(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, "compatible-descendant");
        try {
            try (SqliteProtocolResourceMutationAuthority ignored = fixture.open()) {
            }
            ServerResourceLocator resource = fixture.resources().getFirst();
            CatalogBinding target = CoreCatalogEvolution.load().sources().stream().sorted().toList().getLast();
            appendCompatible(fixture, resource, target);
            appendLive(fixture, resource, "SAVE", ResourceActivationState.ACTIVE);
            appendLive(fixture, resource, "ACTIVATE", ResourceActivationState.INACTIVE);
            try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
                assertEquals(8L, authority.load(resource).revision());
                assertEquals(target, fixture.core().require(resource).graphDocument().catalogBinding());
                assertEquals(ResourceActivationState.INACTIVE, authority.load(resource).activationState());
            }
        } finally {
            fixture.close();
        }
    }

    @Test
    void rejectsCompatibilityActorWithoutItsDeterministicWriterProof(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, "actor-only");
        try {
            try (SqliteProtocolResourceMutationAuthority ignored = fixture.open()) {
            }
            ServerResourceLocator resource = fixture.resources().getFirst();
            CatalogBinding target = CoreCatalogEvolution.load().sources().stream().sorted().toList().getLast();
            appendLive(fixture, resource, "SAVE", ResourceActivationState.ACTIVE, target);
            corruptReceipt(fixture, resource, "actor_id", CoreCatalogCompatibilityRebind.ACTOR);
            assertThrows(IllegalStateException.class, fixture::open);
        } finally {
            fixture.close();
        }
    }

    @Test
    void rejectsTamperedAuthenticatedCompatibilityFingerprint(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, "fingerprint");
        try {
            try (SqliteProtocolResourceMutationAuthority ignored = fixture.open()) {
            }
            ServerResourceLocator resource = fixture.resources().getFirst();
            appendCompatible(fixture, resource, CoreCatalogEvolution.load().sources().stream().sorted().toList().getLast());
            corruptReceipt(fixture, resource, "fingerprint", "0".repeat(64));
            assertThrows(IllegalStateException.class, fixture::open);
        } finally {
            fixture.close();
        }
    }

    @Test
    void acceptsMultipleReceiptLinkedDeletesAfterCompletedMigration(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, "first", "second");
        try {
            try (SqliteProtocolResourceMutationAuthority ignored = fixture.open()) {
            }
            for (ServerResourceLocator resource : fixture.resources()) {
                appendDelete(fixture, resource, true, false);
            }

            try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
                for (ServerResourceLocator resource : fixture.resources()) {
                    assertTrue(authority.load(resource).deleted());
                    assertEquals(6L, authority.load(resource).revision());
                }
            }
        } finally {
            fixture.close();
        }
    }

    @Test
    void acceptsReceiptAuthoritativePublishedSaveBeforeExactActivationHead(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, "edited");
        try {
            try (SqliteProtocolResourceMutationAuthority ignored = fixture.open()) {
            }
            ServerResourceLocator resource = fixture.resources().getFirst();
            appendLive(fixture, resource, "SAVE", ResourceActivationState.ACTIVE);
            appendLive(fixture, resource, "ACTIVATE", ResourceActivationState.INACTIVE);

            try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
                assertEquals(7L, authority.load(resource).revision());
                assertEquals(ResourceActivationState.INACTIVE, authority.load(resource).activationState());
            }
        } finally {
            fixture.close();
        }
    }

    @Test
    void rejectsUnreceiptedForwardHead(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, "unreceipted");
        try {
            try (SqliteProtocolResourceMutationAuthority ignored = fixture.open()) {
            }
            appendDelete(fixture, fixture.resources().getFirst(), false, false);

            assertThrows(IllegalStateException.class, fixture::open);
        } finally {
            fixture.close();
        }
    }

    @Test
    void rejectsBackwardHead(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, "backward");
        try {
            try (SqliteProtocolResourceMutationAuthority ignored = fixture.open()) {
            }
            rollbackToFrozenSource(fixture, fixture.resources().getFirst());

            assertThrows(IllegalStateException.class, fixture::open);
        } finally {
            fixture.close();
        }
    }

    @Test
    void rejectsForwardReceiptWithMismatchedPrecondition(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, "mismatch");
        try {
            try (SqliteProtocolResourceMutationAuthority ignored = fixture.open()) {
            }
            appendDelete(fixture, fixture.resources().getFirst(), true, true);

            assertThrows(IllegalStateException.class, fixture::open);
        } finally {
            fixture.close();
        }
    }

    @Test
    void rejectsForwardRevisionGap(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, "gap");
        try {
            try (SqliteProtocolResourceMutationAuthority ignored = fixture.open()) {
            }
            ServerResourceLocator resource = fixture.resources().getFirst();
            appendDelete(fixture, resource, true, false);
            corruptReceipt(fixture, resource, "result_revision", 7L);

            assertThrows(IllegalStateException.class, fixture::open);
        } finally {
            fixture.close();
        }
    }

    @Test
    void rejectsNonCanonicalSourceAndTargetLocators(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, "locator");
        try {
            try (SqliteProtocolResourceMutationAuthority ignored = fixture.open()) {
            }
            ServerResourceLocator resource = fixture.resources().getFirst();
            appendDelete(fixture, resource, true, false);
            corruptReceipt(fixture, resource, "source_resource", resource.canonicalText());
            corruptReceipt(fixture, resource, "target_resource", resource("other").canonicalText());

            assertThrows(IllegalStateException.class, fixture::open);
        } finally {
            fixture.close();
        }
    }

    @Test
    void rejectsProtocolAndCoreResultMismatch(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, "hash-mismatch");
        try {
            try (SqliteProtocolResourceMutationAuthority ignored = fixture.open()) {
            }
            ServerResourceLocator resource = fixture.resources().getFirst();
            appendLive(fixture, resource, "SAVE", ResourceActivationState.ACTIVE);
            corruptReceipt(fixture, resource, "result_hash", "e".repeat(64));
            corruptReceipt(fixture, resource, "result_core_payload_hash", "d".repeat(64));

            assertThrows(IllegalStateException.class, fixture::open);
        } finally {
            fixture.close();
        }
    }

    @Test
    void rejectsMissingTransitionAndNonStateReceiptShapes(@TempDir Path root) throws Exception {
        Fixture missing = fixture(root.resolve("missing"), "missing-transition");
        try {
            try (SqliteProtocolResourceMutationAuthority ignored = missing.open()) {
            }
            ServerResourceLocator resource = missing.resources().getFirst();
            appendDelete(missing, resource, true, false);
            corruptReceipt(missing, resource, "transition_hash", null);
            assertThrows(IllegalStateException.class, missing::open);
        } finally {
            missing.close();
        }

        Fixture replay = fixture(root.resolve("replay"), "non-state");
        try {
            try (SqliteProtocolResourceMutationAuthority ignored = replay.open()) {
            }
            ServerResourceLocator resource = replay.resources().getFirst();
            appendDelete(replay, resource, true, false);
            corruptReceipt(replay, resource, "result_mutation_id", UUID.randomUUID().toString());
            assertThrows(IllegalStateException.class, replay::open);
        } finally {
            replay.close();
        }
    }

    @Test
    void rejectsTombstoneThatDoesNotPreservePriorHashes(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, "tombstone");
        try {
            try (SqliteProtocolResourceMutationAuthority ignored = fixture.open()) {
            }
            ServerResourceLocator resource = fixture.resources().getFirst();
            appendDelete(fixture, resource, true, false);
            corruptReceipt(fixture, resource, "result_asset_hash", "c".repeat(64));

            assertThrows(IllegalStateException.class, fixture::open);
        } finally {
            fixture.close();
        }
    }

    @Test
    void rejectsForwardHeadWithUnexpectedCatalogBinding(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, "catalog-regression");
        try {
            try (SqliteProtocolResourceMutationAuthority ignored = fixture.open()) {
            }
            ServerResourceLocator resource = fixture.resources().getFirst();
            CatalogBinding unexpected = new CatalogBinding(55L, "a".repeat(64), "b".repeat(64));
            appendLive(fixture, resource, "SAVE", ResourceActivationState.ACTIVE, unexpected);

            assertThrows(IllegalStateException.class, fixture::open);
        } finally {
            fixture.close();
        }
    }

    private static Fixture fixture(Path root, String... ids) throws Exception {
        LinkedHashMap<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> sources = new LinkedHashMap<>();
        List<Map<String, Object>> incidents = new ArrayList<>();
        for (String id : ids) {
            ServerResourceLocator resource = resource(id);
            UUID mutationId = UUID.nameUUIDFromBytes(("source:" + id).getBytes(StandardCharsets.UTF_8));
            GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, 4L,
                CoreCatalogBindingMigration.SOURCE, Set.of(), List.of(), List.of(), List.of(), List.of(),
                OpaqueData.of(Map.of("fixture", id)));
            CoreGraphStorageBoundary.Decoded source = BOUNDARY.decode(BOUNDARY.encode(graph,
                new CoreGraphStorageBoundary.AssetMetadata("flow", 4L, mutationId, ResourceActivationState.ACTIVE),
                resource), resource);
            sources.put(resource, source);
            incidents.add(Map.of("type", "flow", "id", id, "revision", 4L,
                "mutationId", mutationId.toString(), "assetHash", source.envelope().assetHash().canonicalText()));
        }
        Map<String, Object> manifest = Map.of("format", CoreCatalogBindingMigration.ID,
            "source", binding(CoreCatalogBindingMigration.SOURCE), "target", binding(CoreCatalogBindingMigration.TARGET),
            "incidents", incidents);
        Files.createDirectories(root);
        MigrationReportsPersistenceParticipant reports = new MigrationReportsPersistenceParticipant(root);
        reports.admit();
        return new Fixture(root.resolve("runtime/resource-mutations.db"), reports,
            new MapCoreAuthority(sources), CoreCatalogBindingMigration.parse(CanonicalJson.canonicalize(manifest)),
            Map.copyOf(sources), List.copyOf(sources.keySet()));
    }

    private static Map<String, Object> binding(CatalogBinding binding) {
        return Map.of("generation", binding.generation(),
            "catalogChecksum", binding.catalogChecksum().canonicalText(),
            "bindingManifestHash", binding.bindingManifestHash().canonicalText());
    }

    private static ServerResourceLocator resource(String id) {
        return new ServerResourceLocator(SERVER,
            ContractRef.of(OWNER, ResourceTypeId.of("flow")), id);
    }

    private static void appendDelete(Fixture fixture, ServerResourceLocator resource,
                                     boolean writeReceipt, boolean mismatchPrecondition) throws Exception {
        UUID mutationId = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database())) {
            connection.setAutoCommit(false);
            if (writeReceipt) {
                insertForwardReceipt(connection, resource, mutationId, "DELETE",
                    mismatchPrecondition ? "f".repeat(64) : currentProtocolHash(connection, resource),
                    null, true);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE resource_mutation_state SET revision = revision + 1, mutation_id = ?, deleted = 1,
                    payload = NULL, activation_state = 'active', updated_at = updated_at + 1 WHERE resource = ?
                """)) {
                statement.setString(1, mutationId.toString());
                statement.setString(2, resource.canonicalText());
                assertEquals(1, statement.executeUpdate());
            }
            connection.commit();
        }
        fixture.core().remove(resource);
    }

    private static void appendLive(Fixture fixture, ServerResourceLocator resource, String operation,
                                   ResourceActivationState activationState) throws Exception {
        appendLive(fixture, resource, operation, activationState,
            CoreCatalogBindingMigration.graph(fixture.core().require(resource)).catalogBinding());
    }

    private static void appendLive(Fixture fixture, ServerResourceLocator resource, String operation,
                                   ResourceActivationState activationState, CatalogBinding catalogBinding) throws Exception {
        appendLive(fixture, resource, operation, activationState, catalogBinding, UUID.randomUUID());
    }

    private static void appendLive(Fixture fixture, ServerResourceLocator resource, String operation,
                                   ResourceActivationState activationState, CatalogBinding catalogBinding,
                                   UUID mutationId) throws Exception {
        CoreGraphStorageBoundary.Decoded current = fixture.core().require(resource);
        long revision = Math.addExact(current.envelope().assetRevision(), 1L);
        GraphDocument graph = current.graphDocument();
        GraphDocument projected = new GraphDocument(graph.schemaVersion(), graph.resource(), revision,
            catalogBinding, graph.requiredCapabilities(), graph.nodes(), graph.connections(), graph.variables(),
            graph.functions(), graph.unknown());
        CoreGraphStorageBoundary.Decoded result = BOUNDARY.decode(BOUNDARY.encode(projected,
            new CoreGraphStorageBoundary.AssetMetadata("flow", revision, mutationId, activationState), resource), resource);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database())) {
            connection.setAutoCommit(false);
            insertForwardReceipt(connection, resource, mutationId, operation, currentProtocolHash(connection, resource),
                result, false);
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE resource_mutation_state SET revision = ?, mutation_id = ?, payload_hash = ?, deleted = 0,
                    payload = NULL, activation_state = ?, asset_hash = ?, core_payload_hash = ?, core_payload_kind = ?,
                    updated_at = updated_at + 1 WHERE resource = ?
                """)) {
                statement.setLong(1, revision);
                statement.setString(2, mutationId.toString());
                statement.setString(3, protocolHash(result));
                statement.setString(4, activationState.wireName());
                statement.setString(5, result.envelope().assetHash().canonicalText());
                statement.setString(6, coreHash(result).canonicalText());
                statement.setString(7, result.corePayloadKind());
                statement.setString(8, resource.canonicalText());
                assertEquals(1, statement.executeUpdate());
            }
            connection.commit();
        }
        fixture.core().put(resource, result);
    }

    private static void appendCompatible(Fixture fixture, ServerResourceLocator resource, CatalogBinding target) throws Exception {
        CoreGraphStorageBoundary.Decoded source = fixture.core().require(resource);
        UUID mutationId = CoreCatalogCompatibilityRebind.mutationId(resource, source, target);
        String fingerprint = StorageSafety.sha256(String.join("\n", CoreCatalogCompatibilityRebind.ID,
            resource.canonicalText(), Long.toString(source.envelope().assetRevision()), source.envelope().assetMutationId(),
            source.envelope().assetHash().canonicalText(), source.graphDocument().catalogBinding().canonicalText(),
            target.canonicalText(), mutationId.toString()));
        appendLive(fixture, resource, "SAVE", source.envelope().assetActivationState(), target, mutationId);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database());
             PreparedStatement statement = connection.prepareStatement("""
                 UPDATE resource_mutation_receipt SET actor_id = ?, fingerprint = ?, target_activation_state = ?
                 WHERE mutation_id = ?
                 """)) {
            statement.setString(1, CoreCatalogCompatibilityRebind.ACTOR);
            statement.setString(2, fingerprint);
            statement.setString(3, source.envelope().assetActivationState().wireName());
            statement.setString(4, mutationId.toString());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void insertForwardReceipt(Connection connection, ServerResourceLocator resource, UUID mutationId,
                                             String operation, String preconditionHash,
                                             CoreGraphStorageBoundary.Decoded result, boolean deleted) throws Exception {
        String resultHash = deleted ? currentProtocolHash(connection, resource) : protocolHash(result);
        String resultAssetHash = deleted ? currentValue(connection, resource, "asset_hash")
            : result.envelope().assetHash().canonicalText();
        String resultCoreHash = deleted ? currentValue(connection, resource, "core_payload_hash")
            : coreHash(result).canonicalText();
        String resultKind = deleted ? currentValue(connection, resource, "core_payload_kind") : result.corePayloadKind();
        String activation = deleted ? null : result.envelope().assetActivationState().wireName();
        long revision = Long.parseLong(currentValue(connection, resource, "revision")) + 1L;
        String transitionEnvelope = deleted
            ? new String(BOUNDARY.encodeTombstone(resource, revision, mutationId, new ContentHash(resultCoreHash)),
                StandardCharsets.UTF_8)
            : new String(BOUNDARY.encode(result), StandardCharsets.UTF_8);
        CoreResourceMutationTransition transition = new CoreResourceMutationTransition(resource, revision, mutationId,
            deleted, deleted ? null : result.envelope().assetActivationState(), "fixture-forward", transitionEnvelope);
        String targetActivation = "ACTIVATE".equals(operation) ? activation : null;
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO resource_mutation_receipt(mutation_id, actor_id, fingerprint, operation,
                requested_resource, response_resource, source_resource, target_resource, expected_revision,
                precondition_hash, status, result_revision, result_mutation_id, result_hash, result_deleted,
                result_payload, target_activation_state, result_activation_state, precondition_asset_hash,
                precondition_core_payload_hash, precondition_core_payload_kind, result_asset_hash,
                result_core_payload_hash, result_core_payload_kind, transition_envelope, transition_hash,
                transition_published, sequence, error_code, error_message, created_at, updated_at)
            SELECT ?, 'fixture-forward', '1f3a861d12b906fc731b8eaba7c213908b8847c1a56a2464f8ee3a28af508210',
                ?, resource, resource, NULL, resource, revision, ?, 'APPLIED',
                revision + 1, ?, ?, ?, NULL, ?, ?, asset_hash, core_payload_hash, core_payload_kind, ?, ?, ?,
                NULL, ?, 1, (SELECT COALESCE(MAX(sequence), 0) + 1 FROM resource_mutation_receipt), '', '',
                CAST(strftime('%s', 'now') AS INTEGER) * 1000, CAST(strftime('%s', 'now') AS INTEGER) * 1000
            FROM resource_mutation_state WHERE resource = ?
            """)) {
            statement.setString(1, mutationId.toString());
            statement.setString(2, operation);
            statement.setString(3, preconditionHash);
            statement.setString(4, mutationId.toString());
            statement.setString(5, resultHash);
            statement.setInt(6, deleted ? 1 : 0);
            statement.setString(7, targetActivation);
            statement.setString(8, activation);
            statement.setString(9, resultAssetHash);
            statement.setString(10, resultCoreHash);
            statement.setString(11, resultKind);
            statement.setString(12, transition.checkpoint().canonicalEnvelopeHash().canonicalText());
            statement.setString(13, resource.canonicalText());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void corruptReceipt(Fixture fixture, ServerResourceLocator resource, String column,
                                       Object value) throws Exception {
        Set<String> supported = Set.of("result_revision", "source_resource", "target_resource", "result_hash",
            "result_core_payload_hash", "result_mutation_id", "transition_hash", "result_asset_hash", "actor_id", "fingerprint");
        if (!supported.contains(column)) {
            throw new IllegalArgumentException("Unsupported receipt fixture column");
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database());
             PreparedStatement statement = connection.prepareStatement("UPDATE resource_mutation_receipt SET "
                 + column + " = ? WHERE mutation_id = (SELECT mutation_id FROM resource_mutation_state WHERE resource = ?)")) {
            statement.setObject(1, value);
            statement.setString(2, resource.canonicalText());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void rollbackToFrozenSource(Fixture fixture, ServerResourceLocator resource) throws Exception {
        CoreGraphStorageBoundary.Decoded source = fixture.sources().get(resource);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database());
             PreparedStatement statement = connection.prepareStatement("""
                 UPDATE resource_mutation_state SET revision = ?, mutation_id = ?, payload_hash = ?, deleted = 0,
                     payload = NULL, activation_state = ?, asset_hash = ?, core_payload_hash = ?, core_payload_kind = ?,
                     updated_at = updated_at + 1 WHERE resource = ?
                 """)) {
            statement.setLong(1, source.envelope().assetRevision());
            statement.setString(2, source.envelope().assetMutationId());
            statement.setString(3, protocolHash(source));
            statement.setString(4, source.envelope().assetActivationState().wireName());
            statement.setString(5, source.envelope().assetHash().canonicalText());
            statement.setString(6, coreHash(source).canonicalText());
            statement.setString(7, source.corePayloadKind());
            statement.setString(8, resource.canonicalText());
            assertEquals(1, statement.executeUpdate());
        }
        fixture.core().put(resource, source);
    }

    private static String currentProtocolHash(Connection connection, ServerResourceLocator resource) throws Exception {
        return currentValue(connection, resource, "payload_hash");
    }

    private static String currentValue(Connection connection, ServerResourceLocator resource, String column) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT " + column + " FROM resource_mutation_state WHERE resource = ?")) {
            statement.setString(1, resource.canonicalText());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static String protocolHash(CoreGraphStorageBoundary.Decoded decoded) {
        Map<String, Object> payload = (Map<String, Object>) CanonicalJson.parse(BOUNDARY.encode(decoded));
        return ResourcePayloadCodecs.json().canonicalize(payload).checksum().canonicalText();
    }

    private static ContentHash coreHash(CoreGraphStorageBoundary.Decoded decoded) {
        return decoded.graphDocument() != null ? decoded.graphDocument().checksum()
            : decoded.functionSourceDocument().checksum();
    }

    private record Fixture(Path database, MigrationReportsPersistenceParticipant reports, MapCoreAuthority core,
                           CoreCatalogBindingMigration migration,
                           Map<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> sources,
                           List<ServerResourceLocator> resources) implements AutoCloseable {
        private SqliteProtocolResourceMutationAuthority open() {
            FlowResourceRegistry registry = new FlowResourceRegistry();
            registry.addCoreMutationListener(transition -> {
            });
            return new SqliteProtocolResourceMutationAuthority(registry, SERVER, database, core,
                ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L), registry, reports, migration);
        }

        @Override
        public void close() throws Exception {
            reports.close();
        }
    }

    private static final class MapCoreAuthority implements CoreGraphResourceAuthority {
        private final Map<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> current = new LinkedHashMap<>();
        private CoreCatalogEvolution.Proof evolution;

        private MapCoreAuthority(Map<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> sources) {
            current.putAll(sources);
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public Optional<CatalogBinding> activeCatalogBinding() {
            return evolution == null ? Optional.empty() : Optional.of(evolution.target());
        }

        @Override
        public Optional<CoreCatalogEvolution.Proof> activeCatalogEvolution() {
            return Optional.ofNullable(evolution);
        }

        @Override
        public Optional<CoreGraphStorageBoundary.Decoded> load(ServerResourceLocator resource) {
            return Optional.ofNullable(current.get(resource));
        }

        @Override
        public List<CoreGraphResourceState> list(String type) {
            return current.entrySet().stream().filter(entry -> type.equals(entry.getKey().resourceType().value()))
                .map(entry -> CoreGraphResourceState.live(entry.getValue())).toList();
        }

        @Override
        public Optional<CoreGraphResourceState> state(ServerResourceLocator resource) {
            return load(resource).map(CoreGraphResourceState::live);
        }

        @Override
        public void validateSave(ServerResourceLocator resource, CoreGraphStorageBoundary.Decoded canonicalEnvelope) {
        }

        @Override
        public boolean acceptsCatalogRebind(CoreCatalogBindingMigration migration) {
            return true;
        }

        @Override
        public Optional<CoreCatalogRebindResult> rebindCatalog(ServerResourceLocator resource,
                                                               CoreCatalogRebindSource source, UUID mutationId,
                                                               CoreCatalogBindingMigration migration) {
            CoreGraphStorageBoundary.Decoded rebound = migration.project(source.decoded(), mutationId);
            current.put(resource, rebound);
            return Optional.of(new CoreCatalogRebindResult(rebound, false));
        }

        @Override
        public CoreGraphStorageBoundary.Decoded save(ServerResourceLocator resource, byte[] canonicalEnvelope,
                                                      UUID mutationId, long expectedRevision,
                                                      ContentHash payloadChecksum) {
            CoreGraphStorageBoundary.Decoded saved = BOUNDARY.decode(canonicalEnvelope, resource);
            current.put(resource, saved);
            return saved;
        }

        @Override
        public CoreGraphStorageBoundary.CoreGraphTombstone delete(ServerResourceLocator resource, UUID mutationId,
                                                                   long expectedRevision, ContentHash payloadChecksum) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CoreGraphStorageBoundary.Decoded activate(ServerResourceLocator resource,
                                                          ResourceActivationState activationState, UUID mutationId,
                                                          long expectedRevision, ContentHash payloadChecksum) {
            throw new UnsupportedOperationException();
        }

        private CoreGraphStorageBoundary.Decoded require(ServerResourceLocator resource) {
            return Optional.ofNullable(current.get(resource)).orElseThrow();
        }

        private void put(ServerResourceLocator resource, CoreGraphStorageBoundary.Decoded value) {
            current.put(resource, value);
        }

        private void remove(ServerResourceLocator resource) {
            current.remove(resource);
        }
    }
}
