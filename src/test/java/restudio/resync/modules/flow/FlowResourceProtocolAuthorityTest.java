package restudio.resync.modules.flow;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowResourceProtocolAuthorityTest {
    private final FlowResourceProtocolAuthority authority = FlowResourceProtocolAuthority.shared();

    @Test
    void validatesEveryServerDescriptorAgainstTheSharedContract() {
        for (ReSyncManagedResource descriptor : ReSyncResourceCatalog.all()) {
            authority.validateDescriptor(descriptor);
        }
        assertEquals(ReSyncProtocolContract.resource(ReSyncResourceCatalog.FLOW).flowPackets().data(),
            authority.requireFlowPackets(ReSyncResourceCatalog.FLOW).data());
    }

    @Test
    void rejectsDescriptorDriftAtTheResourceBoundary() {
        ReSyncManagedResource flow = ReSyncResourceCatalog.byType(ReSyncResourceCatalog.FLOW);
        ReSyncManagedResource drift = new ReSyncManagedResource(
            flow.typeId(), flow.displayName(), "Blueprints/Future", flow.flowPackets(), flow.enabled(), flow.jsonStorageSupported());

        assertThrows(IllegalStateException.class, () -> authority.validateDescriptor(drift));
    }

    @Test
    void unknownGraphFieldsRemainOpaqueToTheSharedBoundary() {
        String json = """
            {"id":"opaque","version":2,"nodes":{},"connections":[],"localVariables":[],"future":{"keep":[1,true]}}
            """;
        FlowGraph graph = FlowSerializer.deserialize(json);
        authority.validateDescriptor(ReSyncResourceCatalog.byType(ReSyncResourceCatalog.FLOW));

        var serialized = JsonParser.parseString(FlowSerializer.serialize(graph)).getAsJsonObject();
        assertTrue(serialized.has("future"));
        assertEquals(JsonParser.parseString(json).getAsJsonObject().get("future"), serialized.get("future"));
    }
}
