package restudio.resync.modules.flow;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowResourcePacketRouterServerIdentityTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("e65887a4-ea27-4c55-bae2-e1c8d92da433"));

    @TempDir
    Path tempDir;

    @Test
    void routesCanonicalMetadataIdentityWhileKeepingProjectAsInputAlias() throws Exception {
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(tempDir.resolve("assets"), new Gson())) {
            FlowStorage storage = new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.runtime(tempDir),
                new AssetPersistenceGate(tempDir), SERVER, coordinator);
            FlowResourceRegistry registry = new FlowResourceRegistry();
            new FlowResourcePacketRouter(storage, null, null, null, null, null, null, registry, ignored -> {
            });
            @SuppressWarnings("unchecked")
            FlowResourceAdapter<String> adapter = (FlowResourceAdapter<String>) registry.get(
                ReSyncResourceCatalog.PROJECT_METADATA);

            adapter.save("{\"serverId\":\"project\",\"resources\":[]}",
                UUID.fromString("44444444-4444-4444-8444-444444444444"), 0L);

            String canonical = SERVER.canonicalText();
            assertEquals(canonical, adapter.id("{}"));
            assertEquals(canonical, adapter.defaultRequestId());
            assertEquals("singleton:" + canonical, adapter.identityRules());
            assertEquals(List.of(canonical), adapter.listIds());
            assertEquals(adapter.get("project"), adapter.get(canonical));
            assertNull(adapter.get("other-server"));
            assertEquals(canonical, JsonParser.parseString(adapter.get("project"))
                .getAsJsonObject().get("serverId").getAsString());
        }
    }

    @Test
    void exactRegistryAdmissionUsesCanonicalMetadataPayloadHash() throws Exception {
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(tempDir.resolve("assets"), new Gson())) {
            FlowStorage storage = new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.runtime(tempDir),
                new AssetPersistenceGate(tempDir), SERVER, coordinator);
            FlowResourceRegistry registry = new FlowResourceRegistry();
            new FlowResourcePacketRouter(storage, null, null, null, null, null, null, registry, ignored -> {
            });
            @SuppressWarnings("unchecked")
            FlowResourceAdapter<String> adapter = (FlowResourceAdapter<String>) registry.get(
                ReSyncResourceCatalog.PROJECT_METADATA);
            String canonical = adapter.deserialize("{\"serverId\":\"project\",\"resources\":[]}");
            String payloadHash = ResourcePayloadCodecs.json().hashPayload(new Gson().fromJson(canonical, Map.class))
                .canonicalText();
            UUID mutationId = UUID.fromString("77777777-7777-4777-8777-777777777777");
            FlowResourceMutationLease lease = registry.mutationAdmission().acquire(
                List.of(new FlowResourceKey(ReSyncResourceCatalog.PROJECT_METADATA, SERVER.canonicalText())),
                mutationId.toString());
            FlowResourceMutationContext context = new FlowResourceMutationContext("protocol", "", "", "client", lease,
                mutationId, 0L, payloadHash);
            try {
                assertTrue(registry.create(ReSyncResourceCatalog.PROJECT_METADATA, canonical, context).success());
            } finally {
                lease.close();
            }

            assertEquals(payloadHash, adapter.readMutationStamp(SERVER.canonicalText()).payloadHash());
            assertEquals(SERVER.canonicalText(), JsonParser.parseString(adapter.get("project"))
                .getAsJsonObject().get("serverId").getAsString());
        }
    }
}
