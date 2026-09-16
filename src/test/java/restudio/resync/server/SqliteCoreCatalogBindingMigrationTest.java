package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourceDocument;
import restudio.resync.migration.MigrationReportsPersistenceParticipant;
import restudio.resync.modules.flow.FlowResourceRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteCoreCatalogBindingMigrationTest {
    private static final ServerId SERVER = ServerId.deterministic("core-catalog-rebind-integration-test");

    @Test
    void schemaVersionOwnsTheDurableCatalogRebindPlan(@TempDir Path root) throws Exception {
        assertEquals("resource-mutation-authority-v8", SqliteProtocolResourceMutationAuthority.SCHEMA);
        assertEquals("core-catalog-binding-rebind-v1", CoreCatalogBindingMigration.ID);
        Fixture fixture = fixture(root);
        try {
            try (SqliteProtocolResourceMutationAuthority ignored = fixture.open();
                 var connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database());
                 var columns = connection.prepareStatement("PRAGMA table_info(core_catalog_evolution_receipt)");
                 var foreignKeys = connection.prepareStatement("PRAGMA foreign_key_list(core_catalog_evolution_receipt)")) {
                Set<String> actualColumns = new LinkedHashSet<>();
                try (var result = columns.executeQuery()) {
                    while (result.next()) {
                        String name = result.getString("name");
                        actualColumns.add(name);
                        if ("mutation_id".equals(name)) {
                            assertEquals(1, result.getInt("pk"));
                        } else {
                            assertEquals(1, result.getInt("notnull"));
                        }
                    }
                }
                assertEquals(Set.of("mutation_id", "registration_hash", "source_envelope", "target_binding",
                    "result_asset_hash", "proof_hash"), actualColumns);
                try (var result = foreignKeys.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("resource_mutation_receipt", result.getString("table"));
                    assertEquals("mutation_id", result.getString("from"));
                    assertEquals("mutation_id", result.getString("to"));
                    assertEquals("NO ACTION", result.getString("on_delete"));
                    assertFalse(result.next());
                }
            }
        } finally {
            fixture.reports().close();
        }
    }

    @Test
    void durableReportAuthorityIsRequiredBeforeMigrationPlanning(@TempDir Path root) throws Exception {
        assertFalse(SqliteProtocolResourceMutationAuthority.catalogRebindReportsAvailable(null));
        MigrationReportsPersistenceParticipant reports = new MigrationReportsPersistenceParticipant(root);
        assertFalse(SqliteProtocolResourceMutationAuthority.catalogRebindReportsAvailable(reports));

        reports.admit();

        assertTrue(SqliteProtocolResourceMutationAuthority.catalogRebindReportsAvailable(reports));
        reports.close();
    }

    @Test
    void incompleteOldCatalogBindingCannotPublishCoreReadAuthority(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, false);

        try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
            assertFalse(authority.authoritativeCoreReads());
            assertEquals(CoreCatalogBindingMigration.SOURCE,
                CoreCatalogBindingMigration.graph(fixture.core().current).catalogBinding());
            assertThrows(IllegalStateException.class, () -> authority.load(resource()));
            assertThrows(IllegalStateException.class, () -> authority.list(SERVER, resource().type(), ""));
        }
        fixture.reports().close();
    }

    @Test
    void migratesOnceAndVerifiesTheCompleteProofOnRestart(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        UUID firstMutation;
        long firstRevision;
        try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
            firstMutation = UUID.fromString(fixture.core().current.envelope().assetMutationId());
            firstRevision = fixture.core().current.envelope().assetRevision();
            assertEquals(CoreCatalogBindingMigration.TARGET,
                CoreCatalogBindingMigration.graph(fixture.core().current).catalogBinding());
            ResourceDocument<Map<String, Object>> loaded = authority.load(resource());
            List<ResourceDocument<Map<String, Object>>> listed = authority.list(SERVER, resource().type(), "");
            assertEquals(1, listed.size());
            assertEquals(firstRevision, loaded.revision());
            assertEquals(firstRevision, listed.getFirst().revision());
            assertEquals(loaded.mutationId(), listed.getFirst().mutationId());
            assertEquals(loaded.payloadHash(), listed.getFirst().payloadHash());
            assertEquals(loaded.payload(), listed.getFirst().payload());
        }

        try (SqliteProtocolResourceMutationAuthority ignored = fixture.open()) {
            assertEquals(firstMutation, UUID.fromString(fixture.core().current.envelope().assetMutationId()));
            assertEquals(firstRevision, fixture.core().current.envelope().assetRevision());
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database());
             var run = connection.prepareStatement(
                 "SELECT status, report_hash FROM core_catalog_rebind_run WHERE migration_id = ?");
             var items = connection.prepareStatement(
                 "SELECT COUNT(*), MIN(status), MAX(status) FROM core_catalog_rebind_item WHERE migration_id = ?");
             var receipts = connection.prepareStatement(
                 "SELECT COUNT(*) FROM resource_mutation_receipt WHERE actor_id = ?")) {
            run.setString(1, CoreCatalogBindingMigration.ID);
            try (var result = run.executeQuery()) {
                assertTrue(result.next());
                assertEquals("COMPLETE", result.getString(1));
                assertEquals(64, result.getString(2).length());
            }
            items.setString(1, CoreCatalogBindingMigration.ID);
            try (var result = items.executeQuery()) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
                assertEquals("APPLIED", result.getString(2));
                assertEquals("APPLIED", result.getString(3));
            }
            receipts.setString(1, CoreCatalogBindingMigration.ACTOR);
            try (var result = receipts.executeQuery()) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
        fixture.reports().close();
    }

    @Test
    void rejectsMissingOrTamperedCompleteReportsOnRestart(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        try (SqliteProtocolResourceMutationAuthority ignored = fixture.open()) {
        }
        Path report = fixture.reports().root().resolve(
            MigrationReportsPersistenceParticipant.CATALOG_REBIND_REPORT_FILE);
        String canonical = Files.readString(report);
        Files.writeString(report, canonical.replaceFirst("[a-f0-9]{64}", "0".repeat(64)));

        assertThrows(IllegalStateException.class, fixture::open);

        Files.writeString(report, canonical);
        Files.delete(report);
        assertThrows(IllegalStateException.class, fixture::open);
        fixture.reports().close();
    }

    @Test
    void durablyBlocksWhenAnExactIncidentCannotBeApplied(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        fixture.core().current = null;

        assertThrows(IllegalStateException.class, fixture::open);

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database());
             var run = connection.prepareStatement(
                 "SELECT status FROM core_catalog_rebind_run WHERE migration_id = ?");
             var items = connection.prepareStatement(
                 "SELECT COUNT(*), MIN(status), MAX(status) FROM core_catalog_rebind_item WHERE migration_id = ?")) {
            run.setString(1, CoreCatalogBindingMigration.ID);
            try (var result = run.executeQuery()) {
                assertTrue(result.next());
                assertEquals("BLOCKED", result.getString(1));
            }
            items.setString(1, CoreCatalogBindingMigration.ID);
            try (var result = items.executeQuery()) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
                assertEquals("REJECTED", result.getString(2));
                assertEquals("REJECTED", result.getString(3));
            }
        }
        fixture.reports().close();
    }

    @Test
    void rebindDefersCatalogProofUntilAllParticipantsUseTheCandidateRoot(@TempDir Path root) throws Exception {
        Path sourceRoot = root.resolve("source");
        Path candidateRoot = root.resolve("candidate");
        Files.createDirectories(sourceRoot);
        Files.createDirectories(candidateRoot.resolve("runtime"));
        Files.createDirectories(candidateRoot.resolve(MigrationReportsPersistenceParticipant.DIRECTORY));
        Fixture fixture = fixture(sourceRoot, false);
        SqliteProtocolResourceMutationAuthority authority = fixture.open();
        SqliteProtocolResourceMutationPersistenceParticipant mutationParticipant =
            new SqliteProtocolResourceMutationPersistenceParticipant(sourceRoot, authority);
        fixture.reports().admit();
        mutationParticipant.quiesce();
        fixture.reports().quiesce();
        Files.copy(fixture.database(), candidateRoot.resolve("runtime/resource-mutations.db"));

        fixture.reports().rebind(candidateRoot);
        mutationParticipant.rebind(candidateRoot);
        mutationParticipant.resume();
        fixture.reports().resume();

        assertEquals(candidateRoot.resolve("runtime/resource-mutations.db").toAbsolutePath().normalize(),
            authority.databasePath());
        assertEquals(CoreCatalogBindingMigration.TARGET,
            CoreCatalogBindingMigration.graph(fixture.core().current).catalogBinding());
        authority.close();
        fixture.reports().close();
    }

    @Test
    void quiescedHealthIsLocalWhileResumeAndOpenHealthRequireCatalogProof(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        SqliteProtocolResourceMutationAuthority authority = fixture.open();
        SqliteProtocolResourceMutationPersistenceParticipant participant =
            new SqliteProtocolResourceMutationPersistenceParticipant(root, authority);
        Path report = fixture.reports().root().resolve(
            MigrationReportsPersistenceParticipant.CATALOG_REBIND_REPORT_FILE);
        String canonical = Files.readString(report);

        participant.quiesce();
        Files.delete(report);
        participant.healthCheck();
        assertThrows(IOException.class, participant::resume);
        assertTrue(authority.isQuiesced());

        Files.writeString(report, canonical);
        participant.resume();
        Files.delete(report);
        assertThrows(IOException.class, participant::healthCheck);

        authority.close();
        fixture.reports().close();
    }

    private static Fixture fixture(Path root) throws Exception {
        return fixture(root, true);
    }

    private static Fixture fixture(Path root, boolean admitReports) throws Exception {
        MigrationReportsPersistenceParticipant reports = new MigrationReportsPersistenceParticipant(root);
        if (admitReports) {
            reports.admit();
        }
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        ServerResourceLocator resource = resource();
        UUID sourceMutation = UUID.fromString("25376044-4303-4e84-8470-7ab04e123249");
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, 4L,
            CoreCatalogBindingMigration.SOURCE, Set.of(), List.of(), List.of(), List.of(), List.of(),
            OpaqueData.empty());
        CoreGraphStorageBoundary.Decoded source = boundary.decode(boundary.encode(graph,
            new CoreGraphStorageBoundary.AssetMetadata("flow", 4L, sourceMutation, ResourceActivationState.ACTIVE),
            resource), resource);
        String manifest = "{\"format\":\"core-catalog-binding-rebind-v1\",\"incidents\":[{\"assetHash\":\""
            + source.envelope().assetHash().canonicalText() + "\",\"id\":\"ad\",\"mutationId\":\""
            + sourceMutation + "\",\"revision\":4,\"type\":\"flow\"}],\"source\":{\"bindingManifestHash\":\""
            + CoreCatalogBindingMigration.SOURCE.bindingManifestHash().canonicalText()
            + "\",\"catalogChecksum\":\"" + CoreCatalogBindingMigration.SOURCE.catalogChecksum().canonicalText()
            + "\",\"generation\":53},\"target\":{\"bindingManifestHash\":\""
            + CoreCatalogBindingMigration.TARGET.bindingManifestHash().canonicalText()
            + "\",\"catalogChecksum\":\"" + CoreCatalogBindingMigration.TARGET.catalogChecksum().canonicalText()
            + "\",\"generation\":54}}";
        return new Fixture(root.resolve("runtime/resource-mutations.db"), reports, new TestCoreAuthority(source),
            CoreCatalogBindingMigration.parse(manifest));
    }

    private static ServerResourceLocator resource() {
        return new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("flow")), "ad");
    }

    private record Fixture(Path database, MigrationReportsPersistenceParticipant reports,
                           TestCoreAuthority core, CoreCatalogBindingMigration migration) {
        private SqliteProtocolResourceMutationAuthority open() {
            FlowResourceRegistry registry = new FlowResourceRegistry();
            return new SqliteProtocolResourceMutationAuthority(registry, SERVER, database, core,
                ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L), registry, reports, migration);
        }

    }

    private static final class TestCoreAuthority implements CoreGraphResourceAuthority {
        private final CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        private CoreGraphStorageBoundary.Decoded current;

        private TestCoreAuthority(CoreGraphStorageBoundary.Decoded current) {
            this.current = current;
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
        }

        @Override
        public boolean acceptsCatalogRebind(CoreCatalogBindingMigration migration) {
            return true;
        }

        @Override
        public Optional<CoreCatalogRebindResult> rebindCatalog(ServerResourceLocator resource,
                                                               CoreCatalogRebindSource source, UUID mutationId,
                                                               CoreCatalogBindingMigration migration) {
            current = migration.project(source.decoded(), mutationId);
            return Optional.of(new CoreCatalogRebindResult(current, false));
        }

        @Override
        public CoreGraphStorageBoundary.Decoded save(ServerResourceLocator resource, byte[] canonicalEnvelope,
                                                      UUID mutationId, long expectedRevision,
                                                      ContentHash payloadChecksum) {
            current = boundary.decode(canonicalEnvelope, resource);
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
