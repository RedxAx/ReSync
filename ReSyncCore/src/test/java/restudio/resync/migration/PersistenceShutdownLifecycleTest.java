package restudio.resync.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersistenceShutdownLifecycleTest {
    @TempDir
    Path temporary;

    @Test
    void closesOnlyAfterDeterministicFlushQuiesceAndHealthBoundary() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("data"));
        Path targetRoot = Files.createDirectory(temporary.resolve("target"));
        List<String> calls = new ArrayList<>();
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        TrackingParticipant participant = new TrackingParticipant("state", dataRoot, calls, false);
        coordinator.register(participant);
        coordinator.seal();
        Path activeRoot = coordinator.activeDataRoot();

        PersistenceShutdownStatus status = coordinator.quiesce();

        assertEquals(PersistenceShutdownStatus.State.QUIESCED, status.state());
        assertEquals(List.of("flush", "quiesce", "rebind", "health", "resume", "flush", "quiesce", "health"), calls);
        assertEquals(List.of("state"), status.flushedOwners());
        assertEquals(List.of("state"), status.quiescedOwners());
        assertFalse(coordinator.restoreReady());
        assertThrows(IllegalStateException.class, () -> coordinator.participants().rebindAll(targetRoot));
        assertThrows(IllegalStateException.class, () -> coordinator.register(
            new TrackingParticipant("later", dataRoot.resolve("later"), new ArrayList<>(), false)));
        assertThrows(IllegalStateException.class, coordinator.participants()::flushAll);

        PersistenceShutdownStatus closed = coordinator.close();

        assertEquals(PersistenceShutdownStatus.State.CLOSED, closed.state());
        assertEquals("CLOSED", closed.payload().get("state"));
        assertEquals(closed, coordinator.shutdownStatus());
        assertFalse(coordinator.restoreReady());
        assertEquals(activeRoot, participant.root());
    }

    @Test
    void rejectsNewAdmissionAtTheShutdownStartBoundary() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("admission-data"));
        Path targetRoot = Files.createDirectory(temporary.resolve("admission-target"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        coordinator.register(new TrackingParticipant("state", dataRoot, new ArrayList<>(), false));
        coordinator.seal();

        assertEquals(PersistenceShutdownStatus.State.QUIESCING, coordinator.beginShutdown().state());
        assertThrows(IllegalStateException.class, () -> coordinator.register(
            new TrackingParticipant("later", dataRoot.resolve("later"), new ArrayList<>(), false)));
        assertThrows(IllegalStateException.class, () -> coordinator.participants().rebindAll(targetRoot));
        assertThrows(IllegalStateException.class, coordinator.participants()::flushAll);
        assertThrows(IllegalStateException.class, coordinator.fence()::beginMutation);
        assertFalse(coordinator.restoreReady());

        assertEquals(PersistenceShutdownStatus.State.CLOSED, coordinator.close().state());
    }

    @Test
    void shutdownQuiescesDerivedCachesWithoutRequiringAnAuthoritativeFlush() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("derived-data"));
        List<String> calls = new ArrayList<>();
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        coordinator.register(new DerivedTrackingParticipant("derived", dataRoot, calls));
        coordinator.seal();
        calls.clear();

        PersistenceShutdownStatus status = coordinator.quiesce();

        assertEquals(PersistenceShutdownStatus.State.QUIESCED, status.state());
        assertEquals(List.of("quiesce"), calls);
        assertEquals(List.of(), status.flushedOwners());
        assertEquals(List.of("derived"), status.quiescedOwners());
        assertEquals(PersistenceShutdownStatus.State.CLOSED, coordinator.close().state());
    }

    @Test
    void failedHealthCheckClosesReadinessAndRetainsDeterministicFailureStatus() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("failed-data"));
        List<String> calls = new ArrayList<>();
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        TrackingParticipant participant = new TrackingParticipant("state", dataRoot, calls, false);
        coordinator.register(participant);
        coordinator.seal();
        participant.failHealthCheck();

        assertThrows(MigrationException.class, coordinator::close);

        PersistenceShutdownStatus status = coordinator.shutdownStatus();
        assertEquals(PersistenceShutdownStatus.State.FAILED, status.state());
        assertEquals(List.of("flush", "quiesce", "rebind", "health", "resume", "flush", "quiesce", "health", "resume"), calls);
        assertEquals(List.of("state"), status.flushedOwners());
        assertEquals(List.of("state"), status.quiescedOwners());
        assertEquals("health check: health failed", status.failures().get("state"));
        assertFalse(coordinator.restoreReady());
        assertThrows(IllegalStateException.class, () -> coordinator.participants().rebindAll(dataRoot));
        assertEquals(status, coordinator.close());
    }

    @Test
    void retriesAfterTransientHealthFailureOnlyAfterTheQuiescedParticipantResumes() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("retry-data"));
        Path authorityRoot = Files.createDirectory(dataRoot.resolve("authority"));
        Path dependentRoot = Files.createDirectory(dataRoot.resolve("dependent"));
        Files.writeString(authorityRoot.resolve("state.json"), "{}");
        Files.writeString(dependentRoot.resolve("state.json"), "{}");
        List<String> calls = new ArrayList<>();
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        TrackingParticipant authority = new TrackingParticipant("authority", dataRoot, Path.of("authority"), calls,
            Set.of());
        TrackingParticipant dependent = new TrackingParticipant("dependent", dataRoot, Path.of("dependent"), calls,
            Set.of("authority"));
        coordinator.register(authority);
        coordinator.register(dependent);
        coordinator.seal();
        calls.clear();
        authority.failHealthCheck();

        assertThrows(MigrationException.class, coordinator::close);
        assertFalse(authority.quiesced());
        assertFalse(dependent.quiesced());
        authority.allowHealthCheck();

        PersistenceShutdownStatus retried = coordinator.retryClose();

        assertEquals(PersistenceShutdownStatus.State.CLOSED, retried.state());
        assertEquals(List.of("authority:flush", "dependent:flush", "dependent:quiesce", "authority:quiesce",
            "authority:health", "authority:resume", "dependent:resume", "authority:flush", "dependent:flush",
            "dependent:quiesce", "authority:quiesce", "authority:health", "dependent:health"), calls);
        assertEquals("CLOSED", retried.payload().get("state"));
        assertEquals(retried, coordinator.shutdownStatus());
    }

    @Test
    void fenceFailureDuringShutdownIsTerminalAndFailClosed() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("fence-data"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        coordinator.register(new TrackingParticipant("state", dataRoot, new ArrayList<>(), false));
        coordinator.seal();

        try (MigrationFence.MutationLease ignored = coordinator.fence().beginMutation()) {
            assertThrows(IllegalStateException.class, coordinator::close);
        }

        PersistenceShutdownStatus status = coordinator.shutdownStatus();
        assertEquals(PersistenceShutdownStatus.State.FAILED, status.state());
        assertEquals("fence: A Mutation Cannot Acquire The Migration Fence", status.failures().get("coordinator"));
        assertFalse(coordinator.restoreReady());
        assertEquals(status, coordinator.close());
    }

    private ReSyncPersistenceCoordinator coordinator(Path dataRoot) throws IOException {
        return new ReSyncPersistenceCoordinator(dataRoot, temporary.resolve("coordination"), new MigrationFence());
    }

    private static final class TrackingParticipant implements RebindablePersistenceParticipant {
        private final String owner;
        private final Path scopeRoot;
        private final Path relativeRoot;
        private final List<String> calls;
        private final Set<String> dependencies;
        private boolean failHealth;
        private boolean quiesced;
        private Path activeRoot;

        private TrackingParticipant(String owner, Path scopeRoot, List<String> calls, boolean failHealth) {
            this(owner, scopeRoot, Path.of(""), calls, Set.of());
            this.failHealth = failHealth;
        }

        private TrackingParticipant(String owner, Path scopeRoot, Path relativeRoot, List<String> calls,
                                    Set<String> dependencies) {
            this.owner = owner;
            this.scopeRoot = scopeRoot;
            this.relativeRoot = relativeRoot;
            this.calls = calls;
            this.dependencies = Set.copyOf(dependencies);
            this.activeRoot = scopeRoot.resolve(relativeRoot).normalize();
        }

        private void allowHealthCheck() {
            failHealth = false;
        }

        private void failHealthCheck() {
            failHealth = true;
        }

        private boolean quiesced() {
            return quiesced;
        }

        private void record(String action) {
            calls.add(relativeRoot.toString().isEmpty() ? action : owner + ":" + action);
        }

        @Override
        public String owner() {
            return owner;
        }

        @Override
        public Path root() {
            return activeRoot;
        }

        @Override
        public Path rebindScope() {
            return scopeRoot;
        }

        @Override
        public Set<String> resumeDependencies() {
            return dependencies;
        }

        @Override
        public void flush() throws IOException {
            if (quiesced) {
                throw new IOException("flush rejected while quiesced");
            }
            record("flush");
        }

        @Override
        public void quiesce() {
            record("quiesce");
            quiesced = true;
        }

        @Override
        public void resume() {
            record("resume");
            quiesced = false;
        }

        @Override
        public void rebind(Path activeRoot) {
            this.activeRoot = activeRoot.resolve(relativeRoot).normalize();
            record("rebind");
        }

        @Override
        public void healthCheck() throws IOException {
            record("health");
            if (failHealth) {
                throw new IOException("health failed");
            }
        }
    }

    private static final class DerivedTrackingParticipant implements PersistenceParticipant {
        private final String owner;
        private final Path root;
        private final List<String> calls;

        private DerivedTrackingParticipant(String owner, Path root, List<String> calls) {
            this.owner = owner;
            this.root = root;
            this.calls = calls;
        }

        @Override
        public String owner() {
            return owner;
        }

        @Override
        public Path root() {
            return root;
        }

        @Override
        public PersistenceParticipantClassification classification() {
            return PersistenceParticipantClassification.DERIVED_CACHE;
        }

        @Override
        public void flush() throws IOException {
            throw new IOException("derived output is not flushable");
        }

        @Override
        public void quiesce() {
            calls.add("quiesce");
        }
    }
}
