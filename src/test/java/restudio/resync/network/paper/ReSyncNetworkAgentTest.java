package restudio.resync.network.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import restudio.resync.migration.MigrationFence;
import restudio.resync.network.NetworkTransferStatus;
import restudio.resync.network.PlayerStateSnapshot;
import restudio.resync.network.PlayerTransfer;
import restudio.resync.network.paper.state.NetworkPlayerLocationPolicy;
import restudio.resync.network.paper.state.NetworkPlayerStateCodec;
import restudio.resync.network.paper.state.NetworkPlayerStateConfig;
import restudio.resync.network.paper.state.NetworkPlayerStateCoordinator;
import restudio.resync.network.paper.state.NetworkPlayerStateProfile;
import restudio.resync.ReSync;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncNetworkAgentTest {
    @TempDir
    Path temporary;

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void activeAuthenticatedCloseNotifiesOnceWhilePreAuthenticationStaleAndDuplicateClosesStaySilent() throws Exception {
        MockBukkit.mock();
        ReSyncNetworkAgent agent = new ReSyncNetworkAgent(null, config(temporary.resolve("disconnect-callback")),
            new MigrationFence());
        AtomicInteger disconnects = new AtomicInteger();
        agent.addListener(new ReSyncNetworkAgent.Listener() {
            @Override
            public void onDisconnected() {
                disconnects.incrementAndGet();
            }
        });
        agent.prepareForShutdown();

        Object activeClient = client(agent);
        Object staleClient = client(agent);
        setClient(agent, activeClient);
        closeClient(activeClient);
        assertEquals(0, disconnects.get());

        authorized(agent).set(true);
        closeClient(staleClient);
        assertEquals(0, disconnects.get());
        assertTrue(authorized(agent).get());

        presence(agent).put("node", new Object());
        closeClient(activeClient);
        closeClient(activeClient);
        assertEquals(1, disconnects.get());
        assertFalse(authorized(agent).get());
        assertTrue(presence(agent).isEmpty());

        assertTrue(agent.shutdownAfterPreparation(() -> CompletableFuture.completedFuture(null))
            .toCompletableFuture().get(5, TimeUnit.SECONDS).completed());
    }

    @Test
    void failedActiveLeaseDrainRetainsAuthorityForALaterRetry() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("network"));
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(root, new MigrationFence(), Duration.ofSeconds(1));
        NetworkPersistenceDrainController.Registration registration = controller.register(new Component("manifest", root.resolve("manifest")));
        NetworkPersistenceDrainController.Lease lease = controller.acquire("active-network-work");

        assertThrows(IOException.class, () -> ReSyncNetworkAgent.drainPersistenceForShutdown(controller, Duration.ofMillis(25)));
        assertEquals(NetworkPersistenceDrainController.State.FAILED, controller.state());
        assertTrue(registration.active());
        assertEquals(1, controller.componentCount());

        lease.close();
        ReSyncNetworkAgent.drainPersistenceForShutdown(controller, Duration.ofSeconds(1));
        assertEquals(NetworkPersistenceDrainController.State.QUIESCED, controller.state());
        assertTrue(registration.active());
    }

    @Test
    void failedComponentDrainRetainsAuthorityUntilTheComponentRecovers() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("network"));
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(root, new MigrationFence(), Duration.ofSeconds(1));
        Component component = new Component("manifest", root.resolve("manifest"));
        component.failQuiesce = true;
        NetworkPersistenceDrainController.Registration registration = controller.register(component);

        assertThrows(IOException.class, () -> ReSyncNetworkAgent.drainPersistenceForShutdown(controller, Duration.ofSeconds(1)));
        assertEquals(NetworkPersistenceDrainController.State.FAILED, controller.state());
        assertTrue(registration.active());

        component.failQuiesce = false;
        ReSyncNetworkAgent.drainPersistenceForShutdown(controller, Duration.ofSeconds(1));
        assertEquals(NetworkPersistenceDrainController.State.QUIESCED, controller.state());
        controller.closePersistence();
        assertFalse(registration.active());
    }

    @Test
    void dormantAgentShutsDownThroughACompletionStageAndClosesOnlyAfterDrain() throws Exception {
        Path dataRoot = Files.createDirectories(temporary.resolve("dormant"));
        ReSyncNetworkAgentConfig config = new ReSyncNetworkAgentConfig(
            false,
            ReSyncNetworkAgentConfig.ChatPolicy.disabled(),
            ReSyncNetworkAgentConfig.ResourcePolicy.disabled(),
            List.of(), "network-1", "source-1", "Test", "", "", "", 0,
            1_048_576, 500_000, 100, 100,
            ReSyncNetworkAgentConfig.Tls.disabled(), dataRoot.resolve("network/node.credential"));
        ReSyncNetworkAgent agent = new ReSyncNetworkAgent(null, config, new MigrationFence());

        ReSyncNetworkAgent.ShutdownResult result = agent.shutdown().toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertTrue(result.completed());
        assertEquals(NetworkPersistenceDrainController.State.CLOSED, agent.persistenceDrain().state());
        assertEquals(0, agent.persistenceDrain().componentCount());
        assertEquals(0, agent.persistenceDrain().producerCount());
    }

    @Test
    void injectedPlayerDataAdmissionDrainsAsynchronouslyWithinABoundedWait() throws Exception {
        PaperPlayerDataMutationAdmission.clearSharedInstallation(PaperPlayerDataMutationAdmission.sharedInstallation());
        Path world = Files.createDirectories(temporary.resolve("injected-drain-world"));
        Files.createDirectories(world.resolve("playerdata"));
        PaperPlayerDataMutationAdmission admission = new PaperPlayerDataMutationAdmission(List.of(world), temporary);
        PaperPlayerDataMutationAdmission.Installation installation =
            PaperPlayerDataMutationAdmission.installShared(admission);
        ReSyncNetworkAgent agent = new ReSyncNetworkAgent(null, config(temporary.resolve("injected-drain")),
            new MigrationFence(), admission);
        agent.prepareForShutdown();
        admission.resume();
        PaperPlayerDataMutationAdmission.Lease blocker = admission.acquire("bounded-drain-blocker");
        try {
            CompletableFuture<ReSyncNetworkAgent.ShutdownResult> shutdown = agent.shutdownAfterPreparation(
                () -> CompletableFuture.completedFuture(null)).toCompletableFuture();

            assertThrows(java.util.concurrent.TimeoutException.class,
                () -> shutdown.get(50, TimeUnit.MILLISECONDS));
            blocker.close();
            assertTrue(shutdown.get(2, TimeUnit.SECONDS).completed());
            assertEquals(PaperPlayerDataMutationAdmission.State.QUIESCED, admission.state());
        } finally {
            blocker.close();
            admission.close();
            PaperPlayerDataMutationAdmission.clearSharedInstallation(installation);
        }
    }

    @Test
    void shutdownFinalizesChildrenBeforeTheAgentClosesItsPersistenceController() throws Exception {
        Path dataRoot = Files.createDirectories(temporary.resolve("ordered-shutdown"));
        ReSyncNetworkAgentConfig config = new ReSyncNetworkAgentConfig(
            false,
            ReSyncNetworkAgentConfig.ChatPolicy.disabled(),
            ReSyncNetworkAgentConfig.ResourcePolicy.disabled(),
            List.of(), "", "", "", "", "", "", 0,
            1_048_576, 500_000, 100, 100,
            ReSyncNetworkAgentConfig.Tls.disabled(), dataRoot.resolve("network/node.credential"));
        ReSyncNetworkAgent agent = new ReSyncNetworkAgent(null, config, new MigrationFence());
        AtomicBoolean childSawFrozenController = new AtomicBoolean();

        ReSyncNetworkAgent.ShutdownResult result = agent.shutdown(() -> {
            childSawFrozenController.set(agent.persistenceDrain().state() == NetworkPersistenceDrainController.State.QUIESCED
                && agent.persistenceDrain().componentCount() > 0
                && agent.persistenceDrain().producerCount() > 0);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }).toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertTrue(result.completed());
        assertTrue(childSawFrozenController.get());
        assertEquals(NetworkPersistenceDrainController.State.CLOSED, agent.persistenceDrain().state());
    }

    @Test
    void asyncShutdownCannotStartBeforeTheMainThreadPreparationPhase() throws Exception {
        Path dataRoot = Files.createDirectories(temporary.resolve("requires-preparation"));
        ReSyncNetworkAgentConfig config = new ReSyncNetworkAgentConfig(
            false,
            ReSyncNetworkAgentConfig.ChatPolicy.disabled(),
            ReSyncNetworkAgentConfig.ResourcePolicy.disabled(),
            List.of(), "", "", "", "", "", "", 0,
            1_048_576, 500_000, 100, 100,
            ReSyncNetworkAgentConfig.Tls.disabled(), dataRoot.resolve("network/node.credential"));
        ReSyncNetworkAgent agent = new ReSyncNetworkAgent(null, config, new MigrationFence());

        ReSyncNetworkAgent.ShutdownResult result = agent.shutdownAfterPreparation(() -> java.util.concurrent.CompletableFuture.completedFuture(null))
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertFalse(result.completed());
        assertEquals(NetworkPersistenceDrainController.State.OPEN, result.state());
    }

    @Test
    void transferHandlerSwapKeepsThePreviousAuthorityUntilCommit() throws Exception {
        Path dataRoot = Files.createDirectories(temporary.resolve("handler-swap"));
        ReSyncNetworkAgent agent = new ReSyncNetworkAgent(null, config(dataRoot), new MigrationFence());
        ReSyncNetworkAgent.TransferHandler previous = new NoopTransferHandler();
        ReSyncNetworkAgent.TransferHandler replacement = new NoopTransferHandler();
        Field handlerField = ReSyncNetworkAgent.class.getDeclaredField("transferHandler");
        handlerField.setAccessible(true);

        agent.setTransferHandler(previous, false);
        ReSyncNetworkAgent.TransferHandlerSwap staged = agent.stageTransferHandler(replacement);
        assertEquals(previous, handlerField.get(agent));

        agent.rollbackTransferHandler(staged);
        assertEquals(previous, handlerField.get(agent));

        staged = agent.stageTransferHandler(replacement);
        agent.commitTransferHandler(staged, false);
        assertEquals(replacement, handlerField.get(agent));
        agent.shutdown().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    void transferReplacementFenceHoldsAnArrivalUntilTheReplacementIsCommitted() throws Exception {
        Path dataRoot = Files.createDirectories(temporary.resolve("handler-fence"));
        ReSyncNetworkAgent agent = new ReSyncNetworkAgent(null, config(dataRoot), new MigrationFence());
        AtomicBoolean replacementObserved = new AtomicBoolean();
        ReSyncNetworkAgent.TransferHandler previous = new NoopTransferHandler();
        ReSyncNetworkAgent.TransferHandler replacement = new NoopTransferHandler() {
            @Override
            public void recovering(PlayerTransfer ignored, boolean source) {
                replacementObserved.set(true);
            }
        };
        agent.setTransferHandler(previous, false);
        ReSyncNetworkAgent.TransferReplacementFence fence = agent.tryBeginTransferReplacement().orElseThrow();
        ReSyncNetworkAgent.TransferHandlerSwap staged = agent.stageTransferHandler(replacement);
        agent.commitTransferHandler(staged, false);
        PlayerTransfer transfer = transfer(agent.nodeId(), NetworkTransferStatus.ABORTED);
        var publish = ReSyncNetworkAgent.class.getDeclaredMethod("publishTransfer", PlayerTransfer.class);
        publish.setAccessible(true);
        publish.invoke(agent, transfer);

        assertTrue(active(agent).isEmpty());
        fence.commit();
        fence.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertTrue(active(agent).containsKey(transfer.transferId()));
        assertTrue(replacementObserved.get());
        agent.shutdown().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    void transferReplacementFenceReplaysHeldAndBoundaryArrivalsInOrder() throws Exception {
        Path dataRoot = Files.createDirectories(temporary.resolve("handler-fence-order"));
        ReSyncNetworkAgent agent = new ReSyncNetworkAgent(null, config(dataRoot), new MigrationFence());
        List<String> callbacks = new CopyOnWriteArrayList<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        PlayerTransfer first = transfer(agent.nodeId(), NetworkTransferStatus.ABORTED);
        PlayerTransfer second = new PlayerTransfer("boundary-transfer", first.networkId(), UUID.randomUUID(), first.sourceNodeId(), first.targetNodeId(),
            first.fenceEpoch(), NetworkTransferStatus.ABORTED, first.snapshotId(), first.failure(), first.deadline(), first.createdAt(), first.updatedAt());
        AtomicBoolean previousObserved = new AtomicBoolean();
        ReSyncNetworkAgent.TransferHandler previous = new NoopTransferHandler() {
            @Override
            public void recovering(PlayerTransfer ignored, boolean source) {
                previousObserved.set(true);
            }
        };
        ReSyncNetworkAgent.TransferHandler replacement = new NoopTransferHandler() {
            @Override
            public void recovering(PlayerTransfer transfer, boolean source) {
                callbacks.add(transfer.transferId());
                if (transfer.transferId().equals(first.transferId())) {
                    firstEntered.countDown();
                    try {
                        releaseFirst.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                }
            }
        };
        agent.setTransferHandler(previous, false);
        ReSyncNetworkAgent.TransferReplacementFence fence = agent.tryBeginTransferReplacement().orElseThrow();
        ReSyncNetworkAgent.TransferHandlerSwap staged = agent.stageTransferHandler(replacement);
        agent.commitTransferHandler(staged, false);
        var publish = ReSyncNetworkAgent.class.getDeclaredMethod("publishTransfer", PlayerTransfer.class);
        publish.setAccessible(true);
        publish.invoke(agent, first);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var commit = executor.submit(fence::commit);
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
            publish.invoke(agent, second);
            assertEquals(List.of(first.transferId()), callbacks);
            releaseFirst.countDown();
            commit.get(2, TimeUnit.SECONDS);
            fence.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }

        assertEquals(List.of(first.transferId(), second.transferId()), callbacks);
        assertFalse(previousObserved.get());
        agent.shutdown().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    void concurrentTransferPublishersPersistAndPublishTheSameFinalTransfer() throws Exception {
        Path dataRoot = Files.createDirectories(temporary.resolve("ordered-publish"));
        ReSyncNetworkAgent agent = new ReSyncNetworkAgent(null, config(dataRoot), new MigrationFence());
        var publish = ReSyncNetworkAgent.class.getDeclaredMethod("publishTransfer", PlayerTransfer.class);
        publish.setAccessible(true);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 64; round++) {
                long now = System.currentTimeMillis() + round;
                PlayerTransfer older = new PlayerTransfer("ordered-publish", agent.networkId(), UUID.randomUUID(), agent.nodeId(), "target-1",
                    4, NetworkTransferStatus.SOURCE_LEASED, "snapshot-1", "", now + 60_000, now, now);
                PlayerTransfer newer = new PlayerTransfer("ordered-publish", agent.networkId(), older.playerId(), agent.nodeId(), "target-1",
                    4, NetworkTransferStatus.ABORTED, "snapshot-1", "", now + 60_000, now, now + 1);
                CyclicBarrier start = new CyclicBarrier(3);
                var first = executor.submit(() -> publishAfterBarrier(publish, agent, older, start));
                var second = executor.submit(() -> publishAfterBarrier(publish, agent, newer, start));
                start.await(2, TimeUnit.SECONDS);
                first.get(2, TimeUnit.SECONDS);
                second.get(2, TimeUnit.SECONDS);
                PlayerTransfer activeTransfer = active(agent).get("ordered-publish");
                PlayerTransfer persistedTransfer = recovery(agent).snapshot().stream()
                    .filter(value -> value.transferId().equals("ordered-publish"))
                    .findFirst().orElseThrow();
                assertEquals(activeTransfer, persistedTransfer);
            }
        } finally {
            executor.shutdownNow();
        }
        agent.shutdown().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    void transferReplacementCommitIsBoundedAndReplaysAStableFifoBacklogWhileLeaseRemainsHeld() throws Exception {
        MockBukkit.mock();
        assertTrue(Bukkit.isPrimaryThread());
        Path dataRoot = Files.createDirectories(temporary.resolve("bounded-transfer-fence"));
        ReSyncNetworkAgent agent = new ReSyncNetworkAgent(null, config(dataRoot), new MigrationFence());
        List<String> callbacks = new CopyOnWriteArrayList<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        PlayerTransfer first = transferWithId(agent, "bounded-0000", NetworkTransferStatus.ABORTED, 0);
        ReSyncNetworkAgent.TransferHandler replacement = new NoopTransferHandler() {
            @Override
            public void recovering(PlayerTransfer transfer, boolean source) {
                callbacks.add(transfer.transferId());
                if (transfer.transferId().equals(first.transferId())) {
                    firstEntered.countDown();
                    try {
                        releaseFirst.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                }
            }
        };
        agent.setTransferHandler(replacement, false);
        NetworkPersistenceDrainController.ReplacementLease lease = agent.persistenceDrain().beginReplacement("bounded-transfer-fence");
        ReSyncNetworkAgent.TransferReplacementFence fence = agent.tryBeginTransferReplacement().orElseThrow();
        java.lang.reflect.Method publish = ReSyncNetworkAgent.class.getDeclaredMethod("publishTransfer", PlayerTransfer.class);
        publish.setAccessible(true);
        publish.invoke(agent, first);

        long started = System.nanoTime();
        fence.commit();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(elapsedMillis < 500, "main-thread transfer fence commit was not bounded: " + elapsedMillis + "ms");
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
        assertTrue(lease.active());

        List<String> expected = new java.util.ArrayList<>();
        expected.add(first.transferId());
        for (int index = 1; index <= 700; index++) {
            PlayerTransfer transfer = transferWithId(agent, "bounded-" + String.format("%04d", index), NetworkTransferStatus.ABORTED, index);
            expected.add(transfer.transferId());
            publish.invoke(agent, transfer);
        }
        ReSyncNetworkAgent.TransferAdmissionHealth health = agent.transferAdmissionHealth();
        assertTrue(health.blocked());
        assertTrue(health.draining());
        assertTrue(health.queuedEvents() <= 1_024);
        assertTrue(health.queuedBytes() <= 4L * 1024L * 1024L);

        CompletionStage<Void> leaseRelease = fence.completion().thenRun(lease::close);
        assertTrue(lease.active());
        releaseFirst.countDown();
        fence.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
        leaseRelease.toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertFalse(lease.active());
        assertEquals(expected, callbacks);
        assertFalse(agent.transferAdmissionHealth().blocked());
        agent.shutdown().toCompletableFuture().get(3, TimeUnit.SECONDS);
    }

    @Test
    void transferReplacementOverflowRetainsDurableRecoveryAndKeepsAdmissionFailClosed() throws Exception {
        Path dataRoot = Files.createDirectories(temporary.resolve("overflow-transfer-fence"));
        ReSyncNetworkAgent agent = new ReSyncNetworkAgent(null, config(dataRoot), new MigrationFence());
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<String> callbacks = new CopyOnWriteArrayList<>();
        PlayerTransfer first = transferWithId(agent, "overflow-0000", NetworkTransferStatus.ABORTED, 0);
        agent.setTransferHandler(new NoopTransferHandler() {
            @Override
            public void recovering(PlayerTransfer transfer, boolean source) {
                callbacks.add(transfer.transferId());
                if (transfer.transferId().equals(first.transferId())) {
                    firstEntered.countDown();
                    try {
                        releaseFirst.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                }
            }
        }, false);
        ReSyncNetworkAgent.TransferReplacementFence fence = agent.tryBeginTransferReplacement().orElseThrow();
        java.lang.reflect.Method publish = ReSyncNetworkAgent.class.getDeclaredMethod("publishTransfer", PlayerTransfer.class);
        publish.setAccessible(true);
        publish.invoke(agent, first);
        fence.commit();
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS));

        InvocationTargetException overflow = null;
        for (int index = 1; index <= 2_048; index++) {
            try {
                publish.invoke(agent, transferWithId(agent, "overflow-" + String.format("%04d", index), NetworkTransferStatus.ABORTED, index));
            } catch (InvocationTargetException exception) {
                overflow = exception;
                break;
            }
        }
        assertTrue(overflow != null);
        assertTrue(overflow.getCause() instanceof IllegalStateException);
        ReSyncNetworkAgent.TransferAdmissionHealth health = agent.transferAdmissionHealth();
        assertTrue(health.blocked());
        assertTrue(health.backpressured());
        assertTrue(health.queuedEvents() <= 1_024);
        assertTrue(health.queuedBytes() <= 4L * 1024L * 1024L);
        assertTrue(health.overflowedTransfers() <= 1_024);
        assertFalse(health.diagnostic().isBlank());
        assertTrue(recovery(agent).snapshot().size() <= 1_024);

        releaseFirst.countDown();
        Thread.sleep(100);
        assertFalse(fence.completion().toCompletableFuture().isDone());
        assertTrue(agent.transferAdmissionHealth().blocked());

        agent.persistenceDrain().closePersistence();
        ReSyncNetworkAgent recovered = new ReSyncNetworkAgent(null, config(dataRoot), new MigrationFence());
        List<String> recoveredCallbacks = new CopyOnWriteArrayList<>();
        recovered.setTransferHandler(new NoopTransferHandler() {
            @Override
            public void recovering(PlayerTransfer transfer, boolean source) {
                recoveredCallbacks.add(transfer.transferId());
            }
        });
        int persisted = recovery(recovered).snapshot().size();
        recovered.resumeTransferHandler();
        assertEquals(persisted, recoveredCallbacks.size());
        recovered.shutdown().toCompletableFuture().get(3, TimeUnit.SECONDS);
    }

    private static void publishAfterBarrier(java.lang.reflect.Method publish, ReSyncNetworkAgent agent,
                                             PlayerTransfer transfer, CyclicBarrier start) {
        try {
            start.await(2, TimeUnit.SECONDS);
            publish.invoke(agent, transfer);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static PlayerTransfer transferWithId(ReSyncNetworkAgent agent, String transferId,
                                                 NetworkTransferStatus status, long offset) {
        long now = System.currentTimeMillis() + offset;
        return new PlayerTransfer(transferId, agent.networkId(), UUID.randomUUID(), agent.nodeId(), "target-1", 4,
            status, "snapshot-1", "", now + 60_000, now, now);
    }

    @Test
    void asynchronousFinalizerRunsAfterMainThreadPreparation() throws Exception {
        MockBukkit.mock();
        Path dataRoot = Files.createDirectories(temporary.resolve("async-finalizer"));
        ReSyncNetworkAgentConfig config = new ReSyncNetworkAgentConfig(
            false,
            ReSyncNetworkAgentConfig.ChatPolicy.disabled(),
            ReSyncNetworkAgentConfig.ResourcePolicy.disabled(),
            List.of(), "", "", "", "", "", "", 0,
            1_048_576, 500_000, 100, 100,
            ReSyncNetworkAgentConfig.Tls.disabled(), dataRoot.resolve("network/node.credential"));
        ReSyncNetworkAgent agent = new ReSyncNetworkAgent(null, config, new MigrationFence());
        AtomicBoolean finalizerOnPrimaryThread = new AtomicBoolean(true);

        ReSyncNetworkAgent.ShutdownResult result = agent.shutdown(() -> {
            finalizerOnPrimaryThread.set(Bukkit.isPrimaryThread());
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }).toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertTrue(result.completed());
        assertFalse(finalizerOnPrimaryThread.get());
    }

    @Test
    void primaryPreparationRestoresAnActiveSourceTransferBeforeTheSchedulerCanRetire() throws Exception {
        MockBukkit.mock();
        Path dataRoot = Files.createDirectories(temporary.resolve("active-source-transfer"));
        ReSyncNetworkAgent agent = new ReSyncNetworkAgent(null, config(dataRoot), new MigrationFence());
        PlayerTransfer transfer = transfer(agent.nodeId(), NetworkTransferStatus.SOURCE_LEASED);
        AtomicBoolean restoredOnPrimary = new AtomicBoolean();
        agent.setTransferHandler(new NoopTransferHandler() {
            @Override
            public java.util.concurrent.CompletionStage<Void> abortForShutdown(PlayerTransfer ignored, PlayerStateSnapshot snapshot) {
                restoredOnPrimary.set(Bukkit.isPrimaryThread());
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        });
        active(agent).put(transfer.transferId(), transfer);
        recovery(agent).remember(transfer);

        agent.prepareForShutdown();

        assertTrue(restoredOnPrimary.get());
        assertTrue(recovery(agent).snapshot().isEmpty());
        assertTrue(active(agent).isEmpty());
    }

    @Test
    void primaryPreparationRestoresARealSourcePlayerWithoutSchedulingAfterDisable() throws Exception {
        MockBukkit.mock();
        TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
        Path dataRoot = plugin.getDataFolder().toPath();
        Files.createDirectories(dataRoot.resolve("network"));
        ReSyncNetworkAgent agent = new ReSyncNetworkAgent(plugin, config(dataRoot), new MigrationFence());
        NetworkPlayerStateConfig stateConfig = new NetworkPlayerStateConfig(NetworkPlayerStateProfile.CUSTOM,
            "realm", "source-1", false, false, false, false, true, false, false, false, false, false, false,
            NetworkPlayerLocationPolicy.NEVER, java.util.Set.of());
        NetworkPlayerStateCoordinator coordinator = new NetworkPlayerStateCoordinator(plugin, stateConfig, agent.persistenceDrain());
        Player player = MockBukkit.getMock().addPlayer();
        PlayerTransfer transfer = new PlayerTransfer("real-source-transfer", "network-1", player.getUniqueId(), "source-1", "target-1",
            4, NetworkTransferStatus.SOURCE_LEASED, "snapshot-1", "", System.currentTimeMillis() + 60_000,
            System.currentTimeMillis(), System.currentTimeMillis());
        NetworkPlayerStateCodec.Captured captured = NetworkPlayerStateCodec.capture(player, stateConfig);
        agent.setTransferHandler(coordinator);
        sourceTransfers(coordinator).add(transfer.transferId());
        sourceStates(coordinator).put(transfer.transferId(), captured);
        active(agent).put(transfer.transferId(), transfer);
        recovery(agent).remember(transfer);

        agent.prepareForShutdown();
        ReSyncNetworkAgent.ShutdownResult result = agent.shutdownAfterPreparation(() -> java.util.concurrent.CompletableFuture.completedFuture(null))
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertTrue(result.completed());
        assertTrue(active(agent).isEmpty());
        assertTrue(recovery(agent).snapshot().isEmpty());
        assertTrue(sourceTransfers(coordinator).isEmpty());
        assertTrue(MockBukkit.getMock().getScheduler().getPendingTasks().isEmpty());
    }

    @Test
    void shutdownRestoresAnInFlightSourceBeforeForgettingItsRecoveryRecord() throws Exception {
        Path dataRoot = Files.createDirectories(temporary.resolve("source-transfer-shutdown"));
        ReSyncNetworkAgent agent = new ReSyncNetworkAgent(null, config(dataRoot), new MigrationFence());
        NetworkTransferRecoveryStore recovery = recovery(agent);
        PlayerTransfer transfer = transfer(agent.nodeId(), NetworkTransferStatus.ABORTED);
        AtomicBoolean callbackSawRecovery = new AtomicBoolean();
        agent.setTransferHandler(new ReSyncNetworkAgent.TransferHandler() {
            @Override
            public java.util.concurrent.CompletionStage<PlayerStateSnapshot> capture(PlayerTransfer ignored) {
                return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException());
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> prepare(PlayerTransfer ignored, PlayerStateSnapshot snapshot) {
                return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException());
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> apply(PlayerTransfer ignored, PlayerStateSnapshot snapshot) {
                return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException());
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> aborted(PlayerTransfer ignored, PlayerStateSnapshot snapshot) {
                callbackSawRecovery.set(recovery.snapshot().stream().anyMatch(value -> value.transferId().equals(transfer.transferId())));
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        });
        active(agent).put(transfer.transferId(), transfer);
        recovery.remember(transfer);

        ReSyncNetworkAgent.ShutdownResult result = agent.shutdown().toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertTrue(result.completed());
        assertTrue(callbackSawRecovery.get());
        assertTrue(recovery.snapshot().isEmpty());
        assertTrue(active(agent).isEmpty());
    }

    @Test
    void unavailableTransferHandlerRetainsRecoveryUntilAHandlerRetryCompletes() throws Exception {
        Path dataRoot = Files.createDirectories(temporary.resolve("unavailable-transfer-handler"));
        ReSyncNetworkAgent agent = new ReSyncNetworkAgent(null, config(dataRoot), new MigrationFence());
        NetworkTransferRecoveryStore recovery = recovery(agent);
        PlayerTransfer transfer = transfer(agent.nodeId(), NetworkTransferStatus.ABORTED);
        active(agent).put(transfer.transferId(), transfer);
        recovery.remember(transfer);

        ReSyncNetworkAgent.ShutdownResult first = agent.shutdown().toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertFalse(first.completed());
        assertEquals(NetworkPersistenceDrainController.State.QUIESCED, first.state());
        assertTrue(recovery.snapshot().stream().anyMatch(value -> value.transferId().equals(transfer.transferId())));
        assertTrue(active(agent).containsKey(transfer.transferId()));

        agent.setTransferHandler(new NoopTransferHandler());

        ReSyncNetworkAgent.ShutdownResult retry = agent.shutdownAfterPreparation(() -> java.util.concurrent.CompletableFuture.completedFuture(null))
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertTrue(retry.completed());
        assertTrue(active(agent).isEmpty());
    }

    private static ReSyncNetworkAgentConfig config(Path dataRoot) {
        return new ReSyncNetworkAgentConfig(
            false,
            ReSyncNetworkAgentConfig.ChatPolicy.disabled(),
            ReSyncNetworkAgentConfig.ResourcePolicy.disabled(),
            List.of(), "network-1", "source-1", "Test", "", "", "", 0,
            1_048_576, 500_000, 100, 100,
            ReSyncNetworkAgentConfig.Tls.disabled(), dataRoot.resolve("network/node.credential"));
    }

    private static Object client(ReSyncNetworkAgent agent) throws Exception {
        Class<?> type = Class.forName("restudio.resync.network.paper.ReSyncNetworkAgent$Client");
        var constructor = type.getDeclaredConstructor(ReSyncNetworkAgent.class, URI.class, Map.class);
        constructor.setAccessible(true);
        return constructor.newInstance(agent, URI.create("ws://localhost"), Map.of());
    }

    private static void setClient(ReSyncNetworkAgent agent, Object client) throws Exception {
        Field field = ReSyncNetworkAgent.class.getDeclaredField("client");
        field.setAccessible(true);
        field.set(agent, client);
    }

    private static AtomicBoolean authorized(ReSyncNetworkAgent agent) throws Exception {
        Field field = ReSyncNetworkAgent.class.getDeclaredField("authorized");
        field.setAccessible(true);
        return (AtomicBoolean) field.get(agent);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> presence(ReSyncNetworkAgent agent) throws Exception {
        Field field = ReSyncNetworkAgent.class.getDeclaredField("presence");
        field.setAccessible(true);
        return (Map<Object, Object>) field.get(agent);
    }

    private static void closeClient(Object client) throws Exception {
        var method = client.getClass().getDeclaredMethod("onClose", int.class, String.class, boolean.class);
        method.setAccessible(true);
        method.invoke(client, 1000, "test", true);
    }

    private static PlayerTransfer transfer(String sourceNodeId, NetworkTransferStatus status) {
        long now = System.currentTimeMillis();
        return new PlayerTransfer("transfer-shutdown", "network-1", UUID.randomUUID(), sourceNodeId, "target-1", 4,
            status, "snapshot-1", "", now + 60_000, now, now);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, PlayerTransfer> active(ReSyncNetworkAgent agent) throws Exception {
        Field field = ReSyncNetworkAgent.class.getDeclaredField("activeTransfers");
        field.setAccessible(true);
        return (Map<String, PlayerTransfer>) field.get(agent);
    }

    private static NetworkTransferRecoveryStore recovery(ReSyncNetworkAgent agent) throws Exception {
        Field field = ReSyncNetworkAgent.class.getDeclaredField("transferRecovery");
        field.setAccessible(true);
        return (NetworkTransferRecoveryStore) field.get(agent);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<String> sourceTransfers(NetworkPlayerStateCoordinator coordinator) throws Exception {
        Field field = NetworkPlayerStateCoordinator.class.getDeclaredField("sourceTransfers");
        field.setAccessible(true);
        return (java.util.Set<String>) field.get(coordinator);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, NetworkPlayerStateCodec.Captured> sourceStates(NetworkPlayerStateCoordinator coordinator) throws Exception {
        Field field = NetworkPlayerStateCoordinator.class.getDeclaredField("sourceStates");
        field.setAccessible(true);
        return (Map<String, NetworkPlayerStateCodec.Captured>) field.get(coordinator);
    }

    private static class NoopTransferHandler implements ReSyncNetworkAgent.TransferHandler {
        @Override
        public java.util.concurrent.CompletionStage<PlayerStateSnapshot> capture(PlayerTransfer transfer) {
            return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> prepare(PlayerTransfer transfer, PlayerStateSnapshot snapshot) {
            return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> apply(PlayerTransfer transfer, PlayerStateSnapshot snapshot) {
            return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }

    public static class TestReSync extends ReSync {
        @Override
        public void onEnable() {
        }

        @Override
        public void onDisable() {
        }

        @Override
        public ReSyncNetworkAgent getNetworkAgent() {
            return null;
        }
    }

    private static final class Component implements NetworkPersistenceDrainController.Component {
        private final String owner;
        private final Path path;
        private boolean failQuiesce;

        private Component(String owner, Path path) throws IOException {
            this.owner = owner;
            this.path = path.toAbsolutePath().normalize();
            Files.createDirectories(this.path);
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
        }

        @Override
        public void quiesce() throws IOException {
            if (failQuiesce) {
                throw new IOException("component quiesce failed");
            }
        }

        @Override
        public void resume() {
        }

        @Override
        public void validateRebind(Path candidateNetworkRoot) {
        }

        @Override
        public void rebind(Path candidateNetworkRoot) {
        }

        @Override
        public void healthCheck() {
        }
    }
}
