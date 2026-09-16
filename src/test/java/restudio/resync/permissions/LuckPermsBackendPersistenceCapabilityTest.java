package restudio.resync.permissions;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.modules.LuckPermsManagementModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuckPermsBackendPersistenceCapabilityTest {
    @Test
    void capableAdapterDeclaresReadinessAndRunsReplacementLifecycle() throws Exception {
        FakeAdapter adapter = new FakeAdapter(true);
        LuckPermsBackendPersistenceCapability capability = new LuckPermsBackendPersistenceCapability(adapter);

        assertTrue(capability.available());
        assertTrue(capability.snapshotReady());
        assertTrue(capability.rebindReady());

        LuckPermsBackendPersistenceCapability.Admission admission = capability.acquire("save");
        CompletableFuture<Void> quiesce = CompletableFuture.runAsync(() -> assertDoesNotThrow(() -> capability.quiesce(Duration.ofSeconds(2))));
        assertTrue(awaitState(capability, LuckPermsBackendPersistenceCapability.State.QUIESCING));
        assertEquals(LuckPermsBackendPersistenceCapability.State.QUIESCING, capability.state());
        admission.close();
        quiesce.join();

        Path snapshot = Path.of("snapshot").toAbsolutePath().normalize();
        Path replacement = Path.of("replacement").toAbsolutePath().normalize();
        LuckPermsBackendPersistenceCapability.SnapshotManifest manifest = capability.backup(snapshot);
        capability.restore(manifest);
        capability.rebind(manifest, replacement);
        assertEquals(Path.of("backend").toAbsolutePath().normalize(), capability.activeRoot().orElseThrow());
        capability.resume();
        assertEquals(replacement, capability.activeRoot().orElseThrow());

        assertEquals(List.of("quiesce", "flush", "backup:" + snapshot, "restore:" + manifest.snapshotRoot(),
                "rebind:" + replacement, "health", "resume"),
            adapter.operations);
        assertEquals(LuckPermsBackendPersistenceCapability.State.OPEN, capability.state());
    }

    @Test
    void incapableAdapterRemainsUnavailableAndFencesMutationsDuringReplacement() throws Exception {
        FakeAdapter adapter = new FakeAdapter(false);
        LuckPermsBackendPersistenceCapability capability = new LuckPermsBackendPersistenceCapability(adapter);

        assertFalse(capability.available());
        assertTrue(capability.adapterConfigured());
        assertFalse(capability.snapshotReady());
        assertFalse(capability.rebindReady());
        assertFalse(capability.health().healthy());
        capability.quiesce();
        assertThrows(IllegalStateException.class, () -> capability.acquire("save"));
        assertThrows(IOException.class, () -> capability.backup(Path.of("snapshot")));
        assertThrows(IOException.class, () -> capability.rebind(Path.of("replacement")));
        capability.resume();
        LuckPermsBackendPersistenceCapability.Admission resumed = capability.tryAcquire("save").orElseThrow();
        resumed.close();
    }

    @Test
    void drainWaitsForEveryAsyncMutationAndDoesNotAdmitNewWork() throws Exception {
        FakeAdapter adapter = new FakeAdapter(true);
        LuckPermsBackendPersistenceCapability capability = new LuckPermsBackendPersistenceCapability(adapter);
        CompletableFuture<Void> mutation = new CompletableFuture<>();
        CompletableFuture<Void> tracked = capability.trackMutation("delete", () -> mutation);

        CompletableFuture<Void> quiesce = CompletableFuture.runAsync(() -> assertDoesNotThrow(capability::quiesce));
        assertTrue(awaitState(capability, LuckPermsBackendPersistenceCapability.State.QUIESCING));
        assertFalse(quiesce.isDone());
        assertThrows(IllegalStateException.class, () -> capability.acquire("save"));
        mutation.complete(null);
        tracked.join();
        quiesce.join();
        assertEquals(LuckPermsBackendPersistenceCapability.State.QUIESCED, capability.state());
        assertEquals(List.of("quiesce", "flush"), adapter.operations);
    }

    @Test
    void backendFailuresFenceCapabilityAndKeepReadinessUnavailable() {
        FakeAdapter adapter = new FakeAdapter(true);
        adapter.flushFailure = new IOException("flush failed");
        LuckPermsBackendPersistenceCapability capability = new LuckPermsBackendPersistenceCapability(adapter);

        assertThrows(IOException.class, capability::quiesce);
        assertEquals(LuckPermsBackendPersistenceCapability.State.DEGRADED, capability.state());
        assertFalse(capability.readiness().rebindReady());
        assertThrows(IllegalStateException.class, () -> capability.acquire("save"));
    }

    @Test
    void degradedCapabilityRejectsHealthAndRestoreReadiness() {
        FakeAdapter adapter = new FakeAdapter(true);
        adapter.flushFailure = new IOException("flush failed");
        LuckPermsBackendPersistenceCapability capability = new LuckPermsBackendPersistenceCapability(adapter);

        assertThrows(IOException.class, capability::quiesce);

        assertEquals(LuckPermsBackendPersistenceCapability.State.DEGRADED, capability.state());
        assertFalse(capability.health().healthy());
        assertFalse(capability.restoreReady());
        assertFalse(capability.readiness().restoreReady());
        assertThrows(IOException.class, capability::healthCheck);
    }

    @Test
    void externalQuiesceFailureCompensatesAndLeavesARecoverableCapability() throws Exception {
        FakeAdapter adapter = new FakeAdapter(true);
        adapter.quiesceFailure = new IOException("quiesce failed");
        LuckPermsBackendPersistenceCapability capability = new LuckPermsBackendPersistenceCapability(adapter);

        assertThrows(IOException.class, capability::quiesce);
        assertEquals(LuckPermsBackendPersistenceCapability.State.DEGRADED, capability.state());
        assertEquals(List.of("quiesce", "resume"), adapter.operations);
        adapter.quiesceFailure = null;
        capability.quiesce();
        assertEquals(LuckPermsBackendPersistenceCapability.State.QUIESCED, capability.state());
    }

    @Test
    void flushFailureAfterExternalQuiesceCompensatesBeforeRetry() throws Exception {
        FakeAdapter adapter = new FakeAdapter(true);
        adapter.flushFailure = new IOException("flush failed");
        LuckPermsBackendPersistenceCapability capability = new LuckPermsBackendPersistenceCapability(adapter);

        assertThrows(IOException.class, capability::quiesce);
        assertEquals(List.of("quiesce", "flush", "resume"), adapter.operations);
        adapter.flushFailure = null;
        capability.quiesce();
        assertEquals(LuckPermsBackendPersistenceCapability.State.QUIESCED, capability.state());
    }

    @Test
    void unavailableAdapterIsSeparateFromOperationJournal() throws Exception {
        LuckPermsBackendPersistenceCapability capability = LuckPermsBackendPersistenceCapability.unavailable();
        LuckPermsBackendPersistenceCapability noOp = LuckPermsBackendPersistenceCapability.noOp();

        assertFalse(capability.adapterConfigured());
        assertFalse(noOp.adapterConfigured());
        assertTrue(capability.activeRoot().isEmpty());
        assertFalse(capability.readiness().available());
        assertFalse(capability.readiness().rebindReady());
        LuckPermsBackendPersistenceCapability.Admission normal = capability.tryAcquire("save").orElseThrow();
        normal.close();
        capability.quiesce();
        assertTrue(capability.tryAcquire("save").isEmpty());
        capability.resume();
        LuckPermsBackendPersistenceCapability.Admission resumed = capability.tryAcquire("save").orElseThrow();
        resumed.close();
    }

    @Test
    void externalWritersKeepReplacementUnavailableWhenAdapterCannotQuiesceThem() throws Exception {
        FakeAdapter adapter = new FakeAdapter(true);
        adapter.supportOverride = new LuckPermsBackendPersistenceCapability.Support(true, true, true, true, true,
            false, false, true, "external writers cannot be quiesced");
        LuckPermsBackendPersistenceCapability capability = new LuckPermsBackendPersistenceCapability(adapter);

        assertFalse(capability.available());
        assertFalse(capability.snapshotReady());
        assertFalse(capability.rebindReady());
        capability.quiesce();
        assertThrows(IOException.class, () -> capability.backup(Path.of("snapshot")));
        capability.resume();
        LuckPermsBackendPersistenceCapability.Admission admission = capability.tryAcquire("save").orElseThrow();
        admission.close();
    }

    @Test
    void invalidSupportFencesAdmissionsAndCannotQuiesce() {
        FakeAdapter adapter = new FakeAdapter(true);
        adapter.invalidSupport = true;
        LuckPermsBackendPersistenceCapability capability = new LuckPermsBackendPersistenceCapability(adapter);

        assertTrue(capability.tryAcquire("save").isEmpty());
        assertEquals(LuckPermsBackendPersistenceCapability.State.FAILED, capability.state());
        assertThrows(IOException.class, capability::quiesce);
    }

    @Test
    void supportExceptionFencesAdmissions() {
        FakeAdapter adapter = new FakeAdapter(true);
        adapter.supportException = true;
        LuckPermsBackendPersistenceCapability capability = new LuckPermsBackendPersistenceCapability(adapter);

        assertTrue(capability.tryAcquire("save").isEmpty());
        assertEquals(LuckPermsBackendPersistenceCapability.State.FAILED, capability.state());
        assertFalse(capability.readiness().available());
    }

    @Test
    void snapshotManifestBindsIdentityGenerationAndContract() throws Exception {
        FakeAdapter adapter = new FakeAdapter(true);
        LuckPermsBackendPersistenceCapability capability = new LuckPermsBackendPersistenceCapability(adapter);
        capability.quiesce();
        LuckPermsBackendPersistenceCapability.SnapshotManifest manifest = capability.backup(Path.of("snapshot"));

        adapter.identity = new LuckPermsBackendPersistenceCapability.BackendIdentity("luckperms", "other");
        assertThrows(IOException.class, () -> capability.restore(manifest));
        assertEquals(LuckPermsBackendPersistenceCapability.State.DEGRADED, capability.state());
    }

    @Test
    void snapshotManifestRejectsGenerationAndHashMismatch() throws Exception {
        FakeAdapter adapter = new FakeAdapter(true);
        LuckPermsBackendPersistenceCapability capability = new LuckPermsBackendPersistenceCapability(adapter);
        capability.quiesce();
        LuckPermsBackendPersistenceCapability.SnapshotManifest manifest = capability.backup(Path.of("snapshot"));
        LuckPermsBackendPersistenceCapability.SnapshotManifest wrongHash =
            new LuckPermsBackendPersistenceCapability.SnapshotManifest(manifest.backendIdentity(), manifest.generation(),
                manifest.adapterContract(), "wrong", manifest.snapshotRoot());
        assertThrows(IOException.class, () -> capability.restore(wrongHash));
        assertEquals(LuckPermsBackendPersistenceCapability.State.DEGRADED, capability.state());

        FakeAdapter generationAdapter = new FakeAdapter(true);
        LuckPermsBackendPersistenceCapability generationCapability =
            new LuckPermsBackendPersistenceCapability(generationAdapter);
        generationCapability.quiesce();
        LuckPermsBackendPersistenceCapability.SnapshotManifest generationManifest =
            generationCapability.backup(Path.of("generation-snapshot"));
        generationCapability.rebind(generationManifest, Path.of("replacement"));
        assertThrows(IOException.class, () -> generationCapability.restore(generationManifest));
    }

    @Test
    void missingLocalArtifactIsRejectedWithoutPathHashFallback() throws Exception {
        FakeAdapter adapter = new FakeAdapter(true);
        adapter.missingArtifact = true;
        LuckPermsBackendPersistenceCapability capability = new LuckPermsBackendPersistenceCapability(adapter);
        capability.quiesce();

        assertThrows(IOException.class, () -> capability.backup(Path.of("snapshot")));
        assertEquals(LuckPermsBackendPersistenceCapability.State.DEGRADED, capability.state());
    }

    @Test
    void remoteReceiptMustBeTypedAndVerifiedByTheAdapter() throws Exception {
        FakeAdapter adapter = new FakeAdapter(true);
        adapter.remoteReceipt = true;
        LuckPermsBackendPersistenceCapability capability = new LuckPermsBackendPersistenceCapability(adapter);
        capability.quiesce();

        LuckPermsBackendPersistenceCapability.SnapshotManifest manifest = capability.backup(Path.of("snapshot"));
        assertTrue(manifest.snapshotRoot() == null);
        assertEquals(1, adapter.verifiedReceipts);
        capability.restore(manifest);
    }

    @Test
    void snapshotAndRebindReadinessRemainIndependent() throws Exception {
        FakeAdapter adapter = new FakeAdapter(true);
        adapter.supportOverride = new LuckPermsBackendPersistenceCapability.Support(true, true, true, false, false,
            true, true, true, "restore is unavailable");
        LuckPermsBackendPersistenceCapability capability = new LuckPermsBackendPersistenceCapability(adapter);

        assertTrue(capability.snapshotReady());
        assertFalse(capability.rebindReady());
        assertFalse(capability.available());
        capability.quiesce();
        assertDoesNotThrow(() -> capability.backup(Path.of("snapshot")));
    }

    @Test
    void failedRebindRollsBackToThePreviousRoot() throws Exception {
        FakeAdapter adapter = new FakeAdapter(true);
        LuckPermsBackendPersistenceCapability capability = new LuckPermsBackendPersistenceCapability(adapter);
        capability.quiesce();
        LuckPermsBackendPersistenceCapability.SnapshotManifest manifest = capability.backup(Path.of("snapshot"));
        adapter.rebindFailureRoot = Path.of("replacement").toAbsolutePath().normalize();

        assertThrows(IOException.class, () -> capability.rebind(manifest, adapter.rebindFailureRoot));
        assertEquals(Path.of("backend").toAbsolutePath().normalize(), capability.activeRoot().orElseThrow());
        assertEquals(LuckPermsBackendPersistenceCapability.State.QUIESCED, capability.state());
        assertEquals(List.of("quiesce", "flush", "backup:" + Path.of("snapshot").toAbsolutePath().normalize(),
                "rebind:" + adapter.rebindFailureRoot, "rebind:" + adapter.root), adapter.operations);
    }

    @Test
    void rebindValidatesCandidateHealthBeforeResumingExternalWriters() throws Exception {
        FakeAdapter adapter = new FakeAdapter(true);
        LuckPermsBackendPersistenceCapability capability = new LuckPermsBackendPersistenceCapability(adapter);
        capability.quiesce();
        LuckPermsBackendPersistenceCapability.SnapshotManifest manifest = capability.backup(Path.of("snapshot"));
        Path previous = capability.activeRoot().orElseThrow();
        Path replacement = Path.of("replacement").toAbsolutePath().normalize();

        capability.rebind(manifest, replacement);
        assertEquals(previous, capability.activeRoot().orElseThrow());
        adapter.healthFailure = true;
        assertThrows(IOException.class, capability::resume);
        assertEquals(previous, capability.activeRoot().orElseThrow());
        assertEquals(LuckPermsBackendPersistenceCapability.State.DEGRADED, capability.state());
        assertTrue(adapter.operations.contains("rebind:" + previous));
        assertEquals(List.of("quiesce", "flush", "backup:" + Path.of("snapshot").toAbsolutePath().normalize(),
            "rebind:" + replacement, "health", "rebind:" + previous), adapter.operations);
    }

    @Test
    void closeFailureRetainsRetryableAuthorityUntilPhysicalCloseSucceeds() throws Exception {
        FakeAdapter adapter = new FakeAdapter(true);
        LuckPermsBackendPersistenceCapability capability = new LuckPermsBackendPersistenceCapability(adapter);
        capability.quiesce();
        adapter.closeFailure = new IOException("close failed");

        assertThrows(IOException.class, capability::close);
        assertEquals(LuckPermsBackendPersistenceCapability.State.DEGRADED, capability.state());
        assertFalse(capability.state() == LuckPermsBackendPersistenceCapability.State.CLOSED);
        assertEquals(1, adapter.closeAttempts);

        adapter.closeFailure = null;
        capability.close();
        assertEquals(LuckPermsBackendPersistenceCapability.State.CLOSED, capability.state());
        assertEquals(2, adapter.closeAttempts);
    }

    @Test
    void rebindResumeFailureRollsBackBeforeRetryingExternalResume() throws Exception {
        FakeAdapter adapter = new FakeAdapter(true);
        LuckPermsBackendPersistenceCapability capability = new LuckPermsBackendPersistenceCapability(adapter);
        capability.quiesce();
        LuckPermsBackendPersistenceCapability.SnapshotManifest manifest = capability.backup(Path.of("snapshot"));
        Path previous = capability.activeRoot().orElseThrow();
        Path replacement = Path.of("replacement").toAbsolutePath().normalize();

        capability.rebind(manifest, replacement);
        adapter.resumeFailure = true;
        assertThrows(IOException.class, capability::resume);
        assertEquals(previous, capability.activeRoot().orElseThrow());
        assertEquals(LuckPermsBackendPersistenceCapability.State.DEGRADED, capability.state());
        assertEquals(List.of("quiesce", "flush", "backup:" + Path.of("snapshot").toAbsolutePath().normalize(),
            "rebind:" + replacement, "health", "resume", "quiesce", "rebind:" + previous), adapter.operations);

        adapter.resumeFailure = false;
        capability.resume();
        assertEquals(LuckPermsBackendPersistenceCapability.State.OPEN, capability.state());
        assertEquals(List.of("quiesce", "flush", "backup:" + Path.of("snapshot").toAbsolutePath().normalize(),
            "rebind:" + replacement, "health", "resume", "quiesce", "rebind:" + previous, "resume", "health"),
            adapter.operations);
    }

    @Test
    void serviceKeepsBackendBindingSeparateFromOperationJournal() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        try {
            FakeAdapter adapter = new FakeAdapter(true);
            LuckPermsBackendPersistenceCapability capability = new LuckPermsBackendPersistenceCapability(adapter);
            LuckPermsManagementService service = new LuckPermsManagementService(plugin, capability);
            Path journal = plugin.getDataFolder().toPath().resolve("runtime").resolve("luckperms-operations.json")
                .toAbsolutePath().normalize();

            assertEquals(journal, service.persistenceRoot());
            capability.quiesce();
            LuckPermsBackendPersistenceCapability.SnapshotManifest manifest = capability.backup(Path.of("snapshot"));
            capability.rebind(manifest, Path.of("replacement"));
            capability.resume();
            assertEquals(journal, service.persistenceRoot());
            service.close();
            assertTrue(adapter.closed);
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void productionModuleRetainsConfiguredAdapterInsteadOfForcingUnavailable() throws Exception {
        FakeAdapter adapter = new FakeAdapter(true);
        LuckPermsManagementModule module = new LuckPermsManagementModule(adapter);
        var field = LuckPermsManagementModule.class.getDeclaredField("configuredBackendAdapter");
        field.setAccessible(true);

        assertSame(adapter, field.get(module));
    }

    @Test
    void productionModuleDiscoversAnExplicitAdapterProvider() throws Exception {
        FakeAdapter adapter = new FakeAdapter(true);
        LuckPermsBackendPersistenceCapability.AdapterProvider provider = ignored -> adapter;
        LuckPermsManagementModule module = new LuckPermsManagementModule(provider);
        var field = LuckPermsManagementModule.class.getDeclaredField("configuredBackendProvider");
        field.setAccessible(true);
        assertSame(provider, field.get(module));
    }

    private static void assertDoesNotThrow(ThrowingRunnable action) {
        try {
            action.run();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static boolean awaitState(LuckPermsBackendPersistenceCapability capability,
                                      LuckPermsBackendPersistenceCapability.State expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (capability.state() == expected) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        return capability.state() == expected;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class FakeAdapter implements LuckPermsBackendPersistenceCapability.Adapter {
        private final boolean capable;
        private final Path root = Path.of("backend").toAbsolutePath().normalize();
        private final List<String> operations = new ArrayList<>();
        private IOException flushFailure;
        private LuckPermsBackendPersistenceCapability.Support supportOverride;
        private boolean invalidSupport;
        private boolean supportException;
        private boolean missingArtifact;
        private boolean remoteReceipt;
        private boolean resumeFailure;
        private boolean healthFailure;
        private IOException quiesceFailure;
        private IOException closeFailure;
        private int verifiedReceipts;
        private int closeAttempts;
        private LuckPermsBackendPersistenceCapability.BackendIdentity identity =
            new LuckPermsBackendPersistenceCapability.BackendIdentity("luckperms", "fake");
        private LuckPermsBackendPersistenceCapability.AdapterContract contract =
            new LuckPermsBackendPersistenceCapability.AdapterContract("fake", "1");
        private Path rebindFailureRoot;
        private boolean closed;

        private FakeAdapter(boolean capable) {
            this.capable = capable;
        }

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public Path activeRoot() {
            return root;
        }

        @Override
        public LuckPermsBackendPersistenceCapability.Support support() {
            if (supportException) {
                throw new IllegalStateException("support failed");
            }
            if (invalidSupport) {
                return null;
            }
            if (supportOverride == null && !capable) {
                return LuckPermsBackendPersistenceCapability.Support.unavailable(
                    "backend adapter does not support replacement");
            }
            if (supportOverride != null) {
                return supportOverride;
            }
            return capable ? LuckPermsBackendPersistenceCapability.Support.capable()
                : LuckPermsBackendPersistenceCapability.Support.unavailable("backend adapter does not support replacement");
        }

        @Override
        public LuckPermsBackendPersistenceCapability.BackendIdentity identity() {
            return identity;
        }

        @Override
        public LuckPermsBackendPersistenceCapability.AdapterContract contract() {
            return contract;
        }

        @Override
        public void flush() throws IOException {
            operations.add("flush");
            if (flushFailure != null) {
                throw flushFailure;
            }
        }

        @Override
        public void backup(Path snapshotRoot) {
        }

        @Override
        public LuckPermsBackendPersistenceCapability.SnapshotReceipt backup(Path snapshotRoot, long generation)
            throws IOException {
            operations.add("backup:" + snapshotRoot.toAbsolutePath().normalize());
            if (missingArtifact) {
                return LuckPermsBackendPersistenceCapability.SnapshotReceipt.local(identity, generation, contract,
                    "missing", snapshotRoot.resolveSibling("missing-artifact"));
            }
            Path artifact = Files.createTempFile("luckperms-snapshot", ".bin");
            Files.writeString(artifact, "snapshot", StandardCharsets.UTF_8);
            String hash;
            try {
                hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(artifact)));
            } catch (NoSuchAlgorithmException exception) {
                throw new IOException(exception);
            }
            if (remoteReceipt) {
                return LuckPermsBackendPersistenceCapability.SnapshotReceipt.remote(identity, generation, contract,
                    hash, "remote-receipt");
            }
            return LuckPermsBackendPersistenceCapability.SnapshotReceipt.local(identity, generation, contract, hash, artifact);
        }

        @Override
        public void restore(Path snapshotRoot) {
            operations.add("restore:" + snapshotRoot.toAbsolutePath().normalize());
        }

        @Override
        public void restore(LuckPermsBackendPersistenceCapability.SnapshotReceipt receipt) {
            operations.add("restore:" + (receipt.artifact() == null ? receipt.receiptId() : receipt.artifact()));
        }

        @Override
        public void rebind(Path activeRoot) {
            Path normalized = activeRoot.toAbsolutePath().normalize();
            operations.add("rebind:" + normalized);
            if (normalized.equals(rebindFailureRoot)) {
                throw new IllegalStateException("rebind failed");
            }
        }

        @Override
        public void rebind(Path activeRoot, LuckPermsBackendPersistenceCapability.SnapshotReceipt receipt) {
            rebind(activeRoot);
        }

        @Override
        public void verifySnapshot(LuckPermsBackendPersistenceCapability.SnapshotReceipt receipt) {
            if (remoteReceipt) {
                verifiedReceipts++;
            }
        }

        @Override
        public void quiesce() {
            operations.add("quiesce");
            if (quiesceFailure != null) {
                throw new RuntimeException(quiesceFailure);
            }
        }

        @Override
        public void resume() throws IOException {
            operations.add("resume");
            if (resumeFailure) {
                throw new IOException("resume failed");
            }
        }

        @Override
        public void healthCheck() throws IOException {
            operations.add("health");
            if (healthFailure) {
                throw new IOException("health failed");
            }
        }

        @Override
        public void close() throws IOException {
            closeAttempts++;
            if (closeFailure != null) {
                throw closeFailure;
            }
            closed = true;
        }
    }
}
