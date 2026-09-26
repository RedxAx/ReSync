package restudio.resync.network.paper;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.network.paper.ReSyncNetworkAgentConfig.ResourceConflictPolicy;
import restudio.resync.network.paper.ReSyncNetworkAgentConfig.ResourcePolicy;
import restudio.resync.network.paper.ReSyncNetworkAgentConfig.SelectionMode;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkResourceSynchronizerTest {
    @TempDir
    Path temporary;

    @Test
    void incompleteLocalSnapshotCannotBeReconciledAsDeletions() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(new FlowResourceAdapter<String>() {
            @Override
            public ReSyncManagedResource descriptor() {
                return ReSyncResourceCatalog.byType(ReSyncResourceCatalog.GUI);
            }

            @Override
            public String get(String id) {
                if ("second".equals(id)) throw new IllegalStateException("Read Failed");
                return id;
            }

            @Override
            public List<String> listIds() {
                return List.of("first", "second");
            }

            @Override
            public String deserialize(String json) {
                return json;
            }

            @Override
            public String id(String value) {
                return value;
            }

            @Override
            public void save(String value) {
            }

            @Override
            public void delete(String id) {
            }
        });
        ResourcePolicy policy = new ResourcePolicy(true, SelectionMode.ALL, Set.of(), ResourceConflictPolicy.NETWORK_WINS);
        NetworkResourceSynchronizer synchronizer = new NetworkResourceSynchronizer(null, null, registry, policy, temporary, null);

        IllegalStateException failure = assertThrows(IllegalStateException.class, synchronizer::scanLocalResources);
        assertTrue(failure.getMessage().contains(ReSyncResourceCatalog.GUI));
    }

    @Test
    void scanIdentityTracksLiveDeletionAndRecreation() throws Exception {
        Path root = temporary.resolve("assets");
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("flow", "one");
        Path path = Path.of("Blueprints/Flows/one.json");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, new Gson())) {
            AssetTransactionCoordinator.ExpectedProject project = coordinator.read(AssetTransactionCoordinator.Snapshot::project);
            coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(UUID.randomUUID(), project,
                List.of(AssetTransactionCoordinator.AssetDelta.write(key, path,
                    AssetTransactionCoordinator.Missing.INSTANCE, "first".getBytes(StandardCharsets.UTF_8))), List.of()));
            AssetTransactionCoordinator.Snapshot live = coordinator.read(snapshot -> snapshot);
            AssetTransactionCoordinator.CommittedAsset first = NetworkResourceSynchronizer.snapshotIdentity(live, key);
            assertEquals(coordinator.committedAsset(key).orElseThrow(), first);

            coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(UUID.randomUUID(), live.project(),
                List.of(AssetTransactionCoordinator.AssetDelta.delete(key, path, live.states().get(key))), List.of()));
            AssetTransactionCoordinator.Snapshot deleted = coordinator.read(snapshot -> snapshot);
            AssetTransactionCoordinator.CommittedAsset tombstone = NetworkResourceSynchronizer.snapshotIdentity(deleted, key);
            assertEquals(coordinator.committedAsset(key).orElseThrow(), tombstone);
            assertNotEquals(first, tombstone);

            coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(UUID.randomUUID(), deleted.project(),
                List.of(AssetTransactionCoordinator.AssetDelta.write(key, path, deleted.states().get(key),
                    "second".getBytes(StandardCharsets.UTF_8))), List.of()));
            AssetTransactionCoordinator.Snapshot recreated = coordinator.read(snapshot -> snapshot);
            assertEquals(coordinator.committedAsset(key).orElseThrow(), NetworkResourceSynchronizer.snapshotIdentity(recreated, key));
            assertNotEquals(tombstone, NetworkResourceSynchronizer.snapshotIdentity(recreated, key));
        }
    }
}
