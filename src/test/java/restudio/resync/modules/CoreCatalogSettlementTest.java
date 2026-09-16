package restudio.resync.modules;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.catalog.CatalogActivationAuthority;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.runtime.RuntimeBinding;
import restudio.resync.flow.runtime.RuntimeBindingDescriptor;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeResult;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.migration.MigrationReportsPersistenceParticipant;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.server.AuthorityEpoch;
import restudio.resync.server.CoreCatalogBindingMigration;
import restudio.resync.server.CoreCatalogEvolution;
import restudio.resync.server.CoreGraphMutationValidator;
import restudio.resync.server.CoreGraphResourceAuthority;
import restudio.resync.server.ProtocolResourceAuthorizer;
import restudio.resync.server.SqliteProtocolResourceMutationAuthority;
import restudio.resync.storage.StorageSafety;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CoreCatalogSettlementTest {
    private static final String FIXTURE_ENVIRONMENT = "RESYNC_CATALOG_SETTLEMENT_FIXTURE";
    private static final String HASH_MANIFEST = "acceptance-fixture-hashes.json";
    private static final String HASH_MANIFEST_HASH = "9dff0938bdec52b395ee338d32411f6cc52ad97935a8c00343f20e2a022b7dad";
    private static final String EVOLUTION_ACTOR = "core-catalog-evolution-v3";
    private static final ServerId SERVER = ServerId.parseCanonicalText("e65887a4-ea27-4c55-bae2-e1c8d92da433");
    private static final CatalogBinding SOURCE = new CatalogBinding(57L,
        new ContentHash("d9c25c92807d45e79d80fcb941fdf181111d7af53c7f9f93126a62b310377a13"),
        new ContentHash("eb30c3b9bad4a5f57d1e62af0f66b25bf3d31d1d6b4c2158d6e5f2d2e417d0b4"));
    private static final CatalogBinding TARGET = new CatalogBinding(58L,
        new ContentHash("d79bad6b646cf8b0483bbd29d4936734b6eb1cf2e20dfbfcb8738098b88f72ad"),
        new ContentHash("eb30c3b9bad4a5f57d1e62af0f66b25bf3d31d1d6b4c2158d6e5f2d2e417d0b4"));
    private static final CoreGraphStorageBoundary BOUNDARY = new CoreGraphStorageBoundary();
    private static final Map<String, String> FIXTURE_HASHES = Map.of(
        "resource-mutations.db", "5b8f26201bf3e688666b3fe5104d95c1e8ce15c188df21e76dc7654fa2a6418f",
        "asdgasd.json", "a39bdd92d92dde7b449fa7c9a82e61cc9a87182a0aabb7933168cd04c75c315c",
        "ww.json", "2d014d41f6a326f41357feb57e05133fbe29185adc0359cf6d59b7299b95223a",
        "blockBreak.json", "bb17dca89e70c0b73fae18d2de4b425e5cd5e5764ecbc426d08394fc3c666ccf",
        "core-catalog-binding-rebind-v1.json", "278c354430a2aab2fbba8848d188666c1cb918fce4ea9ca8d067e0f400349c9e",
        "source-catalog-57.json", "8734164cf1306eb37396feee6fb5494215edcd01af42873d1958ea2535e01766",
        "current-catalog.json", "0d515173207d5e9df65d9b3537226229b71d422a25468ded36fcbc2f46a29266");

    @Test
    void settlesTheExactSavedCatalogLineageOnceAndPreservesRejectedSources(@TempDir Path temporary) throws Exception {
        Path fixture = fixture();
        verifyFixture(fixture);
        try {
            Map<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> sources = sources(fixture);
            assertSavedHeads(fixture.resolve("resource-mutations.db"), sources);
            verifyCompletedHistoricalRebind(fixture, temporary.resolve("historical"), sources);
            Path catalogRoot = Files.createDirectory(temporary.resolve("catalog"));
            CoreCatalogBaselineProofTest.withCurrentCatalog(catalogRoot, TARGET.generation() + 1L, current -> {
                CatalogRuntimeActivation.ActivationRecord activation = historicalActivation(fixture, current);
                assertEquals(TARGET, binding(activation));
                assertEquals(TARGET, CoreCatalogEvolution.select(TARGET).orElseThrow().prove(
                    activation.catalog(), TARGET).target());
                verifySettlement(fixture, temporary.resolve("settled"), sources, activation);
                verifyValidatorDenial(fixture, temporary.resolve("rejected"), sources, activation);
            });
        } finally {
            verifyFixture(fixture);
        }
    }

    private static void verifyCompletedHistoricalRebind(
        Path fixture,
        Path scope,
        Map<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> sources
    ) throws Exception {
        Path database = copyDatabase(fixture, scope);
        Path reportsRoot = Files.createDirectories(scope.resolve(MigrationReportsPersistenceParticipant.DIRECTORY));
        Files.copy(fixture.resolve(MigrationReportsPersistenceParticipant.CATALOG_REBIND_REPORT_FILE),
            reportsRoot.resolve(MigrationReportsPersistenceParticipant.CATALOG_REBIND_REPORT_FILE),
            StandardCopyOption.COPY_ATTRIBUTES);
        DatabaseSnapshot before = snapshot(database);
        assertEquals(13, deletedRebindItems(database));
        MigrationReportsPersistenceParticipant reports = new MigrationReportsPersistenceParticipant(scope);
        try {
            reports.admit();
            FlowResourceRegistry registry = new FlowResourceRegistry();
            try (SqliteProtocolResourceMutationAuthority ignored = new SqliteProtocolResourceMutationAuthority(
                registry, SERVER, database, new HistoricalAuthority(sources),
                ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L), registry, reports,
                SqliteProtocolResourceMutationAuthority.CatalogStartup.DEFERRED)) {
            }
        } finally {
            reports.close();
        }
        assertEquals(before, snapshot(database));
    }

    private static void verifySettlement(
        Path fixture,
        Path scope,
        Map<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> sources,
        CatalogRuntimeActivation.ActivationRecord activation
    ) throws Exception {
        Path database = copyDatabase(fixture, scope);
        DatabaseSnapshot before = snapshot(database);
        AtomicInteger publications = new AtomicInteger();
        FixtureAuthority core = new FixtureAuthority(sources, validator(activation), null);
        CoreCatalogEvolution.Proof proof = core.activeCatalogEvolution().orElseThrow();
        assertEquals(TARGET, proof.target());
        assertTrue(sources.values().stream().allMatch(proof::eligible));
        settle(database, core, publications);

        assertEquals(3, core.saves());
        assertEquals(3, publications.get());
        assertProjectedSources(sources, core.current());
        DatabaseSnapshot settled = snapshot(database);
        assertPreservedRows(before.receipts(), settled.receipts());
        assertPreservedRows(before.evolutions(), settled.evolutions());
        assertEquals(before.rebindRuns(), settled.rebindRuns());
        assertEquals(before.rebindItems(), settled.rebindItems());
        assertPreservedStates(before.states(), settled.states(), sources.keySet());
        assertEquals(before.receipts().size() + 3, settled.receipts().size());
        assertEquals(before.evolutions().size() + 3, settled.evolutions().size());
        assertEvolutionOutcomes(database, sources, core.current());

        for (int reopen = 0; reopen < 3; reopen++) {
            settle(database, core, publications);
            assertEquals(settled, snapshot(database));
            assertEquals(3, core.saves());
            assertEquals(3, publications.get());
            assertProjectedSources(sources, core.current());
        }
    }

    private static void verifyValidatorDenial(
        Path fixture,
        Path scope,
        Map<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> sources,
        CatalogRuntimeActivation.ActivationRecord activation
    ) throws Exception {
        Path database = copyDatabase(fixture, scope);
        DatabaseSnapshot before = snapshot(database);
        ServerResourceLocator denied = resource("command", "asdgasd");
        AtomicInteger publications = new AtomicInteger();
        FixtureAuthority core = new FixtureAuthority(sources, validator(activation), denied);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> settle(database, core, publications));

        assertEquals("Injected validator denial", failure.getMessage());
        assertEquals(0, core.saves());
        assertEquals(0, publications.get());
        assertEquals(sources, core.current());
        assertEquals(before, snapshot(database));
        assertEquals(0, actorCount(database, EVOLUTION_ACTOR));
    }

    private static CoreGraphMutationValidator validator(CatalogRuntimeActivation.ActivationRecord activation) {
        CatalogActivationAuthority authority = CatalogActivationAuthority.freshInstall();
        return new CoreGraphMutationValidator(SERVER, () -> activation, () -> authority);
    }

    private static CatalogRuntimeActivation.ActivationRecord historicalActivation(
        Path fixture,
        CatalogRuntimeActivation.ActivationRecord current
    ) throws Exception {
        String content = Files.readString(fixture.resolve("current-catalog.json"));
        assertEquals(FIXTURE_HASHES.get("current-catalog.json"), StorageSafety.sha256(content));
        JsonObject snapshot = JsonParser.parseString(content).getAsJsonObject();
        assertEquals(1L, snapshot.get("generation").getAsLong());
        snapshot.addProperty("generation", TARGET.generation());
        String canonicalContent = snapshot.toString();
        List<CatalogContribution> contributions = current.catalog().contributions().stream()
            .map(CoreCatalogSettlementTest::withoutResourceReads).toList();
        CatalogSnapshot catalog = new CatalogSnapshot(TARGET.generation(), current.catalog().contractVersion(),
            TARGET.catalogChecksum(), TARGET.bindingManifestHash(), current.catalog().minimumClientCapabilities(), contributions,
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), canonicalContent, TARGET.bindingManifestHash());
        assertEquals(canonicalContent, catalog.canonicalContent());
        assertEquals(TARGET, new CatalogBinding(catalog.generation(), catalog.contentChecksum(), catalog.bindingManifestHash()));

        assertTrue(current.runtimeManifest().invalidationInputs().values().stream().allMatch(Map::isEmpty));
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        for (var provider : current.runtime().providers().values().stream().sorted(
            Comparator.comparing(value -> value.provider().canonicalText())).toList()) {
            List<RuntimeBinding> bindings = current.runtime().bindingValues().stream()
                .filter(value -> value.provider().equals(provider.provider()))
                .map(CoreCatalogSettlementTest::withoutResourceReads).toList();
            registry.activate(provider, bindings);
        }
        assertEquals(TARGET.bindingManifestHash(), registry.snapshot().bindingManifestHash());
        return new CatalogRuntimeActivation.ActivationRecord(catalog, registry.snapshot());
    }

    private static CatalogContribution withoutResourceReads(CatalogContribution contribution) {
        List<CatalogNodeDescriptor> definitions = contribution.definitions().stream().map(definition ->
            new CatalogNodeDescriptor(definition.id(), definition.schemaVersion(), definition.lifecycle(), definition.domain(),
                definition.family(), definition.displayName(), definition.description(), definition.category(), definition.pins(),
                definition.modes(), definition.branches(), definition.repeatables(), definition.inspector(), definition.handler(),
                withoutResourceReads(definition.semantics()), definition.requiredCapabilities(), definition.replacementIdentity(),
                definition.preview(), definition.metadata())).toList();
        List<RuntimeOperationDescriptor> runtime = contribution.runtimeRequirements().stream().map(requirement ->
            new RuntimeOperationDescriptor(requirement.capability(), requirement.operation(), requirement.inputs(),
                requirement.outputs(), withoutResourceReads(requirement.semantics()), requirement.unknown(), requirement.pins())).toList();
        return new CatalogContribution(contribution.ownerId(), contribution.version(), contribution.contractRange(),
            contribution.dependencies(), definitions, contribution.types(), contribution.conversions(), contribution.categories(),
            contribution.inspectors(), contribution.capabilities(), runtime, contribution.migrations(), contribution.optionSources(),
            contribution.validators(), contribution.editors(), contribution.previews(), contribution.provenance());
    }

    private static RuntimeBinding withoutResourceReads(RuntimeBinding binding) {
        RuntimeBindingDescriptor descriptor = binding.descriptor();
        RuntimeBindingDescriptor historical = new RuntimeBindingDescriptor(descriptor.capability(), descriptor.operation(),
            descriptor.provider(), descriptor.providerVersion(), descriptor.inputs(), descriptor.outputs(),
            withoutResourceReads(descriptor.semantics()), descriptor.available(), descriptor.unknown(), descriptor.pins());
        return historical.available()
            ? new RuntimeBinding(historical, ignored -> CompletableFuture.completedFuture(RuntimeResult.success()))
            : new RuntimeBinding(historical);
    }

    private static RuntimeSemantics withoutResourceReads(RuntimeSemantics semantics) {
        return new RuntimeSemantics(semantics.effect(), semantics.thread(), semantics.authorization(), semantics.cancellation(),
            semantics.timeoutMillis(), semantics.drainDeadlineMillis(), semantics.hardDeadlineMillis(), semantics.unloadPolicy(),
            semantics.retry(), semantics.idempotency(), semantics.audit(), semantics.confirmation(), semantics.sensitiveData(),
            semantics.determinism(), semantics.successBranches(), semantics.failureBranches(), semantics.cancellationBranches(),
            semantics.failureContract(), Set.of(), semantics.resourceWrites(), semantics.unknown());
    }

    private static void settle(Path database, FixtureAuthority core, AtomicInteger publications) {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.addCoreMutationListener(ignored -> publications.incrementAndGet());
        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(
            registry, SERVER, database, core, ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L),
            registry, null, SqliteProtocolResourceMutationAuthority.CatalogStartup.DEFERRED)) {
            authority.settleCatalogBinding(TARGET);
            assertTrue(authority.durable());
            assertTrue(authority.authoritativeCoreReads());
        }
    }

    private static void assertProjectedSources(
        Map<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> sources,
        Map<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> current
    ) {
        assertEquals(sources.keySet(), current.keySet());
        for (Map.Entry<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> entry : sources.entrySet()) {
            CoreGraphStorageBoundary.Decoded before = entry.getValue();
            CoreGraphStorageBoundary.Decoded after = current.get(entry.getKey());
            GraphDocument source = before.graphDocument();
            GraphDocument result = after.graphDocument();
            assertNotNull(source);
            assertNotNull(result);
            assertEquals(source.revision() + 1L, result.revision());
            assertEquals(source.revision() + 1L, after.envelope().assetRevision());
            assertEquals(TARGET, result.catalogBinding());
            assertNotEquals(before.envelope().assetMutationId(), after.envelope().assetMutationId());
            assertNotEquals(before.envelope().assetHash(), after.envelope().assetHash());
            JsonObject original = JsonParser.parseString(source.canonicalJson()).getAsJsonObject();
            JsonObject normalized = JsonParser.parseString(result.canonicalJson()).getAsJsonObject();
            normalized.add("catalogBinding", original.get("catalogBinding"));
            normalized.add("revision", original.get("revision"));
            assertEquals(original, normalized);
            assertEquals(before.envelope().resourceType(), after.envelope().resourceType());
            assertEquals(before.envelope().assetFormatVersion(), after.envelope().assetFormatVersion());
            assertEquals(before.envelope().assetActivationState(), after.envelope().assetActivationState());
        }
    }

    private static void assertEvolutionOutcomes(
        Path database,
        Map<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> sources,
        Map<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> current
    ) throws Exception {
        Set<ServerResourceLocator> observed = new LinkedHashSet<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement("""
                 SELECT r.*, e.source_envelope, e.target_binding, e.result_asset_hash AS evidence_result_asset_hash,
                     e.proof_hash, s.revision AS state_revision, s.mutation_id AS state_mutation_id,
                     s.asset_hash AS state_asset_hash FROM resource_mutation_receipt r
                 JOIN core_catalog_evolution_receipt e ON e.mutation_id = r.mutation_id
                 JOIN resource_mutation_state s ON s.resource = r.response_resource
                 WHERE r.actor_id = ? ORDER BY r.response_resource
                 """)) {
            statement.setString(1, EVOLUTION_ACTOR);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ServerResourceLocator resource = ServerResourceLocator.parseCanonicalText(
                        result.getString("response_resource"));
                    assertTrue(observed.add(resource));
                    CoreGraphStorageBoundary.Decoded source = sources.get(resource);
                    CoreGraphStorageBoundary.Decoded after = current.get(resource);
                    assertNotNull(source);
                    assertNotNull(after);
                    assertEquals("SAVE", result.getString("operation"));
                    assertEquals("APPLIED", result.getString("status"));
                    assertEquals(source.envelope().assetRevision(), result.getLong("expected_revision"));
                    assertEquals(source.envelope().assetHash().canonicalText(),
                        result.getString("precondition_asset_hash"));
                    assertEquals(after.envelope().assetRevision(), result.getLong("result_revision"));
                    assertEquals(result.getString("mutation_id"), result.getString("result_mutation_id"));
                    assertEquals(after.envelope().assetHash().canonicalText(), result.getString("result_asset_hash"));
                    assertEquals(after.envelope().assetHash().canonicalText(),
                        result.getString("evidence_result_asset_hash"));
                    assertEquals(after.envelope().assetRevision(), result.getLong("state_revision"));
                    assertEquals(after.envelope().assetMutationId(), result.getString("state_mutation_id"));
                    assertEquals(after.envelope().assetHash().canonicalText(), result.getString("state_asset_hash"));
                    assertEquals(TARGET.canonicalText(), result.getString("target_binding"));
                    assertEquals(new String(BOUNDARY.encode(source), StandardCharsets.UTF_8),
                        result.getString("source_envelope"));
                    assertEquals(1, result.getInt("transition_published"));
                    assertEquals(null, result.getString("transition_envelope"));
                    assertEquals(null, result.getString("result_payload"));
                    assertTrue(result.getLong("sequence") > 0L);
                    assertFalse(result.getString("transition_hash").isBlank());
                    assertFalse(result.getString("proof_hash").isBlank());
                }
            }
        }
        assertEquals(sources.keySet(), observed);
    }

    private static void assertSavedHeads(
        Path database,
        Map<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> sources
    ) throws Exception {
        assertEquals(3, liveCoreHeads(database));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var state = connection.prepareStatement("SELECT * FROM resource_mutation_state WHERE resource = ?");
             var receipt = connection.prepareStatement(
                 "SELECT * FROM resource_mutation_receipt WHERE mutation_id = ?")) {
            for (Map.Entry<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> entry : sources.entrySet()) {
                CoreGraphStorageBoundary.Decoded source = entry.getValue();
                assertEquals(SOURCE, source.graphDocument().catalogBinding());
                state.setString(1, entry.getKey().canonicalText());
                try (ResultSet result = state.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(0, result.getInt("deleted"));
                    assertEquals(source.envelope().assetRevision(), result.getLong("revision"));
                    assertEquals(source.envelope().assetMutationId(), result.getString("mutation_id"));
                    assertEquals(source.envelope().assetHash().canonicalText(), result.getString("asset_hash"));
                }
                receipt.setString(1, source.envelope().assetMutationId());
                try (ResultSet result = receipt.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("APPLIED", result.getString("status"));
                    assertEquals(1, result.getInt("transition_published"));
                    assertEquals(source.envelope().assetRevision(), result.getLong("result_revision"));
                    assertEquals(source.envelope().assetHash().canonicalText(), result.getString("result_asset_hash"));
                }
            }
        }
    }

    private static void assertPreservedRows(Map<String, List<String>> before, Map<String, List<String>> after) {
        before.forEach((key, value) -> assertEquals(value, after.get(key), key));
    }

    private static void assertPreservedStates(
        Map<String, List<String>> before,
        Map<String, List<String>> after,
        Set<ServerResourceLocator> changed
    ) {
        Set<String> changedKeys = changed.stream().map(ServerResourceLocator::canonicalText)
            .collect(Collectors.toSet());
        before.forEach((key, value) -> {
            if (!changedKeys.contains(key)) {
                assertEquals(value, after.get(key), key);
            }
        });
    }

    private static Map<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> sources(Path fixture) throws Exception {
        Map<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> sources = new LinkedHashMap<>();
        sources.put(resource("command", "asdgasd"), decoded(fixture, "asdgasd.json", "command", "asdgasd"));
        sources.put(resource("command", "ww"), decoded(fixture, "ww.json", "command", "ww"));
        sources.put(resource("flow", "blockBreak"), decoded(fixture, "blockBreak.json", "flow", "blockBreak"));
        return Map.copyOf(sources);
    }

    private static CoreGraphStorageBoundary.Decoded decoded(
        Path fixture,
        String file,
        String type,
        String id
    ) throws Exception {
        ServerResourceLocator resource = resource(type, id);
        CoreGraphStorageBoundary.Decoded decoded = BOUNDARY.decodeText(
            Files.readString(fixture.resolve(file)), resource);
        assertEquals(resource, decoded.graphDocument().resource());
        assertEquals(type, decoded.envelope().resourceType());
        return decoded;
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(type)), id);
    }

    private static CatalogBinding binding(CatalogRuntimeActivation.ActivationRecord activation) {
        return new CatalogBinding(activation.catalog().generation(), activation.catalog().contentChecksum(),
            activation.runtimeManifest().bindingManifestHash());
    }

    private static Path fixture() {
        String configured = System.getenv(FIXTURE_ENVIRONMENT);
        assumeTrue(configured != null && !configured.isBlank(), "An explicit catalog settlement fixture is required");
        Path fixture = Path.of(configured).toAbsolutePath().normalize();
        assumeTrue(Files.isDirectory(fixture), "The catalog settlement fixture directory is required");
        return fixture;
    }

    private static void verifyFixture(Path fixture) throws Exception {
        Path manifest = fixture.resolve(HASH_MANIFEST);
        assertEquals(HASH_MANIFEST_HASH, StorageSafety.sha256(Files.readAllBytes(manifest)));
        Map<String, String> declared = new LinkedHashMap<>();
        for (JsonElement value : JsonParser.parseString(Files.readString(manifest)).getAsJsonArray()) {
            JsonObject entry = value.getAsJsonObject();
            assertEquals(null,
                declared.put(entry.get("Path").getAsString(), entry.get("SHA256").getAsString().toLowerCase()));
        }
        assertEquals(FIXTURE_HASHES, declared);
        for (Map.Entry<String, String> entry : FIXTURE_HASHES.entrySet()) {
            assertEquals(entry.getValue(),
                StorageSafety.sha256(Files.readAllBytes(fixture.resolve(entry.getKey()))), entry.getKey());
        }
    }

    private static Path copyDatabase(Path fixture, Path scope) throws Exception {
        Path database = Files.createDirectories(scope.resolve("runtime")).resolve("resource-mutations.db");
        Files.copy(fixture.resolve("resource-mutations.db"), database, StandardCopyOption.COPY_ATTRIBUTES);
        return database;
    }

    private static int deletedRebindItems(Path database) throws Exception {
        return count(database, """
            SELECT COUNT(*) FROM core_catalog_rebind_item i
            JOIN resource_mutation_state s ON s.resource = i.resource
            WHERE i.status = 'APPLIED' AND s.deleted = 1
            """);
    }

    private static int liveCoreHeads(Path database) throws Exception {
        return count(database,
            "SELECT COUNT(*) FROM resource_mutation_state WHERE deleted = 0 AND core_payload_kind IS NOT NULL");
    }

    private static int actorCount(Path database, String actor) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement(
                 "SELECT COUNT(*) FROM resource_mutation_receipt WHERE actor_id = ?")) {
            statement.setString(1, actor);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private static int count(Path database, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static DatabaseSnapshot snapshot(Path database) throws Exception {
        return new DatabaseSnapshot(rows(database, "resource_mutation_state", "resource"),
            rows(database, "resource_mutation_receipt", "mutation_id"),
            rows(database, "core_catalog_evolution_receipt", "mutation_id"),
            rows(database, "core_catalog_rebind_run", "migration_id"),
            rows(database, "core_catalog_rebind_item", "resource"),
            rows(database, "resource_create_aggregate_receipt", "mutation_id"),
            rows(database, "resource_mutation_authority_meta", "key"));
    }

    private static Map<String, List<String>> rows(Path database, String table, String keyColumn) throws Exception {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement("SELECT * FROM " + table + " ORDER BY " + keyColumn);
             ResultSet result = statement.executeQuery()) {
            ResultSetMetaData metadata = result.getMetaData();
            while (result.next()) {
                List<String> values = new ArrayList<>(metadata.getColumnCount());
                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                    Object value = result.getObject(column);
                    values.add(value instanceof byte[] bytes ? Base64.getEncoder().encodeToString(bytes)
                        : value == null ? null : value.getClass().getName() + ':' + value);
                }
                assertEquals(null, rows.put(result.getString(keyColumn), new ArrayList<>(values)));
            }
        }
        return Map.copyOf(rows);
    }

    private record DatabaseSnapshot(
        Map<String, List<String>> states,
        Map<String, List<String>> receipts,
        Map<String, List<String>> evolutions,
        Map<String, List<String>> rebindRuns,
        Map<String, List<String>> rebindItems,
        Map<String, List<String>> aggregateCreates,
        Map<String, List<String>> authorityMetadata
    ) {
    }

    private static class HistoricalAuthority implements CoreGraphResourceAuthority {
        protected final Map<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> current;

        private HistoricalAuthority(Map<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> current) {
            this.current = new LinkedHashMap<>(current);
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
        public List<CoreGraphResourceState> list(String type) {
            return current.values().stream().filter(value -> type.equals(value.envelope().resourceType()))
                .map(CoreGraphResourceState::live).sorted(Comparator.comparing(CoreGraphResourceState::resource))
                .toList();
        }

        @Override
        public Optional<CoreGraphResourceState> state(ServerResourceLocator resource) {
            return load(resource).map(CoreGraphResourceState::live);
        }

        @Override
        public boolean acceptsCatalogRebind(CoreCatalogBindingMigration migration) {
            return true;
        }

        @Override
        public CoreGraphStorageBoundary.Decoded save(ServerResourceLocator resource, byte[] canonicalEnvelope,
                                                      UUID mutationId, long expectedRevision,
                                                      ContentHash payloadChecksum) {
            throw new AssertionError("Historical proof verification must not save a Core resource");
        }

        @Override
        public CoreGraphStorageBoundary.CoreGraphTombstone delete(ServerResourceLocator resource, UUID mutationId,
                                                                   long expectedRevision, ContentHash payloadChecksum) {
            throw new AssertionError("Historical proof verification must not delete a Core resource");
        }

        @Override
        public CoreGraphStorageBoundary.Decoded activate(ServerResourceLocator resource,
                                                          ResourceActivationState activationState, UUID mutationId,
                                                          long expectedRevision, ContentHash payloadChecksum) {
            throw new AssertionError("Historical proof verification must not activate a Core resource");
        }
    }

    private static final class FixtureAuthority extends HistoricalAuthority {
        private final CoreGraphMutationValidator validator;
        private final ServerResourceLocator denied;
        private int saves;

        private FixtureAuthority(Map<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> current,
                                 CoreGraphMutationValidator validator, ServerResourceLocator denied) {
            super(current);
            this.validator = validator;
            this.denied = denied;
        }

        private Map<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> current() {
            return Map.copyOf(current);
        }

        private int saves() {
            return saves;
        }

        @Override
        public Optional<CatalogBinding> activeCatalogBinding() {
            return Optional.of(validator.activeBinding());
        }

        @Override
        public Optional<CoreCatalogEvolution.Proof> activeCatalogEvolution() {
            return validator.activeEvolution();
        }

        @Override
        public boolean acceptsCatalogRebind(CoreCatalogBindingMigration migration) {
            return false;
        }

        @Override
        public void validateSave(ServerResourceLocator resource, CoreGraphStorageBoundary.Decoded canonicalEnvelope) {
            validator.requireValid(resource, canonicalEnvelope);
            if (resource.equals(denied)) {
                throw new IllegalArgumentException("Injected validator denial");
            }
        }

        @Override
        public CoreGraphStorageBoundary.Decoded save(ServerResourceLocator resource, byte[] canonicalEnvelope,
                                                      UUID mutationId, long expectedRevision,
                                                      ContentHash payloadChecksum) {
            CoreGraphStorageBoundary.Decoded candidate = BOUNDARY.decode(canonicalEnvelope, resource);
            var proof = validator.admit(resource, candidate);
            return validator.executeCurrent(proof, resource, candidate, () -> {
                CoreGraphStorageBoundary.Decoded source = current.get(resource);
                assertNotNull(source);
                assertEquals(expectedRevision, source.envelope().assetRevision());
                assertEquals(expectedRevision + 1L, candidate.envelope().assetRevision());
                assertEquals(mutationId.toString(), candidate.envelope().assetMutationId());
                assertEquals(payloadChecksum, candidate.envelope().assetHash());
                current.put(resource, candidate);
                saves++;
                return candidate;
            });
        }
    }
}
