package restudio.resync.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldExternalPersistenceCapabilityTest {
    @TempDir
    Path temporary;

    @Test
    void defaultCapabilityIsUnavailableButNormalOperationsRemainAdmitted() throws Exception {
        WorldExternalPersistenceCapability capability = new WorldExternalPersistenceCapability();
        Path worldRoot = Files.createDirectories(temporary.resolve("world"));

        assertFalse(capability.available());
        assertEquals(WorldExternalPersistenceCapability.State.UNAVAILABLE, capability.state());
        assertEquals(0, capability.health().registeredWorlds());
        WorldExternalPersistenceCapability.MutationLease normal = capability.acquireMutation("setDifficulty", "world");
        assertEquals(1, capability.activeOperationCount());
        normal.close();
        assertThrows(WorldExternalPersistenceCapability.OperationRejectedException.class,
            () -> capability.acquireReplacementMutation("setDifficulty", "world"));
        assertThrows(IOException.class, capability::healthCheck);
        assertThrows(IOException.class, () -> capability.rebind(Map.of("world", worldRoot)));
    }

    @Test
    void rootsRequireExactIdentityAndNamedDirectory() throws Exception {
        Path worldRoot = Files.createDirectories(temporary.resolve("world"));
        WorldExternalPersistenceCapability capability = new WorldExternalPersistenceCapability();
        capability.registerWorld("world", worldRoot);

        assertEquals(worldRoot.toAbsolutePath().normalize(), capability.roots().get("world").root());
        assertThrows(WorldExternalPersistenceCapability.PathRejectedException.class,
            () -> capability.registerWorld("other", worldRoot));
        assertThrows(WorldExternalPersistenceCapability.IdentityRejectedException.class,
            () -> capability.registerWorld("world/child", Files.createDirectories(temporary.resolve("child"))));
        assertThrows(WorldExternalPersistenceCapability.IdentityRejectedException.class,
            () -> capability.acquireMutation("setDifficulty", " world"));
    }

    @Test
    void replacementFenceClosesNormalOperationsOnlyDuringLifecycle() throws Exception {
        Path worldRoot = Files.createDirectories(temporary.resolve("world"));
        FakeAdapter adapter = new FakeAdapter();
        WorldExternalPersistenceCapability.WorldRoot root = new WorldExternalPersistenceCapability.WorldRoot("world", worldRoot);
        WorldExternalPersistenceAdapterRegistration registration = WorldExternalPersistenceAdapterRegistry.register(adapter, List.of(root));
        WorldExternalPersistenceCapability capability = new WorldExternalPersistenceCapability(registration, adapter, List.of(root));

        WorldExternalPersistenceCapability.MutationLease normal = capability.acquireMutation("setDifficulty", "world");
        CompletableFuture<Void> quiesce = CompletableFuture.runAsync(() -> {
            try {
                capability.quiesce();
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        });
        waitForState(capability, WorldExternalPersistenceCapability.State.QUIESCING);
        assertThrows(WorldExternalPersistenceCapability.OperationRejectedException.class,
            () -> capability.acquireMutation("setDifficulty", "world"));
        normal.close();
        quiesce.get(2, TimeUnit.SECONDS);
        capability.resume();
        assertTrue(capability.normalMutationAdmissionOpen());
    }

    @Test
    void unregisteredAdapterCannotSelfApprove() throws Exception {
        Path worldRoot = Files.createDirectories(temporary.resolve("world"));
        FakeAdapter adapter = new FakeAdapter();

        assertThrows(IOException.class, () -> new WorldExternalPersistenceCapability(adapter,
            List.of(new WorldExternalPersistenceCapability.WorldRoot("world", worldRoot))));
    }

    @Test
    void registrationRejectsNoOpSnapshotRollback() throws Exception {
        Path worldRoot = Files.createDirectories(temporary.resolve("world"));
        FakeAdapter adapter = new FakeAdapter();
        adapter.noOpRollback = true;
        WorldExternalPersistenceCapability.WorldRoot root = new WorldExternalPersistenceCapability.WorldRoot("world", worldRoot);

        assertThrows(IOException.class, () -> WorldExternalPersistenceAdapterRegistry.register(adapter, List.of(root)));
    }

    @Test
    void registrationRejectsNoOpAdapterBehavior() throws Exception {
        Path worldRoot = Files.createDirectories(temporary.resolve("world"));
        FakeAdapter adapter = new FakeAdapter();
        adapter.noOpBehavior = true;

        assertThrows(IOException.class, () -> WorldExternalPersistenceAdapterRegistry.register(adapter, List.of(
            new WorldExternalPersistenceCapability.WorldRoot("world", worldRoot))));
    }

    @Test
    void registrationRejectsPartialBehavioralProbe() throws Exception {
        Path worldRoot = Files.createDirectories(temporary.resolve("world"));
        Path netherRoot = Files.createDirectories(temporary.resolve("nether"));
        FakeAdapter adapter = new FakeAdapter();
        adapter.partialProbe = true;

        assertThrows(IOException.class, () -> WorldExternalPersistenceAdapterRegistry.register(adapter, List.of(
            new WorldExternalPersistenceCapability.WorldRoot("world", worldRoot),
            new WorldExternalPersistenceCapability.WorldRoot("nether", netherRoot))));
    }

    @Test
    void registrationRejectsDishonestRollback() throws Exception {
        Path worldRoot = Files.createDirectories(temporary.resolve("world"));
        FakeAdapter adapter = new FakeAdapter();
        adapter.dishonestRollback = true;

        assertThrows(IOException.class, () -> WorldExternalPersistenceAdapterRegistry.register(adapter, List.of(
            new WorldExternalPersistenceCapability.WorldRoot("world", worldRoot))));
    }

    @Test
    void crashCutLeavesDurableEvidenceAndNextRegistrationRecoversIt() throws Exception {
        Path worldRoot = Files.createDirectories(temporary.resolve("world"));
        FakeAdapter adapter = new FakeAdapter();
        WorldExternalPersistenceCapability.WorldRoot root = new WorldExternalPersistenceCapability.WorldRoot("world", worldRoot);

        WorldExternalPersistenceAdapterRegistry.crashAfter("save");
        try {
            assertThrows(RuntimeException.class, () -> WorldExternalPersistenceAdapterRegistry.register(adapter, List.of(root)));
        } finally {
            WorldExternalPersistenceAdapterRegistry.crashAfter(null);
        }
        Path authority = temporary.resolve(".resync-external-persistence");
        assertTrue(Files.isDirectory(authority));
        try (var paths = Files.walk(authority)) {
            assertTrue(paths.anyMatch(path -> path.getFileName().toString().equals("probe-manifest")));
        }

        WorldExternalPersistenceAdapterRegistration registration =
            WorldExternalPersistenceAdapterRegistry.register(adapter, List.of(root));
        assertTrue(Files.readString(registration.probeManifest()).contains("status=RECOVERED"));
        assertTrue(Files.readString(registration.probeManifest()).contains("status=COMMITTED"));
    }

    @Test
    void registrationCannotBeSelfIssued() {
        assertThrows(SecurityException.class, () -> new WorldExternalPersistenceAdapterRegistration(
            "capability", "adapter", "signing-key", Set.of("save"), new Object(), "manifest", new Object()));
    }

    @Test
    void trustedRegistrationControlsLifecycleAndIntegrity() throws Exception {
        Path worldRoot = Files.createDirectories(temporary.resolve("world"));
        Path replacementRoot = Files.createDirectories(temporary.resolve("replacement-parent").resolve("world"));
        FakeAdapter adapter = new FakeAdapter();
        WorldExternalPersistenceCapability.WorldRoot root = new WorldExternalPersistenceCapability.WorldRoot("world", worldRoot);
        WorldExternalPersistenceAdapterRegistration registration =
            WorldExternalPersistenceAdapterRegistry.register(adapter, List.of(root));
        assertFalse(registration.probeManifestHash().isBlank());
        WorldExternalPersistenceCapability capability = new WorldExternalPersistenceCapability(registration, adapter,
            List.of(root), Duration.ofSeconds(2));

        WorldExternalPersistenceCapability.MutationLease operation = capability.acquireMutation("setDifficulty", "world");
        CompletableFuture<Void> quiesce = CompletableFuture.runAsync(() -> {
            try {
                capability.quiesce(Duration.ofSeconds(2));
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        });
        waitForState(capability, WorldExternalPersistenceCapability.State.QUIESCING);
        assertEquals(1, capability.activeOperationCount());
        assertFalse(quiesce.isDone());

        operation.close();
        quiesce.get(2, TimeUnit.SECONDS);

        assertEquals(WorldExternalPersistenceCapability.State.QUIESCED, capability.state());
        Path snapshotPath = temporary.resolve("world.snapshot");
        WorldExternalPersistenceCapability.ExternalWorldSnapshot snapshot = capability.snapshot("world", snapshotPath);
        assertTrue(snapshot.verified());
        assertEquals(registration.capabilityId(), snapshot.capabilityId());
        assertEquals(registration.adapterId(), snapshot.adapterId());
        WorldExternalPersistenceCapability.ExternalWorldSnapshot tampered = new WorldExternalPersistenceCapability.ExternalWorldSnapshot(
            snapshot.snapshotId(), snapshot.capabilityId(), snapshot.adapterId(), snapshot.worldName(), snapshot.root(), snapshot.path(),
            snapshot.generation(), snapshot.contentHash(), snapshot.treeHash(), snapshot.manifest(), snapshot.signature() + "tampered",
            true, snapshot.transactionToken());
        assertThrows(WorldExternalPersistenceCapability.IdentityRejectedException.class, () -> capability.restore("world", tampered));
        Files.writeString(snapshot.path(), "tampered");
        assertThrows(WorldExternalPersistenceCapability.IdentityRejectedException.class, () -> capability.restore("world", snapshot));
        Files.writeString(snapshot.path(), "world:1");
        Files.writeString(worldRoot.resolve("changed-after-snapshot.txt"), "live mutation");
        capability.resume();
        capability.quiesce();
        capability.restore("world", snapshot);
        capability.rebindWorld("world", replacementRoot);
        capability.restore("world", snapshot);
        capability.resume();

        assertEquals(replacementRoot.toAbsolutePath().normalize(), capability.root("world").root());
        assertEquals(0, capability.activeOperationCount());
        assertTrue(adapter.calls.contains("save"));
        assertTrue(adapter.calls.contains("quiesce"));
        assertTrue(adapter.calls.contains("snapshot"));
        assertTrue(adapter.calls.contains("restore"));
        assertTrue(adapter.calls.contains("rebind"));
        assertTrue(adapter.calls.contains("resume"));
    }

    @Test
    void concurrentLifecycleCallsAreSerialized() throws Exception {
        Path worldRoot = Files.createDirectories(temporary.resolve("world"));
        FakeAdapter adapter = new FakeAdapter();
        WorldExternalPersistenceCapability.WorldRoot root = new WorldExternalPersistenceCapability.WorldRoot("world", worldRoot);
        WorldExternalPersistenceAdapterRegistration registration = WorldExternalPersistenceAdapterRegistry.register(adapter, List.of(root));
        WorldExternalPersistenceCapability capability = new WorldExternalPersistenceCapability(registration, adapter, List.of(root));
        capability.quiesce();
        adapter.resumeEntered = new CountDownLatch(1);
        adapter.resumeRelease = new CountDownLatch(1);

        CompletableFuture<Void> resume = CompletableFuture.runAsync(() -> {
            try {
                capability.resume();
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        });
        waitForState(capability, WorldExternalPersistenceCapability.State.RESUMING);
        adapter.resumeEntered.await(2, TimeUnit.SECONDS);
        CompletableFuture<Void> health = CompletableFuture.runAsync(() -> {
            try {
                capability.healthCheck();
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        });
        assertFalse(health.isDone());
        adapter.resumeRelease.countDown();
        resume.get(2, TimeUnit.SECONDS);
        health.get(2, TimeUnit.SECONDS);
        assertEquals(WorldExternalPersistenceCapability.State.OPEN, capability.state());
    }

    @Test
    void lifecycleFailuresCompensateAndPreserveSafeState() throws Exception {
        Path worldRoot = Files.createDirectories(temporary.resolve("world"));
        Path replacementRoot = Files.createDirectories(temporary.resolve("replacement").resolve("world"));
        FakeAdapter adapter = new FakeAdapter();
        WorldExternalPersistenceCapability.WorldRoot root = new WorldExternalPersistenceCapability.WorldRoot("world", worldRoot);
        WorldExternalPersistenceAdapterRegistration registration = WorldExternalPersistenceAdapterRegistry.register(adapter, List.of(root));
        WorldExternalPersistenceCapability capability = new WorldExternalPersistenceCapability(registration, adapter, List.of(root));

        adapter.failQuiesce = true;
        assertThrows(IOException.class, capability::quiesce);
        assertEquals(WorldExternalPersistenceCapability.State.OPEN, capability.state());
        assertTrue(adapter.calls.contains("rollback-quiesce"));
        assertTrue(adapter.calls.contains("rollback-save"));

        adapter.failQuiesce = false;
        capability.quiesce();
        adapter.failResumeHealth = true;
        assertThrows(IOException.class, capability::resume);
        assertEquals(WorldExternalPersistenceCapability.State.QUIESCED, capability.state());
        assertTrue(adapter.calls.contains("rollback-resume"));

        adapter.failResumeHealth = false;
        adapter.failRebind = true;
        assertThrows(IOException.class, () -> capability.rebindWorld("world", replacementRoot));
        assertEquals(WorldExternalPersistenceCapability.State.QUIESCED, capability.state());
        assertEquals(worldRoot.toAbsolutePath().normalize(), capability.root("world").root());
        assertTrue(adapter.calls.contains("rollback-rebind"));
    }

    @Test
    void snapshotTransactionFailureIsCompensated() throws Exception {
        Path worldRoot = Files.createDirectories(temporary.resolve("world"));
        FakeAdapter adapter = new FakeAdapter();
        WorldExternalPersistenceCapability.WorldRoot root = new WorldExternalPersistenceCapability.WorldRoot("world", worldRoot);
        WorldExternalPersistenceAdapterRegistration registration = WorldExternalPersistenceAdapterRegistry.register(adapter, List.of(root));
        WorldExternalPersistenceCapability capability = new WorldExternalPersistenceCapability(registration, adapter, List.of(root));

        capability.quiesce();
        adapter.failSnapshot = true;
        assertThrows(IOException.class, () -> capability.snapshot("world", temporary.resolve("failed.snapshot")));
        assertEquals(WorldExternalPersistenceCapability.State.QUIESCED, capability.state());
        assertTrue(adapter.calls.contains("rollback-snapshot"));
    }

    @Test
    void registryRoutesEveryBehavioralOperationThroughTheMainThreadContract() throws Exception {
        Path worldRoot = Files.createDirectories(temporary.resolve("world"));
        ExecutorService mainExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "external-world-main");
            thread.setDaemon(true);
            return thread;
        });
        try {
            FakeAdapter adapter = new FakeAdapter();
            adapter.executionContract = WorldExternalPersistenceAdapter.ExecutionContract.paper(
                () -> Thread.currentThread().getName().equals("external-world-main"), mainExecutor);
            WorldExternalPersistenceAdapterRegistry.register(adapter, List.of(
                new WorldExternalPersistenceCapability.WorldRoot("world", worldRoot)));

            assertTrue(adapter.calls.containsAll(List.of("health", "save", "quiesce", "snapshot", "restore", "rebind", "resume")));
            assertTrue(adapter.operationThreads.stream()
                .allMatch(thread -> thread.getName().equals("external-world-main")));
        } finally {
            mainExecutor.shutdownNow();
        }
    }

    @Test
    void registryRecordsAndCompensatesSnapshotFailureForRecovery() throws Exception {
        Path worldRoot = Files.createDirectories(temporary.resolve("world"));
        WorldExternalPersistenceCapability.WorldRoot root = new WorldExternalPersistenceCapability.WorldRoot("world", worldRoot);
        FakeAdapter adapter = new FakeAdapter();
        adapter.failSnapshot = true;

        assertThrows(IOException.class, () -> WorldExternalPersistenceAdapterRegistry.register(adapter, List.of(root)));
        assertEquals(1, adapter.calls.stream().filter("rollback-snapshot"::equals).count());

        Path authority = temporary.resolve(".resync-external-persistence");
        Path failedManifest;
        try (var paths = Files.walk(authority)) {
            failedManifest = paths.filter(path -> path.getFileName().toString().equals("probe-manifest"))
                .findFirst().orElseThrow();
        }
        List<String> failedLines = Files.readAllLines(failedManifest);
        assertTrue(failedLines.stream().anyMatch(line -> line.startsWith("begin\t")));
        assertTrue(failedLines.stream().anyMatch(line -> line.startsWith("receipt\tc25hcHNob3Q\t")));
        assertTrue(failedLines.stream().anyMatch(line -> line.startsWith("rollback-ok\t")));
        assertTrue(failedLines.contains("status=ROLLED_BACK"));

        adapter.failSnapshot = false;
        WorldExternalPersistenceAdapterRegistration registration =
            WorldExternalPersistenceAdapterRegistry.register(adapter, List.of(root));
        assertTrue(Files.readString(failedManifest).contains("status=RECOVERED"));
        assertTrue(Files.readString(registration.probeManifest()).contains("status=COMMITTED"));
    }

    @Test
    void rollbackRuntimeFailureFailsClosed() throws Exception {
        Path worldRoot = Files.createDirectories(temporary.resolve("world"));
        FakeAdapter adapter = new FakeAdapter();
        WorldExternalPersistenceCapability.WorldRoot root = new WorldExternalPersistenceCapability.WorldRoot("world", worldRoot);
        WorldExternalPersistenceAdapterRegistration registration = WorldExternalPersistenceAdapterRegistry.register(adapter, List.of(root));
        WorldExternalPersistenceCapability capability = new WorldExternalPersistenceCapability(registration, adapter, List.of(root));

        adapter.failQuiesce = true;
        adapter.failRollback = true;
        assertThrows(IOException.class, capability::quiesce);
        assertEquals(WorldExternalPersistenceCapability.State.FAILED, capability.state());
    }

    @Test
    void failedHealthCheckClosesAdmission() throws Exception {
        Path worldRoot = Files.createDirectories(temporary.resolve("world"));
        FakeAdapter adapter = new FakeAdapter();
        WorldExternalPersistenceCapability.WorldRoot root = new WorldExternalPersistenceCapability.WorldRoot("world", worldRoot);
        WorldExternalPersistenceAdapterRegistration registration = WorldExternalPersistenceAdapterRegistry.register(adapter, List.of(root));
        WorldExternalPersistenceCapability capability = new WorldExternalPersistenceCapability(registration, adapter, List.of(root));

        adapter.failResumeHealth = true;
        assertThrows(IOException.class, capability::healthCheck);
        assertEquals(WorldExternalPersistenceCapability.State.FAILED, capability.state());
        assertThrows(WorldExternalPersistenceCapability.OperationRejectedException.class,
            () -> capability.acquireNormalMutation("setDifficulty", "world"));
    }

    @Test
    void paperExecutionContractMarshalsOffThreadWithoutBlockingTheMainExecutor() throws Exception {
        ExecutorService mainExecutor = Executors.newSingleThreadExecutor();
        try {
            WorldExternalPersistenceAdapter.ExecutionContract contract =
                WorldExternalPersistenceAdapter.ExecutionContract.paper(() -> false, mainExecutor);
            assertTrue(contract.requiresMainThread());
            assertFalse(contract.isMainThread());
            assertEquals("main", contract.execute(() -> "main"));
        } finally {
            mainExecutor.shutdownNow();
        }
    }

    private void waitForState(WorldExternalPersistenceCapability capability, WorldExternalPersistenceCapability.State expected)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (capability.state() != expected && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertEquals(expected, capability.state());
    }

    private static final class FakeAdapter implements WorldExternalPersistenceAdapter {
        private final List<String> calls = new ArrayList<>();
        private final List<Thread> operationThreads = new ArrayList<>();
        private int sequence;
        private ExecutionContract executionContract = ExecutionContract.direct();
        private boolean failQuiesce;
        private boolean failResumeHealth;
        private boolean failRebind;
        private boolean failSnapshot;
        private boolean noOpBehavior;
        private boolean noOpRollback;
        private boolean partialProbe;
        private boolean dishonestRollback;
        private boolean failRollback;
        private CountDownLatch resumeEntered;
        private CountDownLatch resumeRelease;
        private final Map<String, Path> snapshots = new java.util.HashMap<>();
        private final Map<String, Path> probeMarkers = new java.util.HashMap<>();
        private final Map<String, TreeSnapshot> treeStates = new java.util.HashMap<>();

        @Override
        public ExecutionContract executionContract() {
            return executionContract;
        }

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public void healthCheck(Collection<WorldExternalPersistenceCapability.WorldRoot> roots) throws IOException {
            recordCall("health");
            if (failResumeHealth) {
                throw new IOException("health-failed");
            }
        }

        @Override
        public TransactionReceipt save(Collection<WorldExternalPersistenceCapability.WorldRoot> roots) {
            recordCall("save");
            return receipt("save");
        }

        @Override
        public TransactionReceipt save(Collection<WorldExternalPersistenceCapability.WorldRoot> roots,
                                       BehavioralProbe probe) throws IOException {
            return transition("save", roots, probe);
        }

        @Override
        public TransactionReceipt quiesce(Collection<WorldExternalPersistenceCapability.WorldRoot> roots) throws IOException {
            recordCall("quiesce");
            if (failQuiesce) {
                throw new TransactionFailure("quiesce-failed", new TransactionReceipt("quiesce", "failed-quiesce-token"));
            }
            return receipt("quiesce");
        }

        @Override
        public TransactionReceipt quiesce(Collection<WorldExternalPersistenceCapability.WorldRoot> roots,
                                          BehavioralProbe probe) throws IOException {
            if (failQuiesce) {
                TransactionReceipt transaction = transition("quiesce", roots, probe);
                throw new TransactionFailure("quiesce-failed", transaction);
            }
            return transition("quiesce", roots, probe);
        }

        @Override
        public TransactionReceipt resume(Collection<WorldExternalPersistenceCapability.WorldRoot> roots) throws IOException {
            recordCall("resume");
            if (resumeEntered != null && resumeRelease != null) {
                resumeEntered.countDown();
                try {
                    resumeRelease.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("resume-interrupted", exception);
                }
            }
            return receipt("resume");
        }

        @Override
        public TransactionReceipt resume(Collection<WorldExternalPersistenceCapability.WorldRoot> roots,
                                         BehavioralProbe probe) throws IOException {
            return transition("resume", roots, probe);
        }

        @Override
        public TransactionReceipt coordinatorProbe(WorldExternalPersistenceCapability.WorldRoot root, Path marker) throws IOException {
            recordCall("probe");
            Files.writeString(marker, "after");
            String token = "probe-token-" + (++sequence);
            probeMarkers.put(token, marker);
            return new TransactionReceipt("probe", token);
        }

        @Override
        public SnapshotArtifact snapshot(WorldExternalPersistenceCapability.WorldRoot root, Path destination, long generation)
            throws IOException {
            recordCall("snapshot");
            Files.writeString(destination, root.name() + ":" + generation);
            String token = "snapshot-token-" + (++sequence);
            snapshots.put(token, destination);
            if (failSnapshot) {
                throw new TransactionFailure("snapshot-failed", new TransactionReceipt("snapshot", token));
            }
            return new SnapshotArtifact(new WorldExternalPersistenceCapability.SnapshotId("snapshot-" + sequence), destination, token);
        }

        @Override
        public SnapshotArtifact snapshot(WorldExternalPersistenceCapability.WorldRoot root, Path destination, long generation,
                                         BehavioralProbe probe) throws IOException {
            TransactionReceipt transaction = transition("snapshot", List.of(root), probe);
            Files.writeString(destination, root.name() + ":" + generation);
            snapshots.put(transaction.token(), destination);
            if (failSnapshot) {
                throw new TransactionFailure("snapshot-failed", transaction);
            }
            return new SnapshotArtifact(new WorldExternalPersistenceCapability.SnapshotId("snapshot-" + sequence), destination,
                transaction.token());
        }

        @Override
        public TransactionReceipt restore(WorldExternalPersistenceCapability.WorldRoot root,
                                          WorldExternalPersistenceCapability.ExternalWorldSnapshot snapshot) {
            recordCall("restore");
            return receipt("restore");
        }

        @Override
        public TransactionReceipt restore(WorldExternalPersistenceCapability.WorldRoot root,
                                          WorldExternalPersistenceCapability.ExternalWorldSnapshot snapshot,
                                          BehavioralProbe probe) throws IOException {
            return transition("restore", List.of(root), probe);
        }

        @Override
        public TransactionReceipt rebind(Map<String, WorldExternalPersistenceCapability.WorldRoot> roots) throws IOException {
            recordCall("rebind");
            if (failRebind) {
                throw new TransactionFailure("rebind-failed", new TransactionReceipt("rebind", "failed-rebind-token"));
            }
            return receipt("rebind");
        }

        @Override
        public TransactionReceipt rebind(Map<String, WorldExternalPersistenceCapability.WorldRoot> roots,
                                         BehavioralProbe probe) throws IOException {
            if (failRebind) {
                TransactionReceipt transaction = transition("rebind", roots.values(), probe);
                throw new TransactionFailure("rebind-failed", transaction);
            }
            return transition("rebind", roots.values(), probe);
        }

        @Override
        public void rollback(TransactionReceipt receipt) {
            recordCall("rollback-" + receipt.operation());
            if (failRollback) {
                throw new IllegalStateException("rollback-failed");
            }
            if (!noOpRollback && "snapshot".equals(receipt.operation())) {
                Path snapshot = snapshots.remove(receipt.token());
                if (snapshot != null) {
                    try {
                        Files.deleteIfExists(snapshot);
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                }
            }
            if (!noOpRollback && "probe".equals(receipt.operation())) {
                Path marker = probeMarkers.remove(receipt.token());
                if (marker != null) {
                    try {
                        Files.writeString(marker, "before");
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                }
            }
            if (!noOpRollback) {
                TreeSnapshot state = treeStates.remove(receipt.token());
                if (state != null) {
                    restoreTree(state);
                    if (dishonestRollback) {
                        try {
                            Files.writeString(state.roots().getFirst().resolve("dishonest-rollback-" + receipt.token()), "left-behind");
                        } catch (IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    }
                }
            }
        }

        private TransactionReceipt transition(String operation,
                                              Collection<WorldExternalPersistenceCapability.WorldRoot> roots,
                                              BehavioralProbe probe) throws IOException {
            recordCall(operation);
            if (probe == null) {
                throw new IOException("behavioral probe required");
            }
            TransactionReceipt transaction = receipt(operation);
            if (noOpBehavior) {
                return transaction;
            }
            treeStates.put(transaction.token(), captureTree(roots));
            int index = 0;
            for (WorldExternalPersistenceCapability.WorldRoot root : roots) {
                if (partialProbe && index++ > 0) {
                    continue;
                }
                Path marker = probe.marker(root.name());
                if (marker == null) {
                    throw new IOException("marker missing");
                }
                Files.writeString(marker, operation + ":" + transaction.token());
                Files.writeString(root.root().resolve("operation-" + transaction.token()), operation);
            }
            return transaction;
        }

        private void recordCall(String operation) {
            calls.add(operation);
            operationThreads.add(Thread.currentThread());
        }

        private TreeSnapshot captureTree(Collection<WorldExternalPersistenceCapability.WorldRoot> roots) throws IOException {
            Map<Path, byte[]> state = new java.util.HashMap<>();
            for (WorldExternalPersistenceCapability.WorldRoot root : roots) {
                try (var paths = Files.walk(root.root())) {
                    for (Path path : paths.toList()) {
                        if (Files.isRegularFile(path)) {
                            state.put(path.toAbsolutePath().normalize(), Files.readAllBytes(path));
                        }
                    }
                }
            }
            return new TreeSnapshot(state, roots.stream().map(WorldExternalPersistenceCapability.WorldRoot::root).toList());
        }

        private void restoreTree(TreeSnapshot snapshot) {
            try {
                Set<Path> current = snapshot.files().keySet();
                for (Path root : snapshot.roots()) {
                    try (var paths = Files.walk(root)) {
                        for (Path path : paths.filter(Files::isRegularFile).toList()) {
                            if (!current.contains(path.toAbsolutePath().normalize())) {
                                Files.deleteIfExists(path);
                            }
                        }
                    }
                }
                for (Map.Entry<Path, byte[]> entry : snapshot.files().entrySet()) {
                    Files.createDirectories(entry.getKey().getParent());
                    Files.write(entry.getKey(), entry.getValue());
                }
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private record TreeSnapshot(Map<Path, byte[]> files, List<Path> roots) {
        }

        private TransactionReceipt receipt(String operation) {
            return new TransactionReceipt(operation, operation + "-token-" + (++sequence));
        }
    }
}
