package restudio.resync.flow;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowStorageServerIdentityTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("e65887a4-ea27-4c55-bae2-e1c8d92da433"));

    @TempDir
    Path tempDir;

    @Test
    void persistsCanonicalServerIdentityAndAcceptsLegacyProjectAlias() throws Exception {
        try (AssetTransactionCoordinator coordinator = coordinator()) {
            FlowStorage storage = storage(coordinator);
            storage.saveProjectMetadata("{\"serverId\":\"project\",\"resources\":[]}",
                UUID.fromString("11111111-1111-4111-8111-111111111111"), 0L);

            String canonical = SERVER.canonicalText();
            var persisted = JsonParser.parseString(Files.readString(tempDir.resolve("assets/project.json")))
                .getAsJsonObject();
            assertEquals(canonical, persisted.get("serverId").getAsString());
            assertEquals(storage.getProjectMetadata("project"), storage.getProjectMetadata(canonical));
            assertNull(storage.getProjectMetadata("other-server"));
            assertEquals(List.of(canonical), storage.listProjectMetadataIds());
            assertEquals(storage.readProjectMetadataIdentity("project"), storage.readProjectMetadataIdentity(canonical));
        }
    }

    @Test
    void rejectsForeignProjectMetadataServerIdentity() throws Exception {
        try (AssetTransactionCoordinator coordinator = coordinator()) {
            FlowStorage storage = storage(coordinator);
            assertThrows(IllegalStateException.class, () -> storage.saveProjectMetadata(
                "{\"serverId\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\",\"resources\":[]}",
                UUID.fromString("22222222-2222-4222-8222-222222222222"), 0L));
            assertFalse(Files.readString(tempDir.resolve("assets/project.json")).contains("aaaaaaaa-aaaa"));
        }
    }

    @Test
    void settlesExactSaveHashFromCanonicalAliasPayloadAndRecognizesReplay() throws Exception {
        try (AssetTransactionCoordinator coordinator = coordinator()) {
            FlowStorage storage = storage(coordinator);
            String alias = "{\"serverId\":\"project\",\"resources\":[]}";
            String canonical = storage.normalizeProjectMetadataPayload(alias);
            String expectedHash = ResourcePayloadCodecs.json().hashPayload(
                new Gson().fromJson(canonical, Map.class)).canonicalText();
            UUID mutationId = UUID.fromString("33333333-3333-4333-8333-333333333333");

            storage.saveProjectMetadata(alias, mutationId, 0L);
            FlowStorage.ResourceIdentity first = storage.readProjectMetadataIdentity(SERVER.canonicalText());
            storage.saveProjectMetadata(canonical, mutationId, 0L);

            assertEquals(expectedHash, first.payloadHash());
            assertEquals(first, storage.readProjectMetadataIdentity("project"));
            assertEquals(JsonParser.parseString(canonical),
                JsonParser.parseString(storage.getProjectMetadata(SERVER.canonicalText())));
        }
    }

    @Test
    void recognizesExactDeleteReplayAfterCanonicalSave() throws Exception {
        try (AssetTransactionCoordinator coordinator = coordinator()) {
            FlowStorage storage = storage(coordinator);
            UUID saveMutation = UUID.fromString("55555555-5555-4555-8555-555555555555");
            UUID deleteMutation = UUID.fromString("66666666-6666-4666-8666-666666666666");
            storage.saveProjectMetadata("{\"serverId\":\"project\",\"resources\":[]}", saveMutation, 0L);

            storage.deleteProjectMetadata("project", deleteMutation, 1L);
            storage.deleteProjectMetadata(SERVER.canonicalText(), deleteMutation, 1L);

            FlowStorage.ResourceIdentity identity = storage.readProjectMetadataIdentity("project");
            assertTrue(identity.deleted());
            assertEquals(2L, identity.revision());
            assertEquals(List.of(), storage.listProjectMetadataIds());
            assertNull(storage.getProjectMetadata(SERVER.canonicalText()));
        }
    }

    private AssetTransactionCoordinator coordinator() throws Exception {
        return AssetTransactionCoordinator.open(tempDir.resolve("assets"), new Gson());
    }

    private FlowStorage storage(AssetTransactionCoordinator coordinator) {
        return new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.runtime(tempDir),
            new AssetPersistenceGate(tempDir), SERVER, coordinator);
    }
}
