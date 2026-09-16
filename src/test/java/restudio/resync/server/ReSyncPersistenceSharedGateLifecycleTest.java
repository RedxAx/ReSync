package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.RebindablePersistenceParticipant;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.storage.AssetPersistenceGate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncPersistenceSharedGateLifecycleTest {
    @TempDir
    Path temporary;

    @Test
    void sharedAssetGateReopensBeforeSealPublishesRestoreReadiness() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("runtime"));
        Files.createDirectory(dataRoot.resolve("assets"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        SharedGateParticipant participant = new SharedGateParticipant(dataRoot);

        ReSyncPersistenceTopology.Registration registration = register(coordinator, dataRoot, participant);

        assertTrue(registration.sealed(), registration.unavailableReasons().toString());
        assertTrue(coordinator.sealed());
        assertTrue(participant.gate.isOpen());
        assertOrdered(participant.lifecycle,
            "quiesce:open",
            "rebind:quiesced",
            "health:quiesced",
            "resume:quiesced");

        int beforeRestoreReadiness = participant.lifecycle.size();
        assertTrue(coordinator.restoreReady());
        assertTrue(participant.gate.isOpen());
        assertTrue(participant.lifecycle.subList(beforeRestoreReadiness, participant.lifecycle.size()).stream()
            .anyMatch("readiness:open"::equals));
        assertOrdered(participant.lifecycle, "resume:quiesced", "readiness:open");
    }

    @Test
    void failedResumeKeepsTheGateClosedUntilExplicitRecoveryAndSealRetry() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("retry-runtime"));
        Files.createDirectory(dataRoot.resolve("assets"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        SharedGateParticipant participant = new SharedGateParticipant(dataRoot);
        participant.failResume = true;

        ReSyncPersistenceTopology.Registration failed = register(coordinator, dataRoot, participant);

        assertFalse(failed.sealed());
        assertFalse(coordinator.sealed());
        assertFalse(coordinator.restoreReady());
        assertFalse(participant.gate.isOpen());
        assertTrue(participant.lifecycle.stream().anyMatch("resume:quiesced"::equals));
        assertFalse(participant.lifecycle.stream().anyMatch("readiness:open"::equals));

        participant.failResume = false;
        participant.resume();
        int rebindsBeforeRetry = participant.rebinds;

        coordinator.seal();

        assertTrue(coordinator.sealed());
        assertTrue(coordinator.restoreReady());
        assertTrue(participant.gate.isOpen());
        assertTrue(participant.rebinds > rebindsBeforeRetry);
        assertTrue(participant.lifecycle.stream().anyMatch("rebind:same-scope"::equals));
    }

    private ReSyncPersistenceCoordinator coordinator(Path dataRoot) throws IOException {
        return new ReSyncPersistenceCoordinator(dataRoot, temporary.resolve("coordination"), new MigrationFence());
    }

    private ReSyncPersistenceTopology.Registration register(ReSyncPersistenceCoordinator coordinator, Path dataRoot,
                                                             SharedGateParticipant participant) throws IOException {
        return ReSyncPersistenceTopology.register(coordinator, dataRoot, List.of(
            ReSyncPersistenceTopology.requiredForRestore(participant.owner(), participant.root(), participant)));
    }

    private void assertOrdered(List<String> values, String... expected) {
        int cursor = -1;
        for (String value : expected) {
            int found = values.subList(cursor + 1, values.size()).indexOf(value);
            assertTrue(found >= 0, () -> "Missing lifecycle event " + value + " in " + values);
            cursor += found + 1;
        }
    }

    private static final class SharedGateParticipant implements RebindablePersistenceParticipant {
        private static final String OWNER = "resync.flow.assets";
        private final AssetPersistenceGate gate;
        private final List<String> lifecycle = new ArrayList<>();
        private boolean failResume;
        private int rebinds;

        private SharedGateParticipant(Path scopeRoot) {
            gate = new AssetPersistenceGate(scopeRoot);
        }

        @Override
        public String owner() {
            return OWNER;
        }

        @Override
        public Path root() {
            return gate.scopeRoot().resolve("assets").toAbsolutePath().normalize();
        }

        @Override
        public Path rebindScope() {
            return gate.scopeRoot();
        }

        @Override
        public void flush() {
            lifecycle.add("flush:" + state());
            gate.requireOpen();
        }

        @Override
        public void quiesce() {
            lifecycle.add("quiesce:" + state());
            gate.quiesce();
        }

        @Override
        public void resume() throws IOException {
            lifecycle.add("resume:" + state());
            if (failResume) {
                gate.quiesce();
                throw new IOException("Shared asset child resume failed");
            }
            gate.resume();
        }

        @Override
        public void rebind(Path activeRoot) throws IOException {
            Path scope = activeRoot.toAbsolutePath().normalize();
            lifecycle.add("rebind:" + state());
            if (scope.equals(gate.scopeRoot())) {
                lifecycle.add("rebind:same-scope");
            }
            assertFalse(gate.isOpen());
            Files.createDirectories(scope.resolve("assets"));
            gate.rebind(scope);
            rebinds++;
        }

        @Override
        public void healthCheck() {
            lifecycle.add("health:" + state());
        }

        @Override
        public void readinessCheck() {
            lifecycle.add("readiness:" + state());
            if (gate.isOpen()) {
                gate.requireOpen();
            }
        }

        private String state() {
            return gate.isOpen() ? "open" : "quiesced";
        }
    }
}
