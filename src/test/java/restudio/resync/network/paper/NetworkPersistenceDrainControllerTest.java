package restudio.resync.network.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.MigrationFence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkPersistenceDrainControllerTest {
    @TempDir
    Path temporary;

    @Test
    void leaseStaysHeldUntilTheFinalAsyncCallbackAndUsesTheMigrationFence() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        MigrationFence fence = new MigrationFence();
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(source, fence, Duration.ofSeconds(2));
        FakeComponent component = new FakeComponent("manifest", source.resolve("manifest"));
        controller.register(component);

        NetworkPersistenceDrainController.Lease lease = controller.acquire("delayed-operation");
        assertTrue(fence.tryAcquireMigration(Duration.ZERO).isEmpty());

        CompletableFuture<Void> quiesce = CompletableFuture.runAsync(() -> {
            try {
                controller.quiescePersistence(Duration.ofSeconds(2));
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        });
        waitForState(controller, NetworkPersistenceDrainController.State.QUIESCING);
        assertEquals(1, controller.activeLeaseCount());
        assertFalse(quiesce.isDone());

        lease.close();
        quiesce.get(2, TimeUnit.SECONDS);

        assertEquals(NetworkPersistenceDrainController.State.QUIESCED, controller.state());
        assertEquals(List.of("quiesce", "flush"), component.calls);
        try (MigrationFence.MigrationLease migration = fence.tryAcquireMigration(Duration.ZERO).orElseThrow()) {
            assertTrue(fence.migrationActive());
        }
    }

    @Test
    void beginShutdownClosesProducerAdmissionBeforeTheDrainRuns() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("begin-shutdown"));
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(source, new MigrationFence(), Duration.ofSeconds(1));
        RecordingProducer producer = new RecordingProducer("producer", false);
        controller.register(new FakeComponent("manifest", source.resolve("manifest")));
        controller.registerProducer(producer);

        controller.beginShutdown();

        assertEquals(NetworkPersistenceDrainController.State.QUIESCING, controller.state());
        assertTrue(producer.calls.contains("close"));
        assertTrue(controller.tryAcquire("blocked", Duration.ZERO).isEmpty());
        controller.quiescePersistence();
        assertEquals(NetworkPersistenceDrainController.State.QUIESCED, controller.state());
    }

    @Test
    void replacementLeaseBlocksShutdownUntilTheOldOwnerHasBeenReplaced() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("replacement-lease"));
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(source, new MigrationFence(), Duration.ofSeconds(1));
        controller.register(new FakeComponent("manifest", source.resolve("manifest")));

        NetworkPersistenceDrainController.ReplacementLease lease = controller.beginReplacement("player-state-reload");

        controller.beginShutdown();
        controller.quiescePersistence();
        assertTrue(lease.active());
        assertThrows(IllegalStateException.class, controller::closePersistence);
        lease.close();
        controller.closePersistence();
        assertEquals(NetworkPersistenceDrainController.State.CLOSED, controller.state());
    }

    @Test
    void replacementRegistrationSwapsOwnershipWithoutAnExpectedOwnerGap() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("replacement-registration"));
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(source, new MigrationFence(), Duration.ofSeconds(1));
        FakeComponent first = new FakeComponent("manifest", source.resolve("manifest"));
        FakeComponent replacementComponent = new FakeComponent("manifest", source.resolve("manifest"));
        NetworkPersistenceDrainController.Registration original = controller.register(first);
        controller.expectComponent("manifest");
        NetworkPersistenceDrainController.ReplacementLease lease = controller.beginReplacement("player-state-reload");

        NetworkPersistenceDrainController.Registration replacement = controller.replace(original, replacementComponent);

        assertFalse(original.active());
        assertTrue(replacement.active());
        assertEquals(1, controller.componentCount());
        controller.healthCheckPersistence();

        assertTrue(controller.restore(replacement, original, first));
        assertTrue(original.active());
        assertFalse(replacement.active());
        assertEquals(1, controller.componentCount());
        lease.close();
        controller.closePersistence();
    }

    @Test
    void missingExpectedConfiguredComponentFailsHealthUntilItIsRegistered() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("expected-component"));
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(source, new MigrationFence(), Duration.ofSeconds(1));
        controller.expectComponent("manifest");

        assertThrows(IOException.class, controller::healthCheckPersistence);

        controller.register(new FakeComponent("manifest", source.resolve("manifest")));
        controller.healthCheckPersistence();
        assertEquals(List.of("manifest"), controller.expectedComponentOwners());
    }

    @Test
    void boundedTimeoutRunsDurableAbortBeforeFailingTheDrain() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(source, new MigrationFence(), Duration.ofMillis(50));
        controller.register(new FakeComponent("manifest", source.resolve("manifest")));
        AtomicReference<NetworkPersistenceDrainController.Lease> lease = new AtomicReference<>();
        AtomicReference<Boolean> aborted = new AtomicReference<>(false);
        controller.registerProducer(new NetworkPersistenceDrainController.Producer() {
            @Override
            public String owner() {
                return "delayed-producer";
            }

            @Override
            public void closeAdmission() {
            }

            @Override
            public CompletionStage<NetworkPersistenceDrainController.AbortResult> abortPending() {
                aborted.set(true);
                lease.get().close();
                return CompletableFuture.completedFuture(NetworkPersistenceDrainController.AbortResult.completed());
            }
        });
        lease.set(controller.acquire("stuck-until-abort"));

        controller.quiescePersistence(Duration.ofMillis(250));

        assertTrue(aborted.get());
        assertEquals(NetworkPersistenceDrainController.State.QUIESCED, controller.state());
    }

    @Test
    void delayedDurableAbortIsAwaitedAndLeavesTheControllerDegraded() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(source, new MigrationFence(), Duration.ofSeconds(1));
        controller.register(new FakeComponent("manifest", source.resolve("manifest")));
        AtomicReference<NetworkPersistenceDrainController.Lease> lease = new AtomicReference<>(controller.acquire("active-transfer"));
        controller.registerProducer(new NetworkPersistenceDrainController.Producer() {
            @Override
            public String owner() {
                return "delayed-producer";
            }

            @Override
            public void closeAdmission() {
            }

            @Override
            public CompletionStage<NetworkPersistenceDrainController.AbortResult> abortPending() {
                CompletableFuture<NetworkPersistenceDrainController.AbortResult> result = new CompletableFuture<>();
                CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(() -> {
                    lease.get().close();
                    result.complete(NetworkPersistenceDrainController.AbortResult.retained("Transfer retained for recovery"));
                });
                return result;
            }
        });

        controller.quiescePersistence(Duration.ofSeconds(1));

        assertEquals(NetworkPersistenceDrainController.State.QUIESCED, controller.state());
        assertFalse(controller.health().available());
        assertTrue(controller.health().failures().containsKey("delayed-producer"));
        assertEquals(0, controller.activeLeaseCount());
    }

    @Test
    void degradedProducerCanBeRetriedWithoutReopeningPersistenceAdmission() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("degraded-retry"));
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(source, new MigrationFence(), Duration.ofSeconds(1));
        controller.register(new FakeComponent("manifest", source.resolve("manifest")));
        AtomicReference<Boolean> retained = new AtomicReference<>(true);
        controller.registerProducer(new NetworkPersistenceDrainController.Producer() {
            @Override
            public String owner() {
                return "degraded-producer";
            }

            @Override
            public void closeAdmission() {
            }

            @Override
            public CompletionStage<NetworkPersistenceDrainController.AbortResult> abortPending() {
                return CompletableFuture.completedFuture(retained.get()
                    ? NetworkPersistenceDrainController.AbortResult.retained("retry required")
                    : NetworkPersistenceDrainController.AbortResult.completed());
            }
        });

        controller.quiescePersistence();

        assertThrows(IOException.class, () -> controller.healthCheckPersistence());
        assertTrue(controller.tryAcquire("closed", Duration.ZERO).isEmpty());
        retained.set(false);
        controller.retryDegradedPersistence(Duration.ofSeconds(1));
        controller.healthCheckPersistence();
        assertEquals(NetworkPersistenceDrainController.State.QUIESCED, controller.state());
    }

    @Test
    void failedRebindRestoresEveryComponentRoot() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        Path candidate = Files.createDirectories(temporary.resolve("candidate"));
        MigrationFence fence = new MigrationFence();
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(source, fence, Duration.ofSeconds(2));
        FakeComponent first = new FakeComponent("first", source.resolve("first"));
        FakeComponent second = new FakeComponent("second", source.resolve("second"));
        second.failAfterRebind = true;
        controller.register(first);
        controller.register(second);
        controller.quiescePersistence();

        assertThrows(IOException.class, () -> controller.rebindPersistence(candidate));

        assertEquals(source.resolve("first"), first.activePath());
        assertEquals(source.resolve("second"), second.activePath());
        assertEquals(source, controller.persistenceRoot());
    }

    @Test
    void timeoutWithoutRecoveryFailsClosedAndDoesNotClearTheLease() throws Exception {
        Path source;
        try {
            source = Files.createDirectories(temporary.resolve("source"));
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(source, new MigrationFence(), Duration.ofMillis(10));
        controller.register(new FakeComponent("manifest", source.resolve("manifest")));
        NetworkPersistenceDrainController.Lease lease = controller.acquire("never-completes");

        assertThrows(IOException.class, () -> controller.quiescePersistence(Duration.ofMillis(25)));
        assertEquals(NetworkPersistenceDrainController.State.FAILED, controller.state());
        assertEquals(1, controller.activeLeaseCount());
        lease.close();
        controller.recoverPersistence(Duration.ofSeconds(1));
        assertEquals(NetworkPersistenceDrainController.State.QUIESCED, controller.state());
    }

    @Test
    void asynchronousIdleTimeoutRetainsTheRegistrationForALaterRetry() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(source, new MigrationFence(), Duration.ofSeconds(1));
        NetworkPersistenceDrainController.Registration registration = controller.register(new FakeComponent("manifest", source.resolve("manifest")));
        NetworkPersistenceDrainController.Lease lease = controller.acquire("delayed-server-task");

        long started = System.nanoTime();
        CompletableFuture<Void> idle = controller.awaitIdle(Duration.ofMillis(50));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMillis < 500);
        assertThrows(ExecutionException.class, () -> idle.get(1, TimeUnit.SECONDS));
        assertTrue(registration.active());
        assertEquals(1, controller.componentCount());

        lease.close();
        controller.awaitIdle(Duration.ofSeconds(1)).get(1, TimeUnit.SECONDS);
        registration.close();
        assertEquals(0, controller.componentCount());
    }

    @Test
    void componentDrainFailureRetainsAuthorityUntilRecoveryAndExplicitClose() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(source, new MigrationFence(), Duration.ofSeconds(1));
        FakeComponent component = new FakeComponent("manifest", source.resolve("manifest"));
        component.failQuiesce = true;
        NetworkPersistenceDrainController.Registration registration = controller.register(component);

        assertThrows(IOException.class, controller::quiescePersistence);
        assertEquals(NetworkPersistenceDrainController.State.FAILED, controller.state());
        assertTrue(registration.active());
        assertEquals(1, controller.componentCount());

        component.failQuiesce = false;
        controller.recoverPersistence(Duration.ofSeconds(1));
        assertEquals(NetworkPersistenceDrainController.State.QUIESCED, controller.state());
        assertTrue(registration.active());

        controller.closePersistence();
        assertFalse(registration.active());
        assertEquals(0, controller.componentCount());
    }

    @Test
    void registrationHandlesUnregisterExactlyOnceAndAllowAReplacement() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(source, new MigrationFence(), Duration.ofSeconds(1));
        FakeComponent first = new FakeComponent("manifest", source.resolve("manifest"));

        NetworkPersistenceDrainController.Registration registration = controller.register(first);

        assertTrue(registration.active());
        assertEquals(1, controller.componentCount());
        assertFalse(controller.unregister(null));
        registration.close();
        registration.close();
        assertFalse(registration.active());
        assertEquals(0, controller.componentCount());

        NetworkPersistenceDrainController.Registration replacement = controller.register(new FakeComponent("manifest", source.resolve("manifest")));
        assertTrue(replacement.active());
        assertEquals(1, controller.componentCount());
        assertFalse(controller.unregister(registration));
        assertTrue(replacement.active());
        replacement.close();
        assertEquals(0, controller.componentCount());
    }

    @Test
    void producerRegistrationHandlesAreIdempotentAndDoNotLeaveAStaleProducer() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(source, new MigrationFence(), Duration.ofSeconds(1));
        NetworkPersistenceDrainController.Producer producer = new NetworkPersistenceDrainController.Producer() {
            @Override
            public String owner() {
                return "producer";
            }

            @Override
            public void closeAdmission() {
            }
        };

        NetworkPersistenceDrainController.Registration registration = controller.registerProducer(producer);

        assertTrue(registration.active());
        assertEquals(1, controller.producerCount());
        registration.close();
        registration.close();
        assertFalse(registration.active());
        assertEquals(0, controller.producerCount());
    }

    @Test
    void registrationRetirementDuringDrainIsDeferredUntilResume() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        Path candidate = Files.createDirectories(temporary.resolve("candidate"));
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(source, new MigrationFence(), Duration.ofSeconds(1));
        FakeComponent component = new FakeComponent("manifest", source.resolve("manifest"));
        NetworkPersistenceDrainController.Registration registration = controller.register(component);
        NetworkPersistenceDrainController.Lease blocker = controller.acquire("blocker");

        CompletableFuture<Void> quiesce = CompletableFuture.runAsync(() -> {
            try {
                controller.quiescePersistence(Duration.ofSeconds(1));
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        });
        waitForState(controller, NetworkPersistenceDrainController.State.QUIESCING);

        registration.close();
        assertTrue(registration.retirementPending());
        assertTrue(registration.active());
        assertEquals(1, controller.componentCount());
        blocker.close();
        quiesce.get(1, TimeUnit.SECONDS);

        controller.rebindPersistence(candidate);
        assertTrue(component.calls.contains("rebind:" + candidate.resolve("manifest").toAbsolutePath().normalize()));
        controller.resumePersistence();

        assertFalse(registration.active());
        assertEquals(0, controller.componentCount());
    }

    @Test
    void resumeFailureCompensatesAlreadyResumedComponents() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(source, new MigrationFence(), Duration.ofSeconds(1));
        FakeComponent resumed = new FakeComponent("b-resumed", source.resolve("b-resumed"));
        FakeComponent failed = new FakeComponent("a-failed", source.resolve("a-failed"));
        failed.failResume = true;
        controller.register(resumed);
        controller.register(failed);
        controller.quiescePersistence();

        assertThrows(IOException.class, controller::resumePersistence);

        assertEquals(NetworkPersistenceDrainController.State.FAILED, controller.state());
        assertTrue(resumed.calls.contains("resume"));
        assertTrue(resumed.calls.contains("quiesce"));
        assertTrue(failed.calls.contains("resume"));
        assertTrue(failed.calls.contains("quiesce"));
    }

    @Test
    void resumeFailureCompensatesAlreadyResumedProducersAndKeepsAdmissionClosed() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(source, new MigrationFence(), Duration.ofSeconds(1));
        controller.register(new FakeComponent("manifest", source.resolve("manifest")));
        RecordingProducer resumed = new RecordingProducer("a-resumed", false);
        RecordingProducer failed = new RecordingProducer("b-failed", true);
        controller.registerProducer(resumed);
        controller.registerProducer(failed);
        controller.quiescePersistence();

        assertThrows(IOException.class, controller::resumePersistence);

        assertEquals(NetworkPersistenceDrainController.State.FAILED, controller.state());
        assertTrue(resumed.calls.contains("resume"));
        assertTrue(resumed.calls.contains("close"));
        assertTrue(failed.calls.contains("resume"));
        assertTrue(failed.calls.contains("close"));
        assertTrue(controller.tryAcquire("closed", Duration.ZERO).isEmpty());
    }

    @Test
    void componentAndProducerOwnerCollisionFailsClosed() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(source, new MigrationFence(), Duration.ofSeconds(1));
        controller.register(new FakeComponent("same-owner", source.resolve("component")));

        assertThrows(IllegalArgumentException.class, () -> controller.registerProducer(new RecordingProducer("same-owner", false)));
        assertEquals(1, controller.componentCount());
        assertEquals(0, controller.producerCount());
    }

    @Test
    void registrationOwnerRemainsStableWhenCallbackOwnerChanges() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(source, new MigrationFence(), Duration.ofSeconds(1));
        FakeComponent component = new FakeComponent("stable-owner", source.resolve("stable-owner"));
        controller.register(component);
        component.owner = "mutated-owner";
        component.failHealth = true;

        NetworkPersistenceDrainController.Health health = controller.health();

        assertTrue(health.failures().containsKey("stable-owner"));
        assertFalse(health.failures().containsKey("mutated-owner"));
    }

    @Test
    void pathSynchronizerConstructorRollsBackItsComponentWhenProducerRegistrationFails() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(source, new MigrationFence(), Duration.ofSeconds(1));
        controller.registerProducer(new NetworkPersistenceDrainController.Producer() {
            @Override
            public String owner() {
                return "path-synchronizer:settings";
            }

            @Override
            public void closeAdmission() {
            }
        });
        ReSyncNetworkAgentConfig.PathPolicy policy = new ReSyncNetworkAgentConfig.PathPolicy("settings", "Settings", false, Set.of("server.properties"), ReSyncNetworkAgentConfig.ResourceConflictPolicy.NETWORK_WINS, List.of());

        assertThrows(IllegalArgumentException.class, () -> new NetworkPathSynchronizer(null, null, null, policy, source, source.resolve("path"), controller));

        assertEquals(0, controller.componentCount());
        assertEquals(1, controller.producerCount());
    }

    @Test
    void resourceSynchronizerConstructorRollsBackItsComponentWhenProducerRegistrationFails() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(source, new MigrationFence(), Duration.ofSeconds(1));
        controller.registerProducer(new NetworkPersistenceDrainController.Producer() {
            @Override
            public String owner() {
                return "resource-synchronizer";
            }

            @Override
            public void closeAdmission() {
            }
        });

        assertThrows(IllegalArgumentException.class, () -> new NetworkResourceSynchronizer(null, null, null, ReSyncNetworkAgentConfig.ResourcePolicy.disabled(), source.resolve("resource"), null, controller));

        assertEquals(0, controller.componentCount());
        assertEquals(1, controller.producerCount());
    }

    private void waitForState(NetworkPersistenceDrainController controller, NetworkPersistenceDrainController.State expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (controller.state() != expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, controller.state());
    }

    private static final class RecordingProducer implements NetworkPersistenceDrainController.Producer {
        private final String owner;
        private final boolean failResume;
        private final List<String> calls = new ArrayList<>();

        private RecordingProducer(String owner, boolean failResume) {
            this.owner = owner;
            this.failResume = failResume;
        }

        @Override
        public String owner() {
            return owner;
        }

        @Override
        public void closeAdmission() {
            calls.add("close");
        }

        @Override
        public void resumeAdmission() {
            calls.add("resume");
            if (failResume) {
                throw new IllegalStateException("producer resume failed");
            }
        }
    }

    private static final class FakeComponent implements NetworkPersistenceDrainController.Component {
        private String owner;
        private final List<String> calls = new ArrayList<>();
        private Path path;
        private boolean failAfterRebind;
        private boolean failQuiesce;
        private boolean failResume;
        private boolean failHealth;

        private FakeComponent(String owner, Path path) {
            this.owner = owner;
            this.path = path.toAbsolutePath().normalize();
            try {
                Files.createDirectories(this.path);
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        }

        @Override
        public String owner() {
            return owner;
        }

        @Override
        public Path activePath() {
            return path;
        }

        @Override
        public void flush() {
            calls.add("flush");
        }

        @Override
        public void quiesce() {
            calls.add("quiesce");
            if (failQuiesce) {
                throw new IllegalStateException("component quiesce failed");
            }
        }

        @Override
        public void resume() {
            calls.add("resume");
            if (failResume) {
                throw new IllegalStateException("component resume failed");
            }
        }

        @Override
        public void validateRebind(Path candidateNetworkRoot) throws IOException {
            Files.createDirectories(candidateNetworkRoot.resolve(owner));
        }

        @Override
        public void rebind(Path candidateNetworkRoot) throws IOException {
            path = candidateNetworkRoot.resolve(owner).toAbsolutePath().normalize();
            calls.add("rebind:" + path);
            if (failAfterRebind && candidateNetworkRoot.getFileName().toString().equals("candidate")) {
                throw new IOException("component commit failed");
            }
        }

        @Override
        public void healthCheck() {
            if (failHealth) {
                throw new IllegalStateException("component health failed");
            }
        }
    }
}
