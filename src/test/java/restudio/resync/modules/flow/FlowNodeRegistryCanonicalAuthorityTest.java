package restudio.resync.modules.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.sync.NodeRegistryRequest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlowNodeRegistryCanonicalAuthorityTest {
    private static final ServerId SERVER_ID = ServerId.of(UUID.fromString("11111111-1111-4111-8111-111111111111"));

    @Test
    void typedPublicationRejectsLegacyMetadataWithoutCanonicalContent() {
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), null, null, null);
        handler.setCanonicalServerIdentity(SERVER_ID);
        handler.requireCanonicalCatalogAuthority();
        handler.setActiveCatalogMetadata(new FlowNodeRegistryPacketHandler.ActiveCatalogMetadata(1L, "a".repeat(64),
            List.of(), List.of(), Map.of("activeNodeIds", List.of("fixture:node"))));

        assertThrows(IllegalStateException.class, handler::buildFullSnapshot);
    }

    @Test
    void typedPublicationKeepsTheExistingSnapshotShapeForCanonicalCatalogs() {
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), null, null, null);
        handler.setCanonicalServerIdentity(SERVER_ID);
        handler.requireCanonicalCatalogAuthority();
        CatalogSnapshot catalog = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        handler.setActiveCatalogMetadata(FlowNodeRegistryPacketHandler.activeCatalogMetadata(catalog));

        var snapshot = handler.buildFullSnapshot();

        assertEquals(catalog.contentChecksum().canonicalText(), snapshot.getCatalogChecksum());
        assertEquals(catalog.canonicalContent(), snapshot.getOpaqueData().get("catalogCanonicalContent"));
    }

    @Test
    void negotiatedLegacySnapshotCarriesCanonicalServerIdentity() {
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), null, null, null);
        handler.setCanonicalServerIdentity(SERVER_ID);
        handler.requireCanonicalCatalogAuthority();
        handler.setActiveCatalogMetadata(FlowNodeRegistryPacketHandler.activeCatalogMetadata(
            CatalogSnapshot.empty(new CatalogVersion(1, 0))));
        NodeRegistryRequest request = new NodeRegistryRequest();
        request.setCompatibilityCapability(NodeRegistryRequest.LEGACY_COMPATIBILITY_CAPABILITY);

        var snapshot = handler.buildSnapshot(request);

        assertEquals(SERVER_ID.canonicalText(), snapshot.getServerIdentity());
    }

    @Test
    void legacySnapshotFailsClosedWithoutCanonicalServerIdentity() {
        FlowNodeRegistryPacketHandler handler = new FlowNodeRegistryPacketHandler(new NodeDefinitionRegistry(), null, null, null);
        handler.requireCanonicalCatalogAuthority();
        handler.setActiveCatalogMetadata(FlowNodeRegistryPacketHandler.activeCatalogMetadata(
            CatalogSnapshot.empty(new CatalogVersion(1, 0))));
        NodeRegistryRequest request = new NodeRegistryRequest();
        request.setCompatibilityCapability(NodeRegistryRequest.LEGACY_COMPATIBILITY_CAPABILITY);

        assertThrows(IllegalStateException.class, () -> handler.buildSnapshot(request));
    }
}
