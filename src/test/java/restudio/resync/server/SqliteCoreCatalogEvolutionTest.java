package restudio.resync.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.customcontent.ItemAttributeSchemaService;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.ConnectionState;
import restudio.resync.core.Session;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.command.CommandGraphContract;
import restudio.resync.flow.diagnostics.DiagnosticReportPersistenceParticipant;
import restudio.resync.flow.diagnostics.StructuredFlowDiagnosticReporter;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ResourceActivateRequest;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourceCreateRequest;
import restudio.resync.flow.protocol.ResourceOperation;
import restudio.resync.flow.protocol.ResourcePresentationIntent;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceRebindStatus;
import restudio.resync.migration.RebindablePersistenceParticipant;
import restudio.resync.modules.flow.CoreResourceMutationTransition;
import restudio.resync.modules.flow.FlowCollaborationService;
import restudio.resync.modules.flow.FlowPacketSender;
import restudio.resync.modules.flow.FlowResourcePacketRouter;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.protocol.FrameSender;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.security.ClientIdentity;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.AssetsPersistenceParticipant;
import restudio.resync.storage.ProjectMetadataLineage;
import restudio.resync.storage.StorageSafety;
import restudio.resync.upgrade.AssetCoordinatorMigration;
import restudio.resync.worldgen.WorldGenProjectStorage;

