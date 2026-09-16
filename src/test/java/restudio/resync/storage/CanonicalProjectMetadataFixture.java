package restudio.resync.storage;

import com.google.gson.Gson;
import com.google.gson.JsonPrimitive;
import restudio.resync.flow.identity.ServerId;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

public final class CanonicalProjectMetadataFixture {
    private static final UUID SERVER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MUTATION_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private CanonicalProjectMetadataFixture() {
    }

    public static ServerId serverId() {
        return new ServerId(SERVER_ID);
    }

    public static void seed(AssetTransactionCoordinator coordinator) throws IOException {
        Gson gson = new Gson();
        AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(current -> current);
        List<AssetTransactionCoordinator.ProjectDelta> projectDeltas = List.of(
            AssetTransactionCoordinator.ProjectDelta.set(List.of("serverId"), new JsonPrimitive(SERVER_ID.toString())));
        AssetTransactionCoordinator.AssetDelta lineage = ProjectMetadataLineage.writer(
            coordinator.canonicalRoot(), gson).write(snapshot, projectDeltas, MUTATION_ID);
        coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(
            MUTATION_ID, snapshot.project(), List.of(lineage), projectDeltas));
    }
}
