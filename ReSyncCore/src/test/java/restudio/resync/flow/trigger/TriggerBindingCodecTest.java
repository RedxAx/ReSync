package restudio.resync.flow.trigger;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.graph.ExecutionTarget;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.identity.TriggerBindingId;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TriggerBindingCodecTest {
    private static final ServerId SERVER = ServerId.of(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final ContractRef<NodeId> SOURCE = ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of("player-join"));
    private static final CatalogBinding CATALOG = new CatalogBinding(7, hash('a'), hash('b'));
    private static final TriggerBindingCodec CODEC = TriggerBindingCodec.INSTANCE;

    @Test
    void roundTripPreservesOpaqueDataAndCanonicalizesBindingOrder() {
        TriggerBinding second = binding("22222222-2222-4222-8222-222222222222", "second",
            OpaqueData.of(Map.of("futureBinding", Map.of("enabled", true))));
        TriggerBinding first = binding("11111111-1111-4111-8111-111111111111", "first", OpaqueData.empty());
        TriggerBindingDocument document = new TriggerBindingDocument(SERVER, 9,
            UUID.fromString("33333333-3333-4333-8333-333333333333"), List.of(second, first),
            OpaqueData.of(Map.of("futureDocument", List.of("kept", "two"))));

        TriggerBindingDocument decoded = CODEC.decodeText(CODEC.encodeText(document));

        assertEquals(List.of(first.id(), second.id()), decoded.bindings().stream().map(TriggerBinding::id).toList());
        assertEquals(document, decoded);
        assertEquals(Map.of("futureBinding", Map.of("enabled", true)), decoded.bindings().get(1).unknown().fields());
        assertEquals(Map.of("futureDocument", List.of("kept", "two")), decoded.unknown().fields());
    }

    @Test
    void decodeRejectsNoncanonicalBindingOrderAndUnknownTargetFields() {
        TriggerBinding first = binding("11111111-1111-4111-8111-111111111111", "first", OpaqueData.empty());
        TriggerBinding second = binding("22222222-2222-4222-8222-222222222222", "second", OpaqueData.empty());
        TriggerBindingDocument document = new TriggerBindingDocument(SERVER, 1,
            UUID.fromString("33333333-3333-4333-8333-333333333333"), List.of(first, second));
        JsonValue.JsonObject encoded = CODEC.encode(document);
        JsonValue.JsonArray bindings = (JsonValue.JsonArray) encoded.value("bindings");
        Map<String, JsonValue> reversedFields = new LinkedHashMap<>(encoded.fields());
        reversedFields.put("bindings", JsonValue.array(List.of(bindings.values().get(1), bindings.values().getFirst())));

        assertThrows(IllegalArgumentException.class, () -> CODEC.decode(JsonValue.object(reversedFields)));

        JsonValue.JsonObject encodedBinding = (JsonValue.JsonObject) bindings.values().getFirst();
        JsonValue.JsonObject target = (JsonValue.JsonObject) encodedBinding.value("target");
        Map<String, JsonValue> targetFields = new LinkedHashMap<>(target.fields());
        targetFields.put("future", JsonValue.of(true));
        Map<String, JsonValue> bindingFields = new LinkedHashMap<>(encodedBinding.fields());
        bindingFields.put("target", JsonValue.object(targetFields));
        assertThrows(IllegalArgumentException.class, () -> CODEC.decodeBinding(JsonValue.object(bindingFields)));
    }

    @Test
    void documentRejectsDuplicateIdentityRouteTargetAndOpaqueCollisions() {
        TriggerBinding first = binding("11111111-1111-4111-8111-111111111111", "first", OpaqueData.empty());
        TriggerBinding duplicateId = new TriggerBinding(first.id(), first.route(), target("second"));
        TriggerBinding duplicateRouteTarget = new TriggerBinding(
            TriggerBindingId.of(UUID.fromString("22222222-2222-4222-8222-222222222222")), first.route(), first.target());

        assertThrows(IllegalArgumentException.class, () -> new TriggerBindingDocument(SERVER, 1,
            UUID.fromString("33333333-3333-4333-8333-333333333333"), List.of(first, duplicateId)));
        assertThrows(IllegalArgumentException.class, () -> new TriggerBindingDocument(SERVER, 1,
            UUID.fromString("33333333-3333-4333-8333-333333333333"), List.of(first, duplicateRouteTarget)));
        assertThrows(IllegalArgumentException.class, () -> new TriggerBinding(first.id(), first.route(), first.target(),
            OpaqueData.of(Map.of("target", "collision"))));
        assertThrows(IllegalArgumentException.class, () -> new TriggerBindingDocument(SERVER, 1,
            UUID.fromString("33333333-3333-4333-8333-333333333333"), List.of(first),
            OpaqueData.of(Map.of("revision", 3))));
    }

    private static TriggerBinding binding(String id, String resourceId, OpaqueData unknown) {
        return new TriggerBinding(TriggerBindingId.of(UUID.fromString(id)), new TriggerRoute(TriggerKind.EVENT, SOURCE),
            target(resourceId), unknown);
    }

    private static ExecutionTarget target(String resourceId) {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("flow")), resourceId);
        return new ExecutionTarget(resource, NodeInstanceId.deterministic(resourceId + "-start"), 4, CATALOG);
    }

    private static ContentHash hash(char value) {
        return ContentHash.of(String.valueOf(value).repeat(64));
    }
}
