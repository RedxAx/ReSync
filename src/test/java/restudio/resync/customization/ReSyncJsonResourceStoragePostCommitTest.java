package restudio.resync.customization;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.protocol.ResourcePresentationIntent;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceMutationStamp;
import restudio.resync.resources.JsonAssetStore;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.CanonicalProjectMetadataFixture;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncJsonResourceStoragePostCommitTest {
    private static final String TYPE = ReSyncResourceCatalog.CHAT;
    @TempDir Path temporary;
    private AssetPersistenceGate gate;
    private AssetTransactionCoordinator coordinator;
    private ReSyncJsonResourceStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        gate = new AssetPersistenceGate(temporary);
        coordinator = AssetTransactionCoordinator.open(temporary.resolve("assets"), new Gson());
        CanonicalProjectMetadataFixture.seed(coordinator);
        storage = new ReSyncJsonResourceStorage(plugin, LegacyRuntimeActivationGate.runtime(temporary), gate, coordinator);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (storage != null) storage.closePersistence();
            if (coordinator != null) coordinator.close();
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void failedPrecommitValidationNeverPublishesItsCandidate() {
        AtomicReference<String> projection = new AtomicReference<>();
        AtomicBoolean reject = new AtomicBoolean();
        storage.addInterceptor(new ReSyncJsonResourceStorage.ResourceMutationInterceptor() {
            @Override
            public void beforeSave(String type, JsonObject value) {
                if (reject.get()) throw new IllegalArgumentException("Candidate rejected");
            }

            @Override
            public void afterCommit(String type, String id, JsonObject value, FlowResourceMutationStamp stamp) {
                projection.set(value.get("displayName").getAsString());
            }
        });
        storage.save(TYPE, value("main", "Committed"), UUID.randomUUID(), 0);
        FlowResourceMutationStamp committed = storage.readMutationStamp(TYPE, "main");
        reject.set(true);

        assertThrows(FlowResourceAdapter.PreCommitRejection.class,
            () -> storage.save(TYPE, value("main", "Candidate"), UUID.randomUUID(), committed.revision()));

        assertEquals("Committed", projection.get());
        assertEquals("Committed", storage.get(TYPE, "main").get("displayName").getAsString());
        assertEquals(committed, storage.readMutationStamp(TYPE, "main"));
    }

    @Test
    void committedSaveFailurePreservesDataAndExactRecoveryPublishesDetachedPayloads() {
        IllegalStateException expected = new IllegalStateException("Projection unavailable");
        AtomicBoolean fail = new AtomicBoolean(true);
        AtomicBoolean compensated = new AtomicBoolean();
        AtomicReference<String> projection = new AtomicReference<>();
        List<String> listeners = new ArrayList<>();
        storage.addInterceptor(new ReSyncJsonResourceStorage.ResourceMutationInterceptor() {
            @Override
            public void afterCommit(String type, String id, JsonObject value, FlowResourceMutationStamp stamp) {
                if (fail.get()) throw expected;
                projection.set(value.get("displayName").getAsString());
                value.addProperty("displayName", "Changed By Callback");
            }

            @Override
            public void afterSaveFailure(String type, JsonObject value, RuntimeException failure) {
                compensated.set(true);
            }
        });
        storage.addListener((type, id, value, deleted) -> listeners.add(value.get("displayName").getAsString()));
        UUID mutation = UUID.randomUUID();

        assertSame(expected, assertThrows(IllegalStateException.class,
            () -> storage.save(TYPE, value("main", "Durable"), mutation, 0)));

        FlowResourceMutationStamp stamp = storage.readMutationStamp(TYPE, "main");
        assertEquals(mutation, stamp.mutationId());
        assertEquals(1, stamp.revision());
        assertEquals("Durable", storage.get(TYPE, "main").get("displayName").getAsString());
        assertFalse(compensated.get());
        assertTrue(listeners.isEmpty());
        fail.set(false);
        assertThrows(IllegalStateException.class, () -> storage.completePostCommitRecovery(TYPE, "main", UUID.randomUUID(), 1, false));
        assertThrows(IllegalStateException.class, () -> storage.completePostCommitRecovery(TYPE, "main", mutation, 2, false));
        assertThrows(IllegalStateException.class, () -> storage.completePostCommitRecovery(TYPE, "main", mutation, 1, true));
        assertNull(projection.get());

        storage.completePostCommitRecovery(TYPE, "main", mutation, 1, false);

        assertEquals("Durable", projection.get());
        assertEquals(List.of("Durable"), listeners);
        assertEquals("Durable", storage.get(TYPE, "main").get("displayName").getAsString());
        assertEquals(stamp, storage.readMutationStamp(TYPE, "main"));
    }

    @Test
    void committedDeleteFailureRetainsItsTombstoneWithoutPrecommitCompensation() {
        UUID save = UUID.randomUUID();
        storage.save(TYPE, value("main", "Durable"), save, 0);
        IllegalStateException expected = new IllegalStateException("Delete projection unavailable");
        AtomicBoolean fail = new AtomicBoolean(true);
        AtomicBoolean compensated = new AtomicBoolean();
        AtomicBoolean cleared = new AtomicBoolean();
        storage.addInterceptor(new ReSyncJsonResourceStorage.ResourceMutationInterceptor() {
            @Override
            public void afterCommit(String type, String id, JsonObject value, FlowResourceMutationStamp stamp) {
                if (fail.get()) throw expected;
                assertTrue(stamp.deleted());
                assertNull(value);
                cleared.set(true);
            }

            @Override
            public void afterDeleteFailure(String type, String id, RuntimeException failure) {
                compensated.set(true);
            }
        });
        UUID delete = UUID.randomUUID();

        assertSame(expected, assertThrows(IllegalStateException.class, () -> storage.delete(TYPE, "main", delete, 1)));

        FlowResourceMutationStamp stamp = storage.readMutationStamp(TYPE, "main");
        assertEquals(delete, stamp.mutationId());
        assertEquals(2, stamp.revision());
        assertTrue(stamp.deleted());
        assertNull(storage.get(TYPE, "main"));
        assertFalse(compensated.get());
        fail.set(false);
        assertThrows(IllegalStateException.class, () -> storage.completePostCommitRecovery(TYPE, "main", save, 1, false));
        assertFalse(cleared.get());

        storage.completePostCommitRecovery(TYPE, "main", delete, 2, true);

        assertTrue(cleared.get());
        assertEquals(stamp, storage.readMutationStamp(TYPE, "main"));
    }

    @Test
    void aggregateCreateFailureIsRecoverableWithoutAnotherDurableMutation() {
        JsonObject value = value("created", "Created");
        UUID mutation = UUID.randomUUID();
        String hash = ResourcePayloadCodecs.json().hashPayload(new Gson().fromJson(value, Map.class)).canonicalText();
        ResourcePresentationIntent presentation = new ResourcePresentationIntent("Created", "Chat/created.json", 0);
        AtomicBoolean fail = new AtomicBoolean(true);
        AtomicBoolean compensated = new AtomicBoolean();
        AtomicReference<FlowResourceMutationStamp> projected = new AtomicReference<>();
        storage.addInterceptor(new ReSyncJsonResourceStorage.ResourceMutationInterceptor() {
            @Override
            public void afterCommit(String type, String id, JsonObject payload, FlowResourceMutationStamp stamp) {
                if (fail.get()) throw new IllegalStateException("Create projection unavailable");
                projected.set(stamp);
            }

            @Override
            public void afterSaveFailure(String type, JsonObject payload, RuntimeException failure) {
                compensated.set(true);
            }
        });

        assertThrows(IllegalStateException.class, () -> storage.create(TYPE, value, mutation, 0, presentation, hash));

        FlowResourceMutationStamp committed = storage.readMutationStamp(TYPE, "created");
        assertEquals(mutation, committed.mutationId());
        assertEquals("Created", storage.get(TYPE, "created").get("displayName").getAsString());
        assertFalse(compensated.get());
        long sequence = storage.committedSequence();
        fail.set(false);

        JsonAssetStore.AggregateCreateResult replay = storage.create(TYPE, value, mutation, 0, presentation, hash);

        assertTrue(replay.replayed());
        assertEquals(sequence, storage.committedSequence());
        assertEquals(committed, projected.get());
        assertEquals(committed, storage.readMutationStamp(TYPE, "created"));
    }

    @Test
    void ordinaryWritesPublishTheActualStoreMutationIdentity() {
        List<FlowResourceMutationStamp> projected = new ArrayList<>();
        storage.addInterceptor(new ReSyncJsonResourceStorage.ResourceMutationInterceptor() {
            @Override
            public void afterCommit(String type, String id, JsonObject value, FlowResourceMutationStamp stamp) {
                projected.add(stamp);
                assertEquals(stamp.deleted(), value == null);
            }
        });

        storage.save(TYPE, value("main", "Durable"));

        assertEquals(List.of(storage.readMutationStamp(TYPE, "main")), projected);
        storage.delete(TYPE, "main");
        assertEquals(storage.readMutationStamp(TYPE, "main"), projected.getLast());
        assertTrue(projected.getLast().deleted());
    }

    @Test
    void reloadPublishesDurablePayloadAndDoesNotCompensateForProjectionFailure() {
        storage.save(TYPE, value("main", "Durable"), UUID.randomUUID(), 0);
        AtomicBoolean compensated = new AtomicBoolean();
        AtomicReference<String> projection = new AtomicReference<>();
        AtomicBoolean fail = new AtomicBoolean(true);
        storage.addInterceptor(new ReSyncJsonResourceStorage.ResourceMutationInterceptor() {
            @Override
            public void beforeSave(String type, JsonObject value) {
                value.addProperty("displayName", "Reload Candidate");
            }

            @Override
            public void afterCommit(String type, String id, JsonObject value, FlowResourceMutationStamp stamp) {
                projection.set(value.get("displayName").getAsString());
                if (fail.get()) throw new IllegalStateException("Reload projection unavailable");
            }

            @Override
            public void afterSaveFailure(String type, JsonObject value, RuntimeException failure) {
                compensated.set(true);
            }
        });

        assertThrows(IllegalStateException.class, () -> storage.reload(TYPE, "main"));

        assertEquals("Durable", projection.get());
        assertEquals("Durable", storage.get(TYPE, "main").get("displayName").getAsString());
        assertFalse(compensated.get());
        fail.set(false);
        assertEquals("Durable", storage.reload(TYPE, "main").get("displayName").getAsString());
    }

    @Test
    void projectionCallbacksCanQuiesceStorageFromAnotherThread() {
        storage.addInterceptor(new ReSyncJsonResourceStorage.ResourceMutationInterceptor() {
            @Override
            public void afterCommit(String type, String id, JsonObject value, FlowResourceMutationStamp stamp) {
                CompletableFuture<Void> quiesced = CompletableFuture.runAsync(storage::quiescePersistence);
                try {
                    quiesced.get(2, TimeUnit.SECONDS);
                    storage.resumePersistence();
                } catch (Exception failure) {
                    throw new IllegalStateException("Projection callback retained a persistence gate", failure);
                }
            }
        });

        storage.save(TYPE, value("main", "Durable"), UUID.randomUUID(), 0);

        assertEquals("Durable", storage.get(TYPE, "main").get("displayName").getAsString());
    }

    private JsonObject value(String id, String displayName) {
        JsonObject value = new JsonObject();
        value.addProperty("id", id);
        value.addProperty("displayName", displayName);
        return value;
    }
}
