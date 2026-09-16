package restudio.resync.upgrade.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.CoreGraphProjectionContext;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RawLegacyGraphAdmissionTest {
    @Test
    void preservesRawDataAndProducesStableStructuredIdentities() {
        String first = """
            {"id":"main","resourceType":"flow","enabled":false,"nodes":{"b":{"type":"second","version":1,"x":2,"y":3,"future":{"x":1}},"a":{"type":"first","version":1,"x":0,"y":1}},"connections":[{"sourceNodeId":"a","sourcePin":"out","targetNodeId":"b","targetPin":"in"}],"futureRoot":{"nested":true}}
            """;
        String reordered = """
            {"connections":[{"targetPin":"in","targetNodeId":"b","sourcePin":"out","sourceNodeId":"a"}],"nodes":{"a":{"y":1,"x":0,"version":1,"type":"first"},"b":{"future":{"x":1},"y":3,"x":2,"version":1,"type":"second"}},"futureRoot":{"nested":true},"resourceType":"flow","enabled":false,"id":"main"}
            """;
        var one = RawLegacyGraphAdmission.admit(first, context());
        var two = RawLegacyGraphAdmission.admit(reordered, context());
        assertEquals(one.nodes(), two.nodes());
        assertEquals(one.connections(), two.connections());
        assertEquals(one.canonicalRaw(), two.canonicalRaw());
        assertEquals("main", one.outer().id());
        assertEquals("flow", one.outer().resourceType());
        assertEquals(ResourceActivationState.INACTIVE, one.outer().activationState());
        assertFalse(one.innerJson().contains("enabled"));
        JsonValue.JsonObject futureRoot = (JsonValue.JsonObject) one.rawJson().value("futureRoot");
        assertEquals(true, ((JsonValue.JsonBoolean) futureRoot.value("nested")).value());
    }

    @Test
    void rejectsDuplicateEndpointsMissingNodesAndReservedOuterFields() {
        String connection = "{\"sourceNodeId\":\"a\",\"sourcePin\":\"out\",\"targetNodeId\":\"b\",\"targetPin\":\"in\"}";
        assertThrows(IllegalArgumentException.class, () -> RawLegacyGraphAdmission.admit(
            "{\"id\":\"main\",\"resourceType\":\"flow\",\"nodes\":{\"a\":{},\"b\":{}},\"connections\":[" + connection + "," + connection + "]}", context()));
        assertThrows(IllegalArgumentException.class, () -> RawLegacyGraphAdmission.admit(
            "{\"id\":\"main\",\"resourceType\":\"flow\",\"nodes\":{\"a\":{}},\"connections\":[" + connection + "]}", context()));
        var activation = RawLegacyGraphAdmission.admit(
            "{\"id\":\"main\",\"resourceType\":\"flow\",\"assetActivationState\":\"inactive\",\"nodes\":{},\"connections\":[]}", context());
        assertEquals(ResourceActivationState.INACTIVE, activation.outer().activationState());
        assertThrows(IllegalArgumentException.class, () -> RawLegacyGraphAdmission.admit(
            "{\"id\":\"main\",\"resourceType\":\"flow\",\"nodes\":{},\"nodes\":{},\"connections\":[]}", context()));
        assertThrows(IllegalArgumentException.class, () -> RawLegacyGraphAdmission.admit(
            "{\"id\":\"other\",\"resourceType\":\"flow\",\"nodes\":{},\"connections\":[]}", context()));
        assertThrows(IllegalArgumentException.class, () -> RawLegacyGraphAdmission.admit(
            "{\"id\":\"main\",\"resourceType\":\"command\",\"nodes\":{},\"connections\":[]}", context()));
        assertThrows(IllegalArgumentException.class, () -> RawLegacyGraphAdmission.admit(
            "{\"id\":\"main\",\"resourceType\":\"flow\",\"resourceMutationId\":\"legacy-mutation\",\"nodes\":{},\"connections\":[]}", context()));
    }

    @Test
    void derivesIdentitiesFromTheFixedVersionedNamespace() {
        String source = "{\"id\":\"main\",\"resourceType\":\"flow\",\"nodes\":{\"node\":{}},\"connections\":[]}";
        var one = RawLegacyGraphAdmission.admit(source,
            context(UUID.fromString("22222222-2222-4222-8222-222222222222")));
        var two = RawLegacyGraphAdmission.admit(source,
            context(UUID.fromString("33333333-3333-4333-8333-333333333333")));
        assertEquals(one.nodes(), two.nodes());
        assertEquals(one.connections(), two.connections());
    }

    private static CoreGraphProjectionContext context() {
        return context(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    }

    private static CoreGraphProjectionContext context(UUID identityNamespace) {
        CatalogSnapshot snapshot = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        CatalogBinding binding = new CatalogBinding(snapshot.generation(), snapshot.contentChecksum(), snapshot.bindingManifestHash());
        return new CoreGraphProjectionContext(new ServerResourceLocator(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("flow")), "main"),
            snapshot, binding, 2, identityNamespace);
    }
}
