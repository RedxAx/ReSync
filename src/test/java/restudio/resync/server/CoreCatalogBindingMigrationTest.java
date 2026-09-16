package restudio.resync.server;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CoreCatalogBindingMigrationTest {
    @Test
    void loadsTheFrozenSingleIncidentTestManifest() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
            "/restudio/resync/migration/core-catalog-binding-rebind-v1-test.json")) {
            CoreCatalogBindingMigration migration = CoreCatalogBindingMigration.parse(
                new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8));

            assertEquals(1, migration.incidents().size());
            assertEquals("flow/ad", migration.incidents().values().iterator().next().key());
        }
    }

    @Test
    void rejectsAnArbitraryGraphWithTheHistoricalBinding() {
        CoreCatalogBindingMigration migration = CoreCatalogBindingMigration.load();
        ServerResourceLocator resource = new ServerResourceLocator(ServerId.deterministic("catalog-rebind-test"),
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("flow")), "not-allowlisted");
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, 2L, migration.source(), Set.of(),
            List.of(), List.of(), List.of(), List.of(), OpaqueData.empty());
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        byte[] bytes = boundary.encode(graph, new CoreGraphStorageBoundary.AssetMetadata("flow", 2L,
            UUID.fromString("17c43951-c037-41b2-975f-8f7bfefcf590"), ResourceActivationState.ACTIVE), resource);

        assertFalse(migration.eligible(resource, boundary.decode(bytes, resource)));
        assertEquals(13, migration.incidents().size());
        assertEquals(CoreCatalogBindingMigration.TARGET, migration.target());
    }
}