import java.nio.charset.StandardCharsets;
import java.lang.reflect.Constructor;
import java.io.IOException;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SqliteCoreCatalogEvolutionTest {

    @Test
    void settlesSavedGeneration56CommandLineageWithoutChangingGraphData(@TempDir Path root) throws Exception {
        String configured = System.getenv("RESYNC_SAVED_ACCEPTANCE_ROOT");
        assumeTrue(configured != null && !configured.isBlank(), "An explicit saved acceptance fixture is required");
        Path saved = Path.of(configured);
        assertTrue(saved.isAbsolute());
        Map<String, String> hashes = Map.of(
            "asdgasd.json", "b24f6c02b8e45da0ee9a3e6453bf01c817285f600b516b621c23d4845c09895b",
            "ww.json", "8c10d03da0db99712a75e7eded92d552596ffac226e9a66d4cb7d4f7d892844c",
            "asd.json", "1927c88b34daa49d0212c8b4678c928c250aad346c18b060f2d0a4bb1321b1bb",
            "blockBreak.json", "96595bf36dbc03b1284cc62b60e7524153d7e28d3877951881c5315af56e23de",
            "resource-mutations.db", "4267fd267e18cadaf7c08dd77d2377cb221d2a879be58cde4b2f458215d17f1c");
        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            assertEquals(entry.getValue(), StorageSafety.sha256(Files.readAllBytes(saved.resolve(entry.getKey()))), entry.getKey());
        }
        Path database = Files.createDirectories(root.resolve("runtime")).resolve("resource-mutations.db");
        Files.copy(saved.resolve("resource-mutations.db"), database);
        Map<String, List<Object>> originalReceipts = receiptRows(database);
        assertEquals(301, originalReceipts.size());
        CoreCatalogEvolution.Proof proof = CoreCatalogEvolutionTest.structureProof();
        TestCoreAuthority core = new TestCoreAuthority(proof);
        ServerId server = ServerId.of(UUID.fromString("e65887a4-ea27-4c55-bae2-e1c8d92da433"));
        Map<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> originals = new LinkedHashMap<>();
        for (String name : List.of("asdgasd", "ww", "asd", "blockBreak")) {
            String type = "asdgasd".equals(name) || "ww".equals(name) ? "command" : "flow";
            ServerResourceLocator resource = new ServerResourceLocator(server,
                ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(type)), name);
            CoreGraphStorageBoundary.Decoded source = CoreCatalogEvolutionTest.BOUNDARY.decodeText(
                Files.readString(saved.resolve(name + ".json")), resource);
            assertEquals(CoreCatalogEvolutionTest.TARGET, CoreCatalogCompatibilityRebind.graph(source).catalogBinding());
            if ("asdgasd".equals(name)) {
                assertEquals(8L, source.envelope().assetRevision());
            }
            originals.put(resource, source);
            core.current.put(resource, source);
        }
        core.activate();
        Fixture fixture = new Fixture(database, core, server);
        Map<ServerResourceLocator, ContentHash> admittedHashes = new LinkedHashMap<>();
        for (int attempt = 0; attempt < 3; attempt++) {
            try (SqliteProtocolResourceMutationAuthority authority = fixture.open(SqliteProtocolResourceMutationAuthority.CatalogStartup.DEFERRED)) {
                authority.settleCatalogBinding(proof.target());
                assertTrue(authority.durable());
                assertTrue(authority.authoritativeCoreReads());
                for (Map.Entry<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> entry : originals.entrySet()) {
                    CoreGraphStorageBoundary.Decoded current = core.current.get(entry.getKey());
                    GraphDocument before = CoreCatalogCompatibilityRebind.graph(entry.getValue());
                    GraphDocument after = CoreCatalogCompatibilityRebind.graph(current);
                    JsonObject original = JsonParser.parseString(before.canonicalJson()).getAsJsonObject();
                    JsonObject normalized = JsonParser.parseString(after.canonicalJson()).getAsJsonObject();
                    normalized.add("catalogBinding", original.get("catalogBinding"));
                    normalized.add("revision", original.get("revision"));
                    assertEquals(original, normalized, entry.getKey().canonicalText());
                    assertEquals(before.revision() + 1L, authority.load(entry.getKey()).revision());
                    assertEquals(proof.target(), after.catalogBinding());
                    assertEquals(entry.getValue().envelope().assetActivationState(), current.envelope().assetActivationState());
                    if (attempt == 0) {
                        admittedHashes.put(entry.getKey(), current.envelope().assetHash());
                    } else {
                        assertEquals(admittedHashes.get(entry.getKey()), current.envelope().assetHash());
                    }
                }
            }
            assertEquals(4, core.saves);
            assertEquals(0, core.repairs);
            Map<String, List<Object>> currentReceipts = receiptRows(database);
            assertEquals(originalReceipts.size() + 4, currentReceipts.size());
            originalReceipts.forEach((id, row) -> assertEquals(row, currentReceipts.get(id), id));
            assertEquals(6L, count(database, "core_catalog_evolution_receipt", null));
            assertEquals(0L, count(database, "resource_mutation_receipt", "status = 'PENDING'"));
        }
        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            assertEquals(entry.getValue(), StorageSafety.sha256(Files.readAllBytes(saved.resolve(entry.getKey()))), entry.getKey());
        }
    }

    private static Map<String, List<Object>> receiptRows(Path database) throws Exception {
        Map<String, List<Object>> rows = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM resource_mutation_receipt ORDER BY mutation_id");
             var result = statement.executeQuery()) {
            int columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                List<Object> values = new ArrayList<>();
                for (int column = 1; column <= columns; column++) {
                    values.add(result.getObject(column));
                }
                rows.put(result.getString("mutation_id"), values);
            }
        }
        return rows;
    }

    @Test
    void recoversStructureEvolutionUsingItsOwnRegistrationAfterAssetWrite(@TempDir Path root) throws Exception {
        CoreCatalogEvolution.Proof structure = CoreCatalogEvolutionTest.structureProof();
        Fixture fixture = fixture(root, List.of("flow"), true);
        fixture.core().activate();
        try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
            assertTrue(authority.durable());
        }
        fixture.core().proof = structure;
        fixture.core().activate();
        fixture.core().failureStage = 2;
        assertThrows(IllegalStateException.class, fixture::open);
        assertEquals(8L, fixture.core().current.get(fixture.resource()).envelope().assetRevision());
        assertEquals(1L, count(fixture.database(), "resource_mutation_receipt", "status = 'PENDING'"));
        fixture.core().failureStage = 0;
        try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
            authority.settleCatalogBinding(structure.target());
            assertTrue(authority.durable());
            assertEquals(8L, authority.load(fixture.resource()).revision());
        }
        assertEquals(2, fixture.core().saves);
        assertEquals(0, fixture.core().repairs);
        assertEquals(0L, count(fixture.database(), "resource_mutation_receipt", "status = 'PENDING'"));
        assertEquals(2L, count(fixture.database(), "core_catalog_evolution_receipt", null));
    }

    @Test
    void preservesHistoricalReceiptsAcrossStructureEvolutionAndRepeatedRestart(@TempDir Path root) throws Exception {
        CoreCatalogEvolution.Proof structure = CoreCatalogEvolutionTest.structureProof();
        Fixture fixture = fixture(root, List.of("flow", "function", "command"), true);
        fixture.core().activate();
        try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
            authority.settleCatalogBinding(CoreCatalogEvolutionTest.TARGET);
            assertTrue(authority.durable());
        }
        assertEquals(3L, count(fixture.database(), "core_catalog_evolution_receipt", null));
        fixture.core().proof = structure;
        fixture.core().activate();
        for (int attempt = 0; attempt < 3; attempt++) {
            try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
                authority.settleCatalogBinding(structure.target());
                assertTrue(authority.durable());
                for (ServerResourceLocator resource : fixture.core().current.keySet()) {
                    assertEquals(8L, authority.load(resource).revision());
                    assertEquals(structure.target(), CoreCatalogCompatibilityRebind.graph(fixture.core().current.get(resource)).catalogBinding());
                }
            }
            assertEquals(6, fixture.core().saves);
            assertEquals(6L, count(fixture.database(), "core_catalog_evolution_receipt", null));
            assertEquals(3L, count(fixture.database(), "resource_mutation_receipt",
                "actor_id = 'core-catalog-evolution-v1' AND status = 'APPLIED' AND transition_published = 1"));
            assertEquals(3L, count(fixture.database(), "resource_mutation_receipt",
                "actor_id = 'core-catalog-evolution-v2' AND status = 'APPLIED' AND transition_published = 1"));
        }
    }

    @Test
    void settlesResourceDeclarationsAfterRestrictedRebindWithoutRewritingHistoricalReceipts(@TempDir Path root)
        throws Exception {
        CoreCatalogEvolution.Proof rebind = CoreCatalogEvolutionTest.rebindProof();
        CoreCatalogEvolution.Proof declarations = CoreCatalogEvolutionTest.resourceDeclarationProof();
        TestCoreAuthority core = new TestCoreAuthority(rebind);
        core.active = CoreCatalogEvolutionTest.REBIND_SOURCE;
        Map<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> originals = new LinkedHashMap<>();
        for (String type : List.of("flow", "command", "function")) {
            CoreGraphStorageBoundary.Decoded source = CoreCatalogEvolutionTest.source(type,
                "resource-declaration-" + type, false, CoreCatalogEvolutionTest.REBIND_SOURCE);
            ServerResourceLocator resource = CoreCatalogCompatibilityRebind.graph(source).resource();
            originals.put(resource, source);
            core.current.put(resource, source);
        }
        Fixture fixture = new Fixture(root.resolve("runtime/resource-mutations.db"), core);
        try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
            for (ServerResourceLocator resource : originals.keySet()) {
                assertEquals(6L, authority.load(resource).revision());
            }
        }
        seedSourceReceipts(fixture);
        Map<String, List<Object>> syntheticReceipts = receiptRows(fixture.database());

        core.activate();
        try (SqliteProtocolResourceMutationAuthority authority = fixture.open(
            SqliteProtocolResourceMutationAuthority.CatalogStartup.DEFERRED)) {
            authority.settleCatalogBinding(CoreCatalogEvolutionTest.REBIND_TARGET);
            assertTrue(authority.durable());
        }
        Map<String, List<Object>> rebindReceipts = receiptRows(fixture.database());
        syntheticReceipts.forEach((id, row) -> assertEquals(row, rebindReceipts.get(id), id));
        assertEquals(3L, count(fixture.database(), "resource_mutation_receipt",
            "actor_id = 'core-catalog-evolution-v3' AND status = 'APPLIED' AND transition_published = 1"));

        core.proof = declarations;
        core.activate();
        for (int attempt = 0; attempt < 3; attempt++) {
            try (SqliteProtocolResourceMutationAuthority authority = fixture.open(
                SqliteProtocolResourceMutationAuthority.CatalogStartup.DEFERRED)) {
                authority.settleCatalogBinding(CoreCatalogEvolutionTest.RESOURCE_DECLARATION_TARGET);
                assertTrue(authority.durable());
                for (Map.Entry<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> entry : originals.entrySet()) {
                    CoreGraphStorageBoundary.Decoded current = core.current.get(entry.getKey());
                    GraphDocument before = CoreCatalogCompatibilityRebind.graph(entry.getValue());
                    GraphDocument after = CoreCatalogCompatibilityRebind.graph(current);
                    JsonObject original = JsonParser.parseString(before.canonicalJson()).getAsJsonObject();
                    JsonObject normalized = JsonParser.parseString(after.canonicalJson()).getAsJsonObject();
                    normalized.add("catalogBinding", original.get("catalogBinding"));
                    normalized.add("revision", original.get("revision"));
                    assertEquals(original, normalized, entry.getKey().canonicalText());
                    assertEquals(8L, authority.load(entry.getKey()).revision());
                    assertEquals(CoreCatalogEvolutionTest.RESOURCE_DECLARATION_TARGET, after.catalogBinding());
                    assertEquals(entry.getValue().envelope().assetActivationState(), current.envelope().assetActivationState());
                }
            }
            assertEquals(6, core.saves);
            Map<String, List<Object>> currentReceipts = receiptRows(fixture.database());
            rebindReceipts.forEach((id, row) -> assertEquals(row, currentReceipts.get(id), id));
            assertEquals(6L, count(fixture.database(), "core_catalog_evolution_receipt", null));
            assertEquals(3L, count(fixture.database(), "resource_mutation_receipt",
                "actor_id = 'core-catalog-evolution-v3' AND status = 'APPLIED' AND transition_published = 1"));
            assertEquals(3L, count(fixture.database(), "resource_mutation_receipt",
                "actor_id = 'core-catalog-evolution-v4' AND status = 'APPLIED' AND transition_published = 1"));
            assertEquals(0L, count(fixture.database(), "resource_mutation_receipt", "status = 'PENDING'"));
        }
    }

    @Test
    void replaysAppliedAggregatePublicationAfterRepeatedFailureWithoutRecreatingOrRewinding(@TempDir Path root)
        throws Exception {
        for (boolean superseded : List.of(false, true)) {
            try (StorageFixture fixture = new StorageFixture(root.resolve(superseded ? "superseded" : "current"))) {
                fixture.core.failAggregatePublication = true;
                fixture.createSources(1, superseded);
                ServerResourceLocator resource = fixture.core.current.keySet().iterator().next();
                long sourceRevision = superseded ? 2L : 1L;
                assertEquals(sourceRevision, fixture.core.load(resource).orElseThrow().envelope().assetRevision());
                assertEquals(1, fixture.core.creates);
                assertEquals(0L, count(fixture.database, "resource_mutation_receipt", "status = 'PENDING'"));
                assertEquals(1L, count(fixture.database, "resource_mutation_receipt",
                    "operation = 'CREATE' AND status = 'APPLIED' AND mutation_id IN (SELECT mutation_id FROM resource_create_aggregate_receipt WHERE published = 0)"));
                long sequence = fixture.coordinator.read(value -> value.rootSequence());
                String metadata = fixture.storage.getProjectMetadata(CoreCatalogEvolutionTest.SERVER.canonicalText());
                fixture.core.activate();
                for (int attempt = 0; attempt < 2; attempt++) {
                    try (SqliteProtocolResourceMutationAuthority authority = fixture.open(SqliteProtocolResourceMutationAuthority.CatalogStartup.DEFERRED)) {
                        assertFalse(authority.durable());
                        assertFalse(authority.authoritativeCoreReads());
                        assertThrows(IllegalStateException.class, () -> authority.settleCatalogBinding(CoreCatalogEvolutionTest.TARGET));
                    }
                    assertEquals(sequence, fixture.coordinator.read(value -> value.rootSequence()).longValue());
                    assertEquals(metadata, fixture.storage.getProjectMetadata(CoreCatalogEvolutionTest.SERVER.canonicalText()));
                    assertEquals(1, fixture.core.creates);
                    assertEquals(0, fixture.core.saves);
                }
                fixture.core.failAggregatePublication = false;
                try (SqliteProtocolResourceMutationAuthority authority = fixture.open(SqliteProtocolResourceMutationAuthority.CatalogStartup.DEFERRED)) {
                    assertEquals(sourceRevision, fixture.core.aggregatePublishedRevisions.getLast());
                    assertEquals(sequence, fixture.coordinator.read(value -> value.rootSequence()).longValue());
                    assertEquals(metadata, fixture.storage.getProjectMetadata(CoreCatalogEvolutionTest.SERVER.canonicalText()));
                    authority.settleCatalogBinding(CoreCatalogEvolutionTest.TARGET);
                    assertTrue(authority.durable());
                    assertEquals(sourceRevision + 1L, authority.load(resource).revision());
                }
                int publications = fixture.core.aggregatePublications;
                try (SqliteProtocolResourceMutationAuthority authority = fixture.open(SqliteProtocolResourceMutationAuthority.CatalogStartup.DEFERRED)) {
                    authority.settleCatalogBinding(CoreCatalogEvolutionTest.TARGET);
                    assertTrue(authority.durable());
                }
                assertEquals(publications, fixture.core.aggregatePublications);
                assertEquals(1, fixture.core.creates);
                assertEquals(1, fixture.core.saves);
                assertEquals(0, fixture.core.repairs);
            }
        }
    }

    @Test
    void deferredStartupSealsPersistenceAndSettlesEighteenMixedPublishedSourcesExactlyOnce(@TempDir Path root)
        throws Exception {
        try (StorageFixture initial = new StorageFixture(root)) {
            try (SqliteProtocolResourceMutationAuthority authority = initial.open(SqliteProtocolResourceMutationAuthority.CatalogStartup.IMMEDIATE)) {
                ReSyncPersistenceTopology.Registration registration = initial.seal(authority);
                initial.persistence.completeStartupActivation(registration.readiness());
                initial.createSources(authority, 18, false);
                assertEquals(12, initial.transitionEvents.size());
                initial.persistence.shutdown();
            }
        }
        try (StorageFixture fixture = new StorageFixture(root)) {
            assertEquals(6L, count(fixture.database, "resource_mutation_receipt",
                "operation = 'CREATE' AND mutation_id IN (SELECT mutation_id FROM resource_mutation_state WHERE core_payload_kind IS NOT NULL)"));
            assertEquals(12L, count(fixture.database, "resource_mutation_receipt",
                "operation = 'ACTIVATE' AND transition_published = 1 AND result_payload IS NULL"));
            assertEquals(0, fixture.transitionEvents.size());
            fixture.core.activate();
            try (SqliteProtocolResourceMutationAuthority authority = fixture.open(SqliteProtocolResourceMutationAuthority.CatalogStartup.DEFERRED)) {
                assertFalse(authority.durable());
                assertFalse(authority.authoritativeCoreReads());
                assertEquals(0, fixture.core.saves);
                assertThrows(IllegalStateException.class, () -> authority.load(CoreCatalogEvolutionTest.resource("flow", "mixed-0")));
                assertFalse(mutate(authority, metadataCreate()).handled());
                SqliteProtocolResourceMutationPersistenceParticipant participant =
                    new SqliteProtocolResourceMutationPersistenceParticipant(fixture.activeRoot, authority);
                ReSyncPersistenceCoordinator persistence = fixture.persistence;
                List<ReSyncPersistenceTopology.Binding> bindings = fixture.persistenceBindings(participant, true);
                fixture.assertOwnedFiles();
                ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(persistence, fixture.activeRoot,
                    bindings);
                assertTrue(registration.sealed(), fixture.persistenceStatus(authority, registration));
                fixture.configuration.activateAndFlush();
                assertEquals(authority.databasePath(), fixture.database);
                assertEquals(fixture.activeRoot.resolve(ConfigurationPersistenceParticipant.FILE_NAME), fixture.configuration.root());
                assertEquals(fixture.activeRoot.resolve("diagnostics"), fixture.diagnostics.root());
                fixture.assertOwnedFiles();
                assertTrue(persistence.currentValidatedReadinessProof(registration.readiness()).isPresent());
                participant.healthCheck();
                authority.settleCatalogBinding(CoreCatalogEvolutionTest.TARGET);
                assertTrue(authority.authoritativeCoreReads());
                assertTrue(authority.durable());
                assertEquals(18, fixture.core.saves);
                assertEquals(18, fixture.transitionEvents.size());
                for (CoreGraphStorageBoundary.Decoded value : fixture.core.current.values()) {
                    assertEquals(CoreCatalogEvolutionTest.TARGET, CoreCatalogCompatibilityRebind.graph(value).catalogBinding());
                }
                authority.settleCatalogBinding(CoreCatalogEvolutionTest.TARGET);
                assertEquals(18, fixture.core.saves);
                authority.closeMutationAdmission();
                assertThrows(IllegalStateException.class, () -> authority.settleCatalogBinding(CoreCatalogEvolutionTest.TARGET));
                assertFalse(authority.durable());
            }
            try (SqliteProtocolResourceMutationAuthority authority = fixture.open(SqliteProtocolResourceMutationAuthority.CatalogStartup.DEFERRED)) {
                authority.settleCatalogBinding(CoreCatalogEvolutionTest.TARGET);
                assertTrue(authority.durable());
            }
            assertEquals(18, fixture.core.saves);
            assertEquals(18L, count(fixture.database, "core_catalog_evolution_receipt", null));
            assertEquals(6L, count(fixture.database, "resource_mutation_receipt",
                "operation = 'CREATE' AND mutation_id IN (SELECT mutation_id FROM resource_create_aggregate_receipt WHERE published = 1) AND transition_published = 0 AND transition_hash IS NULL AND transition_envelope IS NULL AND result_payload IS NOT NULL AND mutation_id IN (SELECT source_mutation_id FROM (SELECT json_extract(source_envelope, '$.assetMutationId') AS source_mutation_id FROM core_catalog_evolution_receipt))"));
        }
    }

    @Test
    void constructorOnlyServicesSealAndRestartWithoutUserResources(@TempDir Path root) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            try (StorageFixture fixture = new StorageFixture(root)) {
                fixture.core.activate();
                try (SqliteProtocolResourceMutationAuthority authority = fixture.open(SqliteProtocolResourceMutationAuthority.CatalogStartup.DEFERRED)) {
                    ReSyncPersistenceTopology.Registration registration = fixture.seal(authority);
                    assertFalse(authority.durable());
                    assertTrue(fixture.persistence.currentValidatedReadinessProof(registration.readiness()).isPresent());
                    authority.settleCatalogBinding(CoreCatalogEvolutionTest.TARGET);
                    fixture.persistence.completeStartupActivation(registration.readiness());
                    assertTrue(authority.durable());
                    assertEquals(0L, count(fixture.database, "resource_mutation_receipt", null));
                    assertEquals(0, fixture.core.saves);
                    fixture.persistence.shutdown();
                }
            }
        }
    }

    @Test
    void missingDiagnosticOwnerRollsEveryParticipantBackToPreparedActiveRoot(@TempDir Path root) throws Exception {
        try (StorageFixture fixture = new StorageFixture(root)) {
            fixture.createSources(1);
            fixture.core.activate();
            try (SqliteProtocolResourceMutationAuthority authority = fixture.open(SqliteProtocolResourceMutationAuthority.CatalogStartup.DEFERRED)) {
                Path preparedRoot = fixture.activeRoot;
                Path preparedDatabase = fixture.database;
                assertFalse(preparedRoot.equals(fixture.persistence.dataRoot()));
                SqliteProtocolResourceMutationPersistenceParticipant participant =
                    new SqliteProtocolResourceMutationPersistenceParticipant(preparedRoot, authority);
                List<ReSyncPersistenceTopology.Binding> bindings = fixture.persistenceBindings(participant, false);
                Path diagnosticLock = preparedRoot.resolve("diagnostics/.locks/diagnostic-report-store.lock");
                assertTrue(Files.isRegularFile(diagnosticLock));
                assertThrows(IOException.class, () -> fixture.persistence.participants().ownerFor(preparedRoot, diagnosticLock));
                ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
                    fixture.persistence, preparedRoot, bindings);
                String status = fixture.persistenceStatus(authority, registration);
                assertFalse(registration.sealed(), status);
                assertTrue(registration.unavailableReasons().values().stream()
                    .anyMatch(reason -> reason.contains("No Persistence Participant Owns File: diagnostics/")), status);
                assertEquals(PersistenceRebindStatus.State.ROLLED_BACK,
                    fixture.persistence.participants().rebindStatus().state(), status);
                assertEquals(Optional.of(preparedRoot), fixture.persistence.participants().rebindStatus().activeRoot(), status);
                assertEquals(Map.of(), fixture.persistence.participants().rebindStatus().rollbackFailures(), status);
                assertEquals(preparedDatabase, authority.databasePath(), status);
                assertEquals(preparedRoot, fixture.activeRoot, status);
                assertFalse(authority.durable());
                assertFalse(authority.authoritativeCoreReads());
                assertEquals(0, fixture.core.saves);
            }
        }
    }

    @Test
    void aggregateSourceRequiresPublishedCanonicalPrimaryMetadataAndOriginalFingerprint(@TempDir Path root) throws Exception {
        List<String> corruptions = List.of(
            "UPDATE resource_create_aggregate_receipt SET metadata_payload = '{}'",
            "UPDATE resource_create_aggregate_receipt SET metadata_mutation_id = '00000000-0000-4000-8000-000000000001'",
            "UPDATE resource_create_aggregate_receipt SET display_name = 'Altered'",
            "UPDATE resource_mutation_receipt SET result_payload = '{}' WHERE operation = 'CREATE'",
            "UPDATE resource_mutation_receipt SET fingerprint = 'altered' WHERE operation = 'CREATE'");
        for (int index = 0; index < corruptions.size(); index++) {
            try (StorageFixture fixture = new StorageFixture(root.resolve("case-" + index))) {
                fixture.createSources(1);
                execute(fixture.database, corruptions.get(index));
                fixture.core.activate();
                try (SqliteProtocolResourceMutationAuthority authority = fixture.open(SqliteProtocolResourceMutationAuthority.CatalogStartup.DEFERRED)) {
                    assertThrows(RuntimeException.class, () -> authority.settleCatalogBinding(CoreCatalogEvolutionTest.TARGET));
                    assertFalse(authority.durable());
                    assertFalse(authority.authoritativeCoreReads());
                }
                assertEquals(0, fixture.core.saves);
                assertEquals(0, fixture.core.repairs);
                assertEquals(0L, count(fixture.database, "core_catalog_evolution_receipt", null));
            }
        }
    }

    @Test
    void evolvesEveryGraphFamilyOnceWithOneRevisionAndDurableProof(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, List.of("flow", "function", "command"), true);
        fixture.core().activate();
        try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
            for (ServerResourceLocator resource : fixture.core().current.keySet()) {
                assertEquals(7L, authority.load(resource).revision());
                assertEquals(ResourceActivationState.INACTIVE, authority.load(resource).activationState());
                assertEquals(CoreCatalogEvolutionTest.TARGET,
                    CoreCatalogCompatibilityRebind.graph(fixture.core().current.get(resource)).catalogBinding());
            }
        }
        assertEquals(3, fixture.core().saves);
        assertEquals(3L, count(fixture.database(), "core_catalog_evolution_receipt", null));
        assertEquals(3L, count(fixture.database(), "resource_mutation_receipt", "actor_id = 'core-catalog-evolution-v1' AND status = 'APPLIED' AND transition_published = 1 AND transition_envelope IS NULL"));
        try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
            assertTrue(authority.durable());
        }
        assertEquals(3, fixture.core().saves);
    }

    @Test
    void proofInsertFailureRollsBackThePendingReceiptBeforeAssetWrite(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, List.of("flow"), true);
        execute(fixture.database(), "CREATE TRIGGER reject_evolution_proof BEFORE INSERT ON core_catalog_evolution_receipt "
            + "BEGIN SELECT RAISE(ABORT, 'Injected proof insert failure'); END");
        fixture.core().activate();
        assertThrows(IllegalStateException.class, fixture::open);
        assertEquals(0L, count(fixture.database(), "resource_mutation_receipt", "status = 'PENDING'"));
        assertEquals(0L, count(fixture.database(), "core_catalog_evolution_receipt", null));
        assertEquals(0, fixture.core().saves);
        assertEquals(6L, fixture.core().current.get(fixture.resource()).envelope().assetRevision());
    }

    @Test
    void recoversPendingBeforeAssetWriteUsingTheSameProofAndMutation(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, List.of("command"), true);
        fixture.core().activate();
        fixture.core().failureStage = 1;
        assertThrows(IllegalStateException.class, fixture::open);
        assertEquals(1L, count(fixture.database(), "core_catalog_evolution_receipt", null));
        assertEquals(1L, count(fixture.database(), "resource_mutation_receipt", "status = 'PENDING'"));
        assertEquals(6L, fixture.core().current.values().iterator().next().envelope().assetRevision());
        fixture.core().failureStage = 0;
        try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
            assertTrue(authority.durable());
            assertEquals(7L, authority.load(fixture.resource()).revision());
        }
        assertEquals(1L, count(fixture.database(), "core_catalog_evolution_receipt", null));
        assertEquals(0L, count(fixture.database(), "resource_mutation_receipt", "status = 'PENDING'"));
    }

    @Test
    void acceptsAnAlreadyAppliedAssetOnlyAfterAuthenticatingThePendingProof(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, List.of("flow"), true);
        fixture.core().activate();
        fixture.core().failureStage = 2;
        assertThrows(IllegalStateException.class, fixture::open);
        assertEquals(7L, fixture.core().current.get(fixture.resource()).envelope().assetRevision());
        fixture.core().failureStage = 0;
        try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
            assertTrue(authority.durable());
            assertEquals(7L, authority.load(fixture.resource()).revision());
        }
        assertEquals(1, fixture.core().saves);
    }

    @Test
    void missingProofBlocksPendingEvenWhenTheAssetAlreadyMatches(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, List.of("flow"), true);
        fixture.core().activate();
        fixture.core().failureStage = 2;
        assertThrows(IllegalStateException.class, fixture::open);
        execute(fixture.database(), "DELETE FROM core_catalog_evolution_receipt");
        fixture.core().failureStage = 0;
        ContentHash appliedAssetHash = fixture.core().current.get(fixture.resource()).envelope().assetHash();
        try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
            assertFalse(authority.durable());
        }
        assertEquals(1, fixture.core().saves);
        assertEquals(0, fixture.core().repairs);
        assertEquals(appliedAssetHash, fixture.core().current.get(fixture.resource()).envelope().assetHash());
        assertEquals(1L, count(fixture.database(), "resource_mutation_receipt", "status = 'PENDING'"));
        assertEquals(0L, count(fixture.database(), "resource_mutation_receipt", "actor_id = 'core-revision-repair'"));
    }

    @Test
    void divergentSourceBlocksRecoveryWithoutRevisionRepairOrOverwrite(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, List.of("flow"), true);
        fixture.core().activate();
        fixture.core().failureStage = 1;
        assertThrows(IllegalStateException.class, fixture::open);
        CoreGraphStorageBoundary.Decoded source = fixture.core().current.get(fixture.resource());
        GraphDocument graph = source.graphDocument();
        GraphDocument divergent = new GraphDocument(graph.schemaVersion(), graph.resource(), graph.revision(), graph.catalogBinding(),
            graph.requiredCapabilities(), graph.nodes(), graph.connections(), graph.variables(), graph.functions(),
            OpaqueData.of(Map.of("external-edit", true)));
        CoreGraphStorageBoundary.Decoded changed = CoreCatalogEvolutionTest.BOUNDARY.decode(
            CoreCatalogEvolutionTest.BOUNDARY.encode(divergent, new CoreGraphStorageBoundary.AssetMetadata("flow", graph.revision(),
                UUID.fromString(source.envelope().assetMutationId()), source.envelope().assetActivationState()), graph.resource()), graph.resource());
        fixture.core().current.put(fixture.resource(), changed);
        fixture.core().failureStage = 0;
        try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
            assertFalse(authority.durable());
        }
        assertEquals(changed.envelope().assetHash(), fixture.core().current.get(fixture.resource()).envelope().assetHash());
        assertEquals(1, fixture.core().saves);
        assertEquals(0, fixture.core().repairs);
        assertEquals(1L, count(fixture.database(), "resource_mutation_receipt", "status = 'PENDING'"));
    }

    @Test
    void publicationFailureStopsBeforeTheNextPendingResource(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, List.of("flow", "function"), true);
        fixture.core().activate();
        try (SqliteProtocolResourceMutationAuthority ignored = fixture.open()) {
        }
        rewindEvolutionsAsPending(fixture);
        fixture.core().saves = 0;
        fixture.core().failPublication = true;
        try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
            assertFalse(authority.durable());
        }
        assertEquals(1, fixture.core().saves);
        assertEquals(0, fixture.core().repairs);
        assertEquals(1L, count(fixture.database(), "resource_mutation_receipt", "status = 'PENDING'"));
        assertEquals(1L, count(fixture.database(), "resource_mutation_receipt", "actor_id = 'core-catalog-evolution-v1' AND status = 'APPLIED' AND transition_published = 0"));
        assertEquals(List.of(6L, 7L), fixture.core().current.values().stream()
            .map(value -> value.envelope().assetRevision()).sorted().toList());
        fixture.core().failPublication = false;
        try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
            assertTrue(authority.durable());
        }
        assertEquals(2, fixture.core().saves);
        assertEquals(0L, count(fixture.database(), "resource_mutation_receipt", "status = 'PENDING'"));
        assertEquals(2L, count(fixture.database(), "resource_mutation_receipt", "actor_id = 'core-catalog-evolution-v1' AND status = 'APPLIED' AND transition_published = 1"));
    }

    @Test
    void pendingRecoveryPublicationFailureRemainsBlockedAcrossAnotherRestart(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, List.of("flow"), true);
        fixture.core().activate();
        fixture.core().failureStage = 1;
        assertThrows(IllegalStateException.class, fixture::open);
        fixture.core().failureStage = 0;
        fixture.core().failPublication = true;
        for (int attempt = 0; attempt < 2; attempt++) {
            try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
                assertFalse(authority.durable());
            }
        }
        assertEquals(2, fixture.core().saves);
        assertEquals(0, fixture.core().repairs);
        fixture.core().failPublication = false;
        try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
            assertTrue(authority.durable());
        }
        assertEquals(2, fixture.core().saves);
    }

    @Test
    void changedActiveManifestDoesNotRetargetPendingEvidence(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, List.of("flow"), true);
        fixture.core().activate();
        fixture.core().failureStage = 1;
        assertThrows(IllegalStateException.class, fixture::open);
        fixture.core().failureStage = 0;
        fixture.core().active = new CatalogBinding(56L, CoreCatalogEvolutionTest.TARGET.catalogChecksum(),
            new ContentHash("7".repeat(64)));
        try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
            assertFalse(authority.durable());
        }
        assertEquals(6L, fixture.core().current.get(fixture.resource()).envelope().assetRevision());
        fixture.core().activate();
        try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
            assertTrue(authority.durable());
            assertEquals(7L, authority.load(fixture.resource()).revision());
        }
    }

    @Test
    void publicationFailureKeepsAppliedProofAndReplaysOnlyTheOutbox(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, List.of("flow"), true);
        fixture.core().activate();
        fixture.core().failPublication = true;
        assertThrows(IllegalStateException.class, fixture::open);
        assertEquals(1L, count(fixture.database(), "resource_mutation_receipt", "actor_id = 'core-catalog-evolution-v1' AND status = 'APPLIED' AND transition_published = 0 AND transition_envelope IS NOT NULL"));
        try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
            assertFalse(authority.durable());
        }
        assertEquals(1, fixture.core().saves);
        assertEquals(0, fixture.core().repairs);
        fixture.core().failPublication = false;
        try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
            assertTrue(authority.durable());
        }
        assertEquals(1, fixture.core().saves);
        assertEquals(1L, count(fixture.database(), "resource_mutation_receipt", "actor_id = 'core-catalog-evolution-v1' AND transition_published = 1 AND transition_envelope IS NULL"));
    }

    @Test
    void corruptAppliedProofBlocksBeforeOutboxPublication(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, List.of("flow"), true);
        fixture.core().activate();
        fixture.core().failPublication = true;
        assertThrows(IllegalStateException.class, fixture::open);
        execute(fixture.database(), "UPDATE core_catalog_evolution_receipt SET proof_hash = 'invalid'");
        fixture.core().failPublication = false;
        int publications = fixture.core().publications;
        assertThrows(IllegalStateException.class, fixture::open);
        assertEquals(publications, fixture.core().publications);
    }

    @Test
    void corruptPublishedProofBlocksBeforeRestoringHighWater(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, List.of("flow"), true);
        fixture.core().activate();
        try (SqliteProtocolResourceMutationAuthority ignored = fixture.open()) {
        }
        execute(fixture.database(), "UPDATE core_catalog_evolution_receipt SET result_asset_hash = 'invalid'");
        int publications = fixture.core().publications;
        assertThrows(IllegalStateException.class, fixture::open);
        assertEquals(publications, fixture.core().publications);
    }

    @Test
    void unreceiptedSourceIsLeftUntouched(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, List.of("flow"), false);
        fixture.core().activate();
        try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
            assertEquals(6L, authority.load(fixture.resource()).revision());
        }
        assertEquals(0, fixture.core().saves);
        assertEquals(0L, count(fixture.database(), "core_catalog_evolution_receipt", null));
    }

    @Test
    void finalSettlementRejectsAStoredHeadOmittedFromTheResourceList(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, List.of("flow"), true);
        fixture.core().activate();
        fixture.core().omitFromList = true;
        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(
            new FlowResourceRegistry(), CoreCatalogEvolutionTest.SERVER, fixture.database(), fixture.core(),
            ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L), AggregateResourceCreateStorage.unavailable(),
            null, SqliteProtocolResourceMutationAuthority.CatalogStartup.DEFERRED)) {
            assertThrows(IllegalStateException.class, () -> authority.settleCatalogBinding(CoreCatalogEvolutionTest.TARGET));
            assertFalse(authority.durable());
            assertEquals(0, fixture.core().saves);
        }
    }

    private static Fixture fixture(Path root, List<String> types, boolean receipt) throws Exception {
        TestCoreAuthority core = new TestCoreAuthority(CoreCatalogEvolutionTest.proof());
        for (String type : types) {
            CoreGraphStorageBoundary.Decoded source = CoreCatalogEvolutionTest.source(type, type, "command".equals(type));
            core.current.put(CoreCatalogCompatibilityRebind.graph(source).resource(), source);
        }
        Fixture fixture = new Fixture(root.resolve("runtime/resource-mutations.db"), core);
        try (SqliteProtocolResourceMutationAuthority authority = fixture.open()) {
            for (ServerResourceLocator resource : core.current.keySet()) {
                authority.load(resource);
            }
        }
        if (receipt) {
            seedSourceReceipts(fixture);
        }
        return fixture;
    }

    private static void seedSourceReceipts(Fixture fixture) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database());
             PreparedStatement receipt = connection.prepareStatement("""
                 INSERT INTO resource_mutation_receipt(mutation_id, actor_id, fingerprint, operation,
                     requested_resource, response_resource, source_resource, target_resource, expected_revision,
                     precondition_hash, status, result_revision, result_mutation_id, result_hash, result_deleted,
                     result_payload, target_activation_state, result_activation_state, precondition_asset_hash,
                     precondition_core_payload_hash, precondition_core_payload_kind, result_asset_hash,
                     result_core_payload_hash, result_core_payload_kind, transition_envelope, transition_hash,
                     transition_published, sequence, error_code, error_message, created_at, updated_at)
                 SELECT mutation_id, 'fixture', 'fixture', 'SAVE', resource, resource, NULL, resource, revision - 1,
                     '', 'APPLIED', revision, mutation_id, payload_hash, 0, NULL, NULL, activation_state,
                     NULL, NULL, NULL, asset_hash, core_payload_hash, core_payload_kind, NULL, ?, 1,
                     (SELECT COALESCE(MAX(sequence), 0) + 1 FROM resource_mutation_receipt), '', '', 1, 1
                 FROM resource_mutation_state WHERE resource = ?
                 """)) {
            for (CoreGraphStorageBoundary.Decoded source : fixture.core().current.values()) {
                ServerResourceLocator resource = CoreCatalogCompatibilityRebind.graph(source).resource();
                CoreResourceMutationTransition transition = new CoreResourceMutationTransition(resource,
                    source.envelope().assetRevision(), UUID.fromString(source.envelope().assetMutationId()), false,
                    source.envelope().assetActivationState(), "fixture",
                    new String(CoreCatalogEvolutionTest.BOUNDARY.encode(source), StandardCharsets.UTF_8));
                receipt.setString(1, transition.checkpoint().canonicalEnvelopeHash().canonicalText());
                receipt.setString(2, resource.canonicalText());
                assertEquals(1, receipt.executeUpdate());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void rewindEvolutionsAsPending(Fixture fixture) throws Exception {
        Map<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> sources = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database());
             PreparedStatement evidence = connection.prepareStatement("SELECT mutation_id, source_envelope FROM core_catalog_evolution_receipt");
             PreparedStatement state = connection.prepareStatement("""
                 UPDATE resource_mutation_state SET revision = ?, mutation_id = ?, payload_hash = ?, asset_hash = ?,
                     core_payload_hash = ?, core_payload_kind = ?, activation_state = ?, deleted = 0, payload = NULL
                 WHERE resource = ?
                 """);
             PreparedStatement receipt = connection.prepareStatement("""
                 UPDATE resource_mutation_receipt SET status = 'PENDING', sequence = 0, result_payload = ?,
                     transition_envelope = NULL, transition_hash = NULL, transition_published = 0
                 WHERE mutation_id = ? AND status = 'APPLIED'
                 """)) {
            connection.setAutoCommit(false);
            try (var result = evidence.executeQuery()) {
                while (result.next()) {
                    UUID mutationId = UUID.fromString(result.getString("mutation_id"));
                    CoreGraphStorageBoundary.Decoded candidate = fixture.core().current.values().stream()
                        .filter(value -> mutationId.toString().equals(value.envelope().assetMutationId())).findFirst().orElseThrow();
                    ServerResourceLocator resource = CoreCatalogCompatibilityRebind.graph(candidate).resource();
                    CoreGraphStorageBoundary.Decoded source = CoreCatalogEvolutionTest.BOUNDARY.decodeText(
                        result.getString("source_envelope"), resource);
                    Map<String, Object> sourcePayload = (Map<String, Object>) CanonicalJson.parse(CoreCatalogEvolutionTest.BOUNDARY.encode(source));
                    Map<String, Object> candidatePayload = (Map<String, Object>) CanonicalJson.parse(CoreCatalogEvolutionTest.BOUNDARY.encode(candidate));
                    state.setLong(1, source.envelope().assetRevision());
                    state.setString(2, source.envelope().assetMutationId());
                    state.setString(3, ResourcePayloadCodecs.json().canonicalize(sourcePayload).checksum().canonicalText());
                    state.setString(4, source.envelope().assetHash().canonicalText());
                    state.setString(5, source.graphDocument() != null ? source.graphDocument().checksum().canonicalText()
                        : source.functionSourceDocument().checksum().canonicalText());
                    state.setString(6, source.corePayloadKind());
                    state.setString(7, source.envelope().assetActivationState().wireName());
                    state.setString(8, resource.canonicalText());
                    assertEquals(1, state.executeUpdate());
                    receipt.setString(1, ResourcePayloadCodecs.json().canonicalInput(candidatePayload));
                    receipt.setString(2, mutationId.toString());
                    assertEquals(1, receipt.executeUpdate());
                    sources.put(resource, source);
                }
            }
            connection.commit();
        }
        assertEquals(2, sources.size());
        fixture.core().current.putAll(sources);
    }

    private static long count(Path database, String table, String predicate) throws Exception {
        if (!List.of("resource_mutation_receipt", "core_catalog_evolution_receipt").contains(table)) {
            throw new IllegalArgumentException("Unexpected fixture table");
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table
                 + (predicate == null ? "" : " WHERE " + predicate));
             var result = statement.executeQuery()) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static void execute(Path database, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private record Fixture(Path database, TestCoreAuthority core, ServerId server) {
        private Fixture(Path database, TestCoreAuthority core) {
            this(database, core, CoreCatalogEvolutionTest.SERVER);
        }

        private ServerResourceLocator resource() {
            return core.current.keySet().iterator().next();
        }

        private SqliteProtocolResourceMutationAuthority open() {
            return open(SqliteProtocolResourceMutationAuthority.CatalogStartup.IMMEDIATE);
        }

        private SqliteProtocolResourceMutationAuthority open(SqliteProtocolResourceMutationAuthority.CatalogStartup mode) {
            FlowResourceRegistry registry = new FlowResourceRegistry();
            registry.addCoreMutationListener(transition -> {
                core.publications++;
                if (core.failPublication) {
                    throw new IllegalStateException("Injected publication failure");
                }
            });
            return new SqliteProtocolResourceMutationAuthority(registry, server, database,
                core, ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L), registry, null, mode);
        }
    }

    private static final class TestCoreAuthority implements CoreGraphResourceAuthority {
        private final Map<ServerResourceLocator, CoreGraphStorageBoundary.Decoded> current = new LinkedHashMap<>();
        private CoreCatalogEvolution.Proof proof;
        private CatalogBinding active = CoreCatalogEvolutionTest.SOURCE;
        private int saves;
        private int repairs;
        private int publications;
        private int failureStage;
        private boolean failPublication;
        private boolean omitFromList;
        private boolean failAggregatePublication;
        private int creates;
        private int aggregatePublications;
        private final List<Long> aggregatePublishedRevisions = new ArrayList<>();
        private FlowStorageCoreGraphResourceAuthority backing;

        private TestCoreAuthority(CoreCatalogEvolution.Proof proof) {
            this.proof = proof;
        }

        private void activate() {
            active = proof.target();
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public Optional<CoreGraphStorageBoundary.Decoded> load(ServerResourceLocator resource) {
            return backing == null ? Optional.ofNullable(current.get(resource)) : backing.load(resource);
        }

        @Override
        public Optional<CatalogBinding> activeCatalogBinding() {
            return Optional.of(active);
        }

        @Override
        public Optional<CoreCatalogEvolution.Proof> activeCatalogEvolution() {
            return active.equals(proof.target()) ? Optional.of(proof) : Optional.empty();
        }

        @Override
        public List<CoreGraphResourceState> list(String type) {
            if (omitFromList) {
                return List.of();
            }
            if (backing != null) {
                return backing.list(type);
            }
            return current.values().stream().filter(source -> type.equals(source.envelope().resourceType()))
                .map(CoreGraphResourceState::live).toList();
        }

        @Override
        public Optional<CoreGraphResourceState> state(ServerResourceLocator resource) {
            return load(resource).map(CoreGraphResourceState::live);
        }

        @Override
        public void validateSave(ServerResourceLocator resource, CoreGraphStorageBoundary.Decoded candidate) {
            if (!active.equals(CoreCatalogCompatibilityRebind.graph(candidate).catalogBinding())) {
                throw new IllegalArgumentException("Candidate does not match the active test authority");
            }
        }

        @Override
        public CoreGraphStorageBoundary.Decoded save(ServerResourceLocator resource, byte[] envelope, UUID mutationId,
                                                      long expectedRevision, ContentHash payloadChecksum) {
            saves++;
            if (failureStage == 1) {
                throw new IllegalStateException("Injected failure before asset write");
            }
            CoreGraphStorageBoundary.Decoded candidate = CoreCatalogEvolutionTest.BOUNDARY.decode(envelope, resource);
            validateSave(resource, candidate);
            assertEquals(expectedRevision, load(resource).orElseThrow().envelope().assetRevision());
            assertEquals(expectedRevision + 1L, candidate.envelope().assetRevision());
            assertEquals(mutationId.toString(), candidate.envelope().assetMutationId());
            assertEquals(payloadChecksum, candidate.envelope().assetHash());
            if (backing != null) {
                candidate = backing.save(resource, envelope, mutationId, expectedRevision, payloadChecksum);
            }
            current.put(resource, candidate);
            if (failureStage == 2) {
                throw new IllegalStateException("Injected failure after asset write");
            }
            return candidate;
        }

        @Override
        public Optional<CoreRevisionRepairResult> repairRevisionSkew(ServerResourceLocator resource,
                                                                     CoreRevisionRepairSource source, UUID mutationId) {
            repairs++;
            throw new IllegalStateException("Evolution recovery must not invoke revision repair");
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
            if (backing == null) {
                throw new UnsupportedOperationException();
            }
            CoreGraphStorageBoundary.Decoded result = backing.activate(resource, activationState, mutationId,
                expectedRevision, payloadChecksum);
            current.put(resource, result);
            return result;
        }

        @Override
        public boolean supportsAggregateCreate() {
            return backing != null;
        }

        @Override
        public CoreGraphCreateResult create(ServerResourceLocator resource, CoreGraphStorageBoundary.Decoded source,
                                            UUID mutationId, long expectedRevision, ResourcePresentationIntent presentation) {
            creates++;
            CoreGraphCreateResult result = backing.create(resource, source, mutationId, expectedRevision, presentation);
            current.put(resource, result.primary());
            return result;
        }

        @Override
        public void publishCommitted(ServerResourceLocator resource, UUID mutationId) {
            if (backing != null) {
                aggregatePublications++;
                if (failAggregatePublication) {
                    throw new IllegalStateException("Injected aggregate publication failure");
                }
                aggregatePublishedRevisions.add(backing.load(resource).orElseThrow().envelope().assetRevision());
                backing.publishCommitted(resource, mutationId);
            }
        }
    }

    private static final class StorageFixture implements AutoCloseable {
        private Path activeRoot;
        private Path database;
        private final AssetPersistenceGate gate;
        private AssetTransactionCoordinator coordinator;
        private final ReSyncPersistenceCoordinator persistence;
        private final AssetCoordinatorMigration.Result migration;
        private final FlowResourceRegistry registry = new FlowResourceRegistry();
        private final TestCoreAuthority core = new TestCoreAuthority(CoreCatalogEvolutionTest.proof());
        private final FlowStorage storage;
        private final ConfigurationPersistenceParticipant configuration;
        private final StructuredFlowDiagnosticReporter reporter;
        private final DiagnosticReportPersistenceParticipant diagnostics;
        private AssetsPersistenceParticipant assets;
        private final List<String> transitionEvents = new ArrayList<>();

        private StorageFixture(Path root) throws Exception {
            Files.createDirectories(root);
            Path coordination = root.resolve("coordination");
            persistence = ReSyncPersistenceCoordinator.bootstrap(root.resolve("source"), coordination);
            activeRoot = persistence.prepareActiveRoot();
            var provenance = persistence.freshRootProvenance();
            migration = provenance.isPresent() ? AssetCoordinatorMigration.prepareEmpty(coordination, provenance.get())
                : AssetCoordinatorMigration.load(coordination);
            database = activeRoot.resolve("runtime/resource-mutations.db");
            gate = new AssetPersistenceGate(activeRoot);
            coordinator = migration.openOrAdopt(activeRoot.resolve("assets"), new Gson());
            AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(value -> value);
            if (!snapshot.metadata().document().has("serverId")) {
                assertEquals(0L, snapshot.rootSequence());
                UUID bootstrap = UUID.randomUUID();
                List<AssetTransactionCoordinator.ProjectDelta> changes = List.of(AssetTransactionCoordinator.ProjectDelta.set(
                    List.of("serverId"), new JsonPrimitive(CoreCatalogEvolutionTest.SERVER.canonicalText())));
                AssetTransactionCoordinator.AssetDelta lineage = ProjectMetadataLineage.writer(coordinator.canonicalRoot(),
                    new Gson()).write(snapshot, changes, bootstrap);
                coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(bootstrap, snapshot.project(),
                    List.of(lineage), changes));
            } else {
                assertEquals(CoreCatalogEvolutionTest.SERVER.canonicalText(), snapshot.metadata().document().get("serverId").getAsString());
            }
            configuration = ConfigLoader.load(activeRoot).getPersistenceParticipant();
            reporter = new StructuredFlowDiagnosticReporter(activeRoot.resolve("diagnostics"));
            diagnostics = new DiagnosticReportPersistenceParticipant(activeRoot, activeRoot.resolve("diagnostics"), reporter);
            storage = new FlowStorage(activeRoot.toFile(), LegacyRuntimeActivationGate.runtime(activeRoot), gate,
                configuration, CoreCatalogEvolutionTest.SERVER, coordinator);
            core.backing = new FlowStorageCoreGraphResourceAuthority(storage, CoreCatalogEvolutionTest.SERVER);
            new FlowResourcePacketRouter(storage, null, null, null, null, null, null, registry, ignored -> {
            });
            registry.bindCoreGraphResourceAuthority(core);
            ConnectionInfo observerConnection = connection(null, 1);
            observerConnection.setClientCapabilities(Set.of("resource_events"));
            Session observer = new Session("catalog-observer", "catalog-observer", observerConnection);
            Set<Session> observers = Set.of(observer);
            FlowCollaborationService collaboration = new FlowCollaborationService(observers,
                new FlowPacketSender(null, 1, observers) {
                    @Override
                    public void sendResourceChanged(Session session, String json) {
                        assertEquals(observer, session);
                        transitionEvents.add(json);
                    }
                });
            registry.addCoreMutationListener(transition -> {
                CoreGraphStorageBoundary.Decoded current = core.load(transition.locator()).orElseThrow();
                assertEquals(current.envelope().assetRevision(), transition.revision());
                assertEquals(current.envelope().assetMutationId(), transition.mutationId().toString());
                assertEquals(new String(CoreCatalogEvolutionTest.BOUNDARY.encode(current), StandardCharsets.UTF_8),
                    transition.canonicalEnvelope());
                collaboration.publishCoreMutation(transition);
            });
        }

        private AssetsPersistenceParticipant assetsParticipant() throws Exception {
            MockBukkit.mock();
            var plugin = MockBukkit.createMockPlugin();
            LegacyRuntimeActivationGate runtime = LegacyRuntimeActivationGate.runtime(activeRoot);
            ReSyncJsonResourceStorage json = new ReSyncJsonResourceStorage(plugin, runtime, gate, coordinator);
            CustomContentStorage custom = new CustomContentStorage(plugin, activeRoot, new ItemAttributeSchemaService(),
                runtime, gate, coordinator);
            WorldGenProjectStorage worldGen = new WorldGenProjectStorage(activeRoot.toFile(), runtime, gate, coordinator);
            assets = new AssetsPersistenceParticipant(activeRoot, gate, coordinator, new Gson(), migration, rebound -> {
                coordinator = rebound;
                activeRoot = rebound.canonicalRoot().getParent();
                database = activeRoot.resolve("runtime/resource-mutations.db");
            }, storage, json, custom, worldGen);
            return assets;
        }

        private List<ReSyncPersistenceTopology.Binding> persistenceBindings(
            SqliteProtocolResourceMutationPersistenceParticipant mutations, boolean includeDiagnostics) throws Exception {
            AssetsPersistenceParticipant assetOwner = assetsParticipant();
            List<PersistenceParticipant> participants = new ArrayList<>(List.of(configuration, assetOwner, mutations));
            if (includeDiagnostics) {
                participants.add(diagnostics);
            }
            participants.forEach(persistence::register);
            return participants.stream().map(participant -> ReSyncPersistenceTopology.requiredForRestore(
                participant.owner(), participant.root(), participant)).toList();
        }

        private ReSyncPersistenceTopology.Registration seal(SqliteProtocolResourceMutationAuthority authority) throws Exception {
            SqliteProtocolResourceMutationPersistenceParticipant mutations =
                new SqliteProtocolResourceMutationPersistenceParticipant(activeRoot, authority);
            List<ReSyncPersistenceTopology.Binding> bindings = persistenceBindings(mutations, true);
            assertOwnedFiles();
            ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(persistence, activeRoot, bindings);
            assertTrue(registration.sealed(), persistenceStatus(authority, registration));
            configuration.activateAndFlush();
            assertOwnedFiles();
            return registration;
        }

        private void assertOwnedFiles() throws Exception {
            Map<String, String> inventory = new LinkedHashMap<>();
            try (var files = Files.walk(activeRoot)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    String relative = activeRoot.relativize(file).toString().replace('\\', '/');
                    String expected = relative.startsWith("assets/") ? AssetsPersistenceParticipant.OWNER
                        : relative.startsWith("diagnostics/") ? DiagnosticReportPersistenceParticipant.OWNER
                        : relative.equals(ConfigurationPersistenceParticipant.FILE_NAME) ? ConfigurationPersistenceParticipant.OWNER
                        : Set.of("runtime/resource-mutations.db", "runtime/resource-mutations.db-wal",
                            "runtime/resource-mutations.db-shm").contains(relative)
                            ? SqliteProtocolResourceMutationPersistenceParticipant.OWNER : "";
                    assertFalse(expected.isEmpty(), "Unexpected fixture persistence file: " + relative);
                    String actual = persistence.participants().ownerFor(activeRoot, file);
                    assertEquals(expected, actual, relative);
                    inventory.put(relative, actual);
                }
            }
            assertTrue(inventory.containsKey("diagnostics/.locks/diagnostic-report-store.lock"), inventory.toString());
            assertTrue(inventory.containsKey("runtime/resource-mutations.db"), inventory.toString());
            assertEquals(ConfigurationPersistenceParticipant.OWNER,
                persistence.participants().ownerFor(activeRoot, configuration.root()));
        }

        private String persistenceStatus(SqliteProtocolResourceMutationAuthority authority,
                                         ReSyncPersistenceTopology.Registration registration) {
            return Map.of("unavailable", registration.unavailableReasons(), "rebind", persistence.participants().rebindStatus().payload(),
                "dataRoot", persistence.dataRoot().toString(), "fixtureRoot", activeRoot.toString(),
                "database", authority.databasePath().toString(), "owners", persistence.registeredParticipants().stream()
                    .map(participant -> Map.of("owner", participant.owner(), "root", participant.root().toString(),
                        "scope", participant instanceof RebindablePersistenceParticipant rebindable
                            ? rebindable.rebindScope().toString() : "not rebindable")).toList()).toString();
        }

        private SqliteProtocolResourceMutationAuthority open(SqliteProtocolResourceMutationAuthority.CatalogStartup mode) {
            return new SqliteProtocolResourceMutationAuthority(registry, CoreCatalogEvolutionTest.SERVER, database, core,
                ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L), registry, null, mode);
        }

        private void createSources(int total) {
            createSources(total, false);
        }

        private void createSources(int total, boolean advanceUnpublished) {
            try (SqliteProtocolResourceMutationAuthority authority = open(SqliteProtocolResourceMutationAuthority.CatalogStartup.IMMEDIATE)) {
                createSources(authority, total, advanceUnpublished);
            }
        }

        private void createSources(SqliteProtocolResourceMutationAuthority authority, int total, boolean advanceUnpublished) {
            List<CatalogBinding> bindings = CoreCatalogEvolution.load().sources().stream().sorted().toList();
            for (int index = 0; index < total; index++) {
                core.active = bindings.get(total == 18 && index >= 16 ? 1 : 0);
                String type = index < 12 && total == 18 ? List.of("flow", "command", "function").get(index % 3)
                    : index == 12 ? "flow" : "command";
                CoreGraphStorageBoundary.Decoded source = initialSource(type, "mixed-" + index, core.active);
                ServerResourceLocator resource = CoreCatalogCompatibilityRebind.graph(source).resource();
                ResourcePresentationIntent presentation = new ResourcePresentationIntent("Mixed " + index,
                    "Blueprints/" + type + "/" + resource.id() + ".json", index);
                ResourceCreateRequest<Map<String, Object>> request = new ResourceCreateRequest<>(resource,
                    ResourcePayloadCodecs.json().canonicalize(payload(source)),
                    UUID.fromString(source.envelope().assetMutationId()), presentation);
                ProtocolEnvelopeDispatchResult result = mutate(authority, request);
                if (core.failAggregatePublication) {
                    assertFalse(result.handled());
                    assertEquals("RESOURCE_MUTATION_PENDING", result.code());
                } else {
                    assertTrue(result.handled(), result.code() + ": " + result.message());
                    ProtocolEnvelopeDispatchResult replay = mutate(authority, request);
                    assertTrue(replay.handled(), replay.code() + ": " + replay.message());
                }
                if (total == 18 && index < 12 || advanceUnpublished) {
                    ProtocolEnvelopeDispatchResult activated = mutate(authority,
                        new ResourceActivateRequest(resource, 1L, ResourceActivationState.INACTIVE, UUID.randomUUID()));
                    assertTrue(activated.handled(), activated.code() + ": " + activated.message());
                }
            }
        }

        @Override
        public void close() throws Exception {
            reporter.quiesce();
            configuration.quiesce();
            if (assets != null) {
                try {
                    if (!persistence.shutdownStarted()) {
                        assets.quiesce();
                        assets.close();
                    }
                } finally {
                    MockBukkit.unmock();
                }
            } else {
                gate.quiesce();
                coordinator.close();
            }
        }
    }

    private static CoreGraphStorageBoundary.Decoded initialSource(String type, String id, CatalogBinding binding) {
        CoreGraphStorageBoundary.Decoded source = CoreCatalogEvolutionTest.source(type, id, false, binding);
        GraphDocument previous = CoreCatalogCompatibilityRebind.graph(source);
        GraphDocument graph = new GraphDocument(previous.schemaVersion(), previous.resource(), 1L, binding,
            previous.requiredCapabilities(), "command".equals(type) ? List.of(new GraphNode(NodeInstanceId.deterministic(id),
                CommandGraphContract.CANONICAL_START, 2, Map.of())) : previous.nodes(), previous.connections(),
            previous.variables(), previous.functions(), previous.unknown());
        CoreGraphStorageBoundary.AssetMetadata metadata = new CoreGraphStorageBoundary.AssetMetadata(type, 1L,
            UUID.fromString(source.envelope().assetMutationId()), ResourceActivationState.ACTIVE);
        byte[] bytes = "function".equals(type)
            ? CoreCatalogEvolutionTest.BOUNDARY.encode(new FunctionSourceDocument(new FunctionSignature(
                new FunctionLocator(graph.resource()), new FunctionRevision(1L), List.of(), List.of(), Map.of()), graph),
                metadata, graph.resource())
            : CoreCatalogEvolutionTest.BOUNDARY.encode(graph, metadata, graph.resource());
        return CoreCatalogEvolutionTest.BOUNDARY.decode(bytes, graph.resource());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(CoreGraphStorageBoundary.Decoded source) {
        return (Map<String, Object>) CanonicalJson.parse(CoreCatalogEvolutionTest.BOUNDARY.encode(source));
    }

    private static ResourceCreateRequest<Map<String, Object>> metadataCreate() {
        ServerResourceLocator resource = CoreCatalogEvolutionTest.resource("project_metadata", CoreCatalogEvolutionTest.SERVER.canonicalText());
        return new ResourceCreateRequest<>(resource, ResourcePayloadCodecs.json().canonicalize(
            Map.of("serverId", CoreCatalogEvolutionTest.SERVER.canonicalText(), "resources", List.of())), UUID.randomUUID());
    }

    private static ProtocolEnvelopeDispatchResult mutate(SqliteProtocolResourceMutationAuthority authority, ResourceOperation operation) {
        OwnerId owner = OwnerId.of("restudio.resync");
        ServerResourceLocator resource = operation instanceof ResourceCreateRequest<?> create ? create.resource()
            : ((ResourceActivateRequest) operation).resource();
        UUID mutationId = operation instanceof ResourceCreateRequest<?> create ? create.mutationId()
            : ((ResourceActivateRequest) operation).mutationId();
        long revision = operation instanceof ResourceCreateRequest<?> ? 0L : ((ResourceActivateRequest) operation).expectedRevision();
        Set<ContractRef<CapabilityId>> capabilities = operation instanceof ResourceCreateRequest<?> create && create.presentation() != null
            ? Set.of(ContractRef.of(owner, CapabilityId.of("resources")), ReSyncProtocolContract.RESOURCE_CREATE_PRESENTATION_CAPABILITY)
            : operation instanceof ResourceActivateRequest
                ? Set.of(ContractRef.of(owner, CapabilityId.of("resources")), ReSyncProtocolContract.RESOURCE_ACTIVATION_CAPABILITY)
                : Set.of(ContractRef.of(owner, CapabilityId.of("resources")));
        ProtocolEnvelope<Map<String, Object>> request = new ProtocolEnvelope<>(ProtocolEnvelope.Kind.REQUEST,
            new CatalogVersion(1, operation instanceof ResourceActivateRequest ? 1 : 2), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), CoreCatalogEvolutionTest.SERVER, resource, revision, 1L, mutationId,
            ContractRef.of(owner, OperationId.of("resource." + operation.kind().name().toLowerCase())), capabilities,
            ContractRef.of(owner, ResourceTypeId.of("resource.document")), null,
            operation instanceof ResourceCreateRequest<?> create ? create.payloadHash() : null, false,
            null, null, null, null, null, 0, ProtocolEnvelope.Status.ACCEPTED, List.of(), Map.of(),
            new ProtocolBody.ResourceRequest(operation));
        ConnectionInfo connection = connection(null, new FrameSender() {
            @Override
            public void send(byte[] frame) {
            }

            @Override
            public void close(int code, String reason) {
            }
        }, 1);
        connection.setClientId("catalog-create-test");
        connection.setClientVersion("2.1.0");
        connection.setState(ConnectionState.AUTHENTICATED);
        connection.setProtocolResourceAccess(true);
        Session session = new Session("catalog-create-session", "catalog-create-test", connection,
            new ClientIdentity("catalog-create-test", "2.1.0"));
        return authority.mutate(connection, session, request, operation);
    }

    private static ConnectionInfo connection(Object... arguments) {
        for (Constructor<?> constructor : ConnectionInfo.class.getConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length == arguments.length && types[0].getSimpleName().equals("WebSocket")
                && types[types.length - 1] == int.class) {
                try {
                    return (ConnectionInfo) constructor.newInstance(arguments);
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError("The packaged connection fixture could not be constructed", failure);
                }
            }
        }
        throw new AssertionError("The packaged connection constructor is unavailable");
    }
}
