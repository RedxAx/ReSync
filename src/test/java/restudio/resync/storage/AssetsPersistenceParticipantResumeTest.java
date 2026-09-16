package restudio.resync.storage;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.customcontent.ItemAttributeSchemaService;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.resources.JsonAssetInventory;
import restudio.resync.upgrade.AssetCoordinatorMigration;
import restudio.resync.worldgen.WorldGenProjectStorage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetsPersistenceParticipantResumeTest {
    @TempDir
    Path tempDir;

    private AssetsPersistenceParticipant participant;
    private AssetTransactionCoordinator coordinator;
    private AssetTransactionCoordinator alternateCoordinator;
    private AssetPersistenceGate gate;
    private RecordingFlowStorage flowStorage;
    private RecordingJsonResourceStorage jsonStorage;
    private RecordingCustomContentStorage customContentStorage;
    private RecordingWorldGenProjectStorage worldGenStorage;
    private boolean mocked;

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (participant != null) {
                participant.close();
            } else if (coordinator != null) {
                coordinator.close();
            }
            if (alternateCoordinator != null) {
                alternateCoordinator.close();
            }
        } finally {
            if (mocked) {
                MockBukkit.unmock();
            }
        }
    }

    @Test
    void coordinatedResumeKeepsGateQuiescedUntilAllChildrenResume() throws Exception {
        createFixture();

        participant.quiesce();
        participant.resume();

        assertTrue(gate.isOpen());
        assertEquals(List.of(false), flowStorage.observedGateStates);
        assertEquals(List.of(false), jsonStorage.observedGateStates);
        assertEquals(List.of(false), customContentStorage.observedGateStates);
        assertEquals(List.of(false), worldGenStorage.observedGateStates);
    }

    @Test
    void failedResumeLeavesGateQuiescedAndRetryCanComplete() throws Exception {
        createFixture();

        participant.quiesce();
        flowStorage.failResume = true;

        assertThrows(IOException.class, participant::resume);
        assertFalse(gate.isOpen());
        assertEquals(List.of(false), flowStorage.observedGateStates);
        assertEquals(List.of(false), jsonStorage.observedGateStates);
        assertEquals(List.of(false), customContentStorage.observedGateStates);
        assertEquals(List.of(false), worldGenStorage.observedGateStates);

        flowStorage.failResume = false;
        participant.resume();

        assertTrue(gate.isOpen());
        assertEquals(List.of(false, false), flowStorage.observedGateStates);
        assertEquals(List.of(false, false), jsonStorage.observedGateStates);
        assertEquals(List.of(false, false), customContentStorage.observedGateStates);
        assertEquals(List.of(false, false), worldGenStorage.observedGateStates);
    }

    @Test
    void partialResumeFailureRollsChildrenBackBeforeRetry() throws Exception {
        createFixture();

        participant.quiesce();
        jsonStorage.failResume = true;

        assertThrows(IOException.class, participant::resume);
        assertFalse(gate.isOpen());

        jsonStorage.failResume = false;
        participant.resume();

        assertTrue(gate.isOpen());
        assertEquals(List.of(false, false), flowStorage.observedGateStates);
        assertEquals(List.of(false, false), jsonStorage.observedGateStates);
        assertEquals(List.of(false, false), customContentStorage.observedGateStates);
        assertEquals(List.of(false, false), worldGenStorage.observedGateStates);
    }

    @Test
    void ownershipIndexIncludesTheSharedAssetsRootAndDescendants() throws Exception {
        createFixture();

        PersistenceOwnershipContext context = new PersistenceOwnershipContext(
            participant.rebindScope(), participant.root());
        PersistenceOwnershipIndex index = participant.ownershipIndex(context);

        assertTrue(index.owns(context.relativeToSource(participant.root())));
        assertTrue(index.owns(context.relativeToSource(participant.root().resolve("nested/file.json"))));
        assertFalse(index.owns("other/file.json"));
    }

    @Test
    void aggregateHealthRunsEachChildLocalValidatorOnce() throws Exception {
        AtomicInteger inventoryScans = new AtomicInteger();
        createFixture(active -> active.flush(), root -> {
            inventoryScans.incrementAndGet();
            return JsonAssetInventory.scan(root);
        });

        participant.healthCheck();

        assertEquals(1, inventoryScans.get());
        assertEquals(1, flowStorage.localHealthChecks);
        assertEquals(1, jsonStorage.localHealthChecks);
        assertEquals(1, customContentStorage.localHealthChecks);
        assertEquals(1, worldGenStorage.localHealthChecks);
        assertNotNull(jsonStorage.healthInventory);
        assertSame(jsonStorage.healthInventory, customContentStorage.healthInventory);
        assertSame(jsonStorage.healthInventory, worldGenStorage.healthInventory);
    }

    @Test
    void sameRootRebindPreservesLiveChildrenAndValidatesExactCoordinatorIdentity() throws Exception {
        createFixture();

        participant.quiesce();
        participant.rebind(participant.rebindScope());

        assertFalse(gate.isOpen());
        assertEquals(1, flowStorage.coordinatorValidations);
        assertEquals(1, jsonStorage.coordinatorValidations);
        assertEquals(1, customContentStorage.coordinatorValidations);
        assertEquals(1, worldGenStorage.coordinatorValidations);
        assertEquals(0, flowStorage.localHealthChecks);
        assertEquals(0, jsonStorage.localHealthChecks);
        assertEquals(0, customContentStorage.localHealthChecks);
        assertEquals(0, worldGenStorage.localHealthChecks);
    }

    @Test
    void coordinatedResumeReusesSuccessfulQuiescedHealthProof() throws Exception {
        createFixture();

        participant.quiesce();
        participant.healthCheck();
        participant.resume();

        assertTrue(gate.isOpen());
        assertEquals(1, flowStorage.localHealthChecks);
        assertEquals(1, jsonStorage.localHealthChecks);
        assertEquals(1, customContentStorage.localHealthChecks);
        assertEquals(1, worldGenStorage.localHealthChecks);
        assertEquals(List.of(false), flowStorage.observedGateStates);
        assertEquals(List.of(false), jsonStorage.observedGateStates);
        assertEquals(List.of(false), customContentStorage.observedGateStates);
        assertEquals(List.of(false), worldGenStorage.observedGateStates);
    }

    @Test
    void repeatedQuiescedHealthCheckReusesValidatedInventory() throws Exception {
        AtomicInteger inventoryScans = new AtomicInteger();
        createFixture(active -> active.flush(), root -> {
            inventoryScans.incrementAndGet();
            return JsonAssetInventory.scan(root);
        });

        participant.quiesce();
        participant.healthCheck();
        participant.healthCheck();

        assertEquals(1, inventoryScans.get());
        assertEquals(1, flowStorage.localHealthChecks);
        assertEquals(1, jsonStorage.localHealthChecks);
        assertEquals(1, customContentStorage.localHealthChecks);
        assertEquals(1, worldGenStorage.localHealthChecks);
    }

    @Test
    void readinessChecksFreshBindingWithoutRepeatingTheFullHealthPass() throws Exception {
        createFixture();

        participant.readinessCheck();

        assertEquals(0, flowStorage.localHealthChecks);
        assertEquals(0, jsonStorage.localHealthChecks);
        assertEquals(0, customContentStorage.localHealthChecks);
        assertEquals(0, worldGenStorage.localHealthChecks);
    }

    @Test
    void aggregateFlushUsesOneCoordinatorPassAndPreservesChildValidation() throws Exception {
        AtomicInteger coordinatorFlushes = new AtomicInteger();
        createFixture(active -> {
            coordinatorFlushes.incrementAndGet();
            active.flush();
        });

        participant.flush();

        assertEquals(1, coordinatorFlushes.get());
        assertEquals(1, flowStorage.coordinatorValidations);
        assertEquals(1, jsonStorage.coordinatorValidations);
        assertEquals(1, customContentStorage.coordinatorValidations);
        assertEquals(1, worldGenStorage.coordinatorValidations);
        assertEquals(0, flowStorage.localHealthChecks);
        assertEquals(0, jsonStorage.localHealthChecks);
        assertEquals(0, customContentStorage.localHealthChecks);
        assertEquals(0, worldGenStorage.localHealthChecks);
    }

    @Test
    void aggregateFlushPropagatesCoordinatorFailure() throws Exception {
        AtomicInteger coordinatorFlushes = new AtomicInteger();
        IOException coordinatorFailure = new IOException("coordinator flush failed");
        createFixture(active -> {
            coordinatorFlushes.incrementAndGet();
            throw coordinatorFailure;
        });

        assertEquals(coordinatorFailure, assertThrows(IOException.class, participant::flush));
        assertEquals(1, coordinatorFlushes.get());
        assertEquals(0, worldGenStorage.localHealthChecks);
    }

    @Test
    void aggregateFlushDoesNotRepeatWorldGenHealthValidation() throws Exception {
        AtomicInteger coordinatorFlushes = new AtomicInteger();
        createFixture(active -> {
            coordinatorFlushes.incrementAndGet();
            active.flush();
        });
        worldGenStorage.failLocalHealthCheck = true;

        participant.flush();
        assertEquals(1, coordinatorFlushes.get());
        assertEquals(0, worldGenStorage.localHealthChecks);
    }

    @Test
    void aggregateHealthPropagatesJsonValidatorFailure() throws Exception {
        createFixture();
        jsonStorage.failLocalHealthCheck = true;

        assertThrows(IOException.class, participant::healthCheck);
        assertEquals(1, jsonStorage.localHealthChecks);
        assertEquals(0, customContentStorage.localHealthChecks);
        assertEquals(0, worldGenStorage.localHealthChecks);
    }

    @Test
    void aggregateHealthPropagatesFlowValidatorFailure() throws Exception {
        createFixture();
        flowStorage.failLocalHealthCheck = true;

        assertThrows(IOException.class, participant::healthCheck);
        assertEquals(1, flowStorage.localHealthChecks);
        assertEquals(0, jsonStorage.localHealthChecks);
        assertEquals(0, customContentStorage.localHealthChecks);
        assertEquals(0, worldGenStorage.localHealthChecks);
    }

    @Test
    void aggregateHealthPropagatesCustomContentValidatorFailure() throws Exception {
        createFixture();
        customContentStorage.failLocalHealthCheck = true;

        assertThrows(IOException.class, participant::healthCheck);
        assertEquals(1, jsonStorage.localHealthChecks);
        assertEquals(1, customContentStorage.localHealthChecks);
        assertEquals(0, worldGenStorage.localHealthChecks);
    }

    @Test
    void aggregateHealthPropagatesWorldGenValidatorFailure() throws Exception {
        createFixture();
        worldGenStorage.failLocalHealthCheck = true;

        assertThrows(IOException.class, participant::healthCheck);
        assertEquals(1, jsonStorage.localHealthChecks);
        assertEquals(1, customContentStorage.localHealthChecks);
        assertEquals(1, worldGenStorage.localHealthChecks);
    }

    @Test
    void aggregateHealthRejectsAChildBoundToAnotherCoordinatorInstance() throws Exception {
        createFixture();
        alternateCoordinator = new AssetTransactionCoordinator(coordinator.canonicalRoot(), new Gson());

        participant.quiesce();
        flowStorage.rebindPersistence(flowStorage.getAssetsPath(), alternateCoordinator);

        assertThrows(IOException.class, participant::healthCheck);
    }

    @Test
    void aggregateHealthKeepsCoordinatorExclusiveDuringLocalValidation() throws Exception {
        createFixture();
        CountDownLatch localValidationEntered = new CountDownLatch(1);
        CountDownLatch releaseLocalValidation = new CountDownLatch(1);
        CountDownLatch healthFinished = new CountDownLatch(1);
        CountDownLatch transactionFinished = new CountDownLatch(1);
        AtomicReference<Throwable> healthFailure = new AtomicReference<>();
        AtomicReference<Throwable> transactionFailure = new AtomicReference<>();
        flowStorage.localValidationEntered = localValidationEntered;
        flowStorage.releaseLocalValidation = releaseLocalValidation;

        Thread health = new Thread(() -> {
            try {
                participant.healthCheck();
            } catch (Throwable failure) {
                healthFailure.set(failure);
            } finally {
                healthFinished.countDown();
            }
        });
        health.start();
        Thread transaction = null;
        try {
            assertTrue(localValidationEntered.await(5L, TimeUnit.SECONDS));
            transaction = new Thread(() -> {
                try {
                    AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(current -> current);
                    coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(
                        UUID.fromString("b7000000-0000-4000-8000-000000000001"), snapshot.project(), List.of(),
                        List.of(AssetTransactionCoordinator.ProjectDelta.set(List.of("serverId"),
                            JsonParser.parseString("\"health\"")))));
                } catch (Throwable failure) {
                    transactionFailure.set(failure);
                } finally {
                    transactionFinished.countDown();
                }
            });
            transaction.start();
            assertFalse(transactionFinished.await(100L, TimeUnit.MILLISECONDS));
        } finally {
            releaseLocalValidation.countDown();
            assertTrue(healthFinished.await(5L, TimeUnit.SECONDS));
            if (transaction != null) {
                assertTrue(transactionFinished.await(5L, TimeUnit.SECONDS));
            }
        }
        assertNull(healthFailure.get());
        assertNull(transactionFailure.get());
    }

    private void createFixture() throws Exception {
        createFixture(active -> active.flush());
    }

    private void createFixture(AssetsPersistenceParticipant.CoordinatorFlush coordinatorFlush) throws Exception {
        createFixture(coordinatorFlush, JsonAssetInventory::scan);
    }

    private void createFixture(AssetsPersistenceParticipant.CoordinatorFlush coordinatorFlush,
                               AssetsPersistenceParticipant.AssetInventoryFactory assetInventoryFactory) throws Exception {
        MockBukkit.mock();
        mocked = true;
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path scope = Files.createDirectories(tempDir.resolve("scope")).toAbsolutePath().normalize();
        Path assets = Files.createDirectories(scope.resolve("assets"));
        gate = new AssetPersistenceGate(scope);
        coordinator = new AssetTransactionCoordinator(assets, new Gson());
        LegacyRuntimeActivationGate runtime = LegacyRuntimeActivationGate.runtime(scope);
        flowStorage = new RecordingFlowStorage(scope.toFile(), runtime, gate, coordinator);
        jsonStorage = new RecordingJsonResourceStorage(plugin, runtime, gate, coordinator);
        customContentStorage = new RecordingCustomContentStorage(plugin, scope, gate, coordinator);
        worldGenStorage = new RecordingWorldGenProjectStorage(scope.toFile(), runtime, gate, coordinator);
        AssetCoordinatorMigration.Result migration = new AssetCoordinatorMigration.Result(
            scope.resolve("migration-artifact"), "0".repeat(64), "1".repeat(64),
            new AssetTransactionCoordinator.AdoptionInventory("resume-test", "{}", List.of()), List.of());
        participant = new AssetsPersistenceParticipant(scope, gate, coordinator, new Gson(), migration,
            ignored -> { }, flowStorage, jsonStorage, customContentStorage, worldGenStorage, coordinatorFlush,
            assetInventoryFactory);
    }

    private static final class RecordingFlowStorage extends FlowStorage {
        private final AssetPersistenceGate gate;
        private final List<Boolean> observedGateStates = new ArrayList<>();
        private int coordinatorValidations;
        private int localHealthChecks;
        private boolean failResume;
        private boolean failLocalHealthCheck;
        private CountDownLatch localValidationEntered;
        private CountDownLatch releaseLocalValidation;

        private RecordingFlowStorage(File dataFolder, LegacyRuntimeActivationGate runtime,
                                     AssetPersistenceGate gate, AssetTransactionCoordinator coordinator) {
            super(dataFolder, runtime, gate, coordinator);
            this.gate = gate;
        }

        @Override
        public synchronized void resumePersistenceWhileQuiesced() throws IOException {
            observedGateStates.add(gate.isOpen());
            if (failResume) {
                throw new IOException("Flow resume failed");
            }
            super.resumePersistenceWhileQuiesced();
        }

        @Override
        public synchronized void resumePersistenceAfterHealthCheck() throws IOException {
            observedGateStates.add(gate.isOpen());
            if (failResume) {
                throw new IOException("Flow resume failed");
            }
            super.resumePersistenceAfterHealthCheck();
        }

        @Override
        public synchronized void healthCheckPersistenceLocal() throws IOException {
            localHealthChecks++;
            if (failLocalHealthCheck) {
                throw new IOException("Flow local health check failed");
            }
            if (localValidationEntered != null && releaseLocalValidation != null) {
                localValidationEntered.countDown();
                try {
                    if (!releaseLocalValidation.await(5L, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to release Flow local health validation");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Flow local health validation was interrupted", failure);
                }
            }
            super.healthCheckPersistenceLocal();
        }

        @Override
        public synchronized void validateActiveCoordinator(AssetTransactionCoordinator expected) throws IOException {
            coordinatorValidations++;
            super.validateActiveCoordinator(expected);
        }
    }

    private static final class RecordingJsonResourceStorage extends ReSyncJsonResourceStorage {
        private final AssetPersistenceGate gate;
        private final List<Boolean> observedGateStates = new ArrayList<>();
        private int coordinatorValidations;
        private int localHealthChecks;
        private boolean failResume;
        private boolean failLocalHealthCheck;
        private JsonAssetInventory healthInventory;

        private RecordingJsonResourceStorage(JavaPlugin plugin, LegacyRuntimeActivationGate runtime,
                                             AssetPersistenceGate gate, AssetTransactionCoordinator coordinator) {
            super(plugin, runtime, gate, coordinator);
            this.gate = gate;
        }

        @Override
        public synchronized void resumePersistenceWhileQuiesced() throws IOException {
            observedGateStates.add(gate.isOpen());
            if (failResume) {
                throw new IOException("JSON resource resume failed");
            }
            super.resumePersistenceWhileQuiesced();
        }

        @Override
        public synchronized void resumePersistenceAfterHealthCheck() throws IOException {
            observedGateStates.add(gate.isOpen());
            if (failResume) {
                throw new IOException("JSON resource resume failed");
            }
            super.resumePersistenceAfterHealthCheck();
        }

        @Override
        public synchronized void healthCheckPersistenceLocal() throws IOException {
            super.healthCheckPersistenceLocal();
        }

        @Override
        public synchronized void healthCheckPersistenceLocal(JsonAssetInventory inventory) throws IOException {
            localHealthChecks++;
            healthInventory = inventory;
            if (failLocalHealthCheck) {
                throw new IOException("JSON resource local health check failed");
            }
            super.healthCheckPersistenceLocal(inventory);
        }

        @Override
        public synchronized void validateActiveCoordinator(AssetTransactionCoordinator expected) throws IOException {
            coordinatorValidations++;
            super.validateActiveCoordinator(expected);
        }
    }

    private static final class RecordingCustomContentStorage extends CustomContentStorage {
        private final AssetPersistenceGate gate;
        private final List<Boolean> observedGateStates = new ArrayList<>();
        private int coordinatorValidations;
        private int localHealthChecks;
        private boolean failLocalHealthCheck;
        private JsonAssetInventory healthInventory;

        private RecordingCustomContentStorage(JavaPlugin plugin, Path scope,
                                              AssetPersistenceGate gate, AssetTransactionCoordinator coordinator) {
            super(plugin, scope, new ItemAttributeSchemaService(), LegacyRuntimeActivationGate.runtime(scope), gate, coordinator);
            this.gate = gate;
        }

        @Override
        public synchronized void resumePersistenceWhileQuiesced() throws IOException {
            observedGateStates.add(gate.isOpen());
            super.resumePersistenceWhileQuiesced();
        }

        @Override
        public synchronized void resumePersistenceAfterHealthCheck() throws IOException {
            observedGateStates.add(gate.isOpen());
            super.resumePersistenceAfterHealthCheck();
        }

        @Override
        public synchronized void healthCheckPersistenceLocal() throws IOException {
            super.healthCheckPersistenceLocal();
        }

        @Override
        public synchronized void healthCheckPersistenceLocal(JsonAssetInventory inventory) throws IOException {
            localHealthChecks++;
            healthInventory = inventory;
            if (failLocalHealthCheck) {
                throw new IOException("Custom content local health check failed");
            }
            super.healthCheckPersistenceLocal(inventory);
        }

        @Override
        public synchronized void validateActiveCoordinator(AssetTransactionCoordinator expected) throws IOException {
            coordinatorValidations++;
            super.validateActiveCoordinator(expected);
        }
    }

    private static final class RecordingWorldGenProjectStorage extends WorldGenProjectStorage {
        private final AssetPersistenceGate gate;
        private final List<Boolean> observedGateStates = new ArrayList<>();
        private int coordinatorValidations;
        private int localHealthChecks;
        private boolean failLocalHealthCheck;
        private JsonAssetInventory healthInventory;

        private RecordingWorldGenProjectStorage(File dataFolder, LegacyRuntimeActivationGate runtime,
                                                AssetPersistenceGate gate, AssetTransactionCoordinator coordinator) {
            super(dataFolder, runtime, gate, coordinator);
            this.gate = gate;
        }

        @Override
        public synchronized void resumePersistenceWhileQuiesced() throws IOException {
            observedGateStates.add(gate.isOpen());
            super.resumePersistenceWhileQuiesced();
        }

        @Override
        public synchronized void resumePersistenceAfterHealthCheck() throws IOException {
            observedGateStates.add(gate.isOpen());
            super.resumePersistenceAfterHealthCheck();
        }

        @Override
        public synchronized void healthCheckPersistenceLocal() throws IOException {
            super.healthCheckPersistenceLocal();
        }

        @Override
        public synchronized void healthCheckPersistenceLocal(JsonAssetInventory inventory) throws IOException {
            localHealthChecks++;
            healthInventory = inventory;
            if (failLocalHealthCheck) {
                throw new IOException("WorldGen local health check failed");
            }
            super.healthCheckPersistenceLocal(inventory);
        }

        @Override
        public synchronized void validateActiveCoordinator(AssetTransactionCoordinator expected) throws IOException {
            coordinatorValidations++;
            super.validateActiveCoordinator(expected);
        }
    }
}
