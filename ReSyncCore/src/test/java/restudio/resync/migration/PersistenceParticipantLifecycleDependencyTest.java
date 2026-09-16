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
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceParticipantLifecycleDependencyTest {
    @TempDir
    Path temporary;

    @Test
    void resumesFoundationsBeforeDependentsAndQuiescesDependentsFirst() throws Exception {
        List<String> calls = new ArrayList<>();
        State foundationState = new State();
        State mutationState = new State();
        TrackingParticipant foundation = participant(ProductionPersistenceOwners.FLOW_ASSETS, Set.of(),
            foundationState, calls, null);
        TrackingParticipant mutation = participant("resync.runtime.resource-mutations",
            Set.of(ProductionPersistenceOwners.FLOW_ASSETS), mutationState, calls, foundationState);
        PersistenceParticipantRegistry registry = registry(foundation, mutation);

        registry.quiesceAll();
        assertEquals(List.of("resync.runtime.resource-mutations:quiesce", "resync.flow.assets:quiesce"), calls);
        assertFalse(foundationState.open);
        assertFalse(mutationState.open);

        calls.clear();
        registry.resumeAll();

        assertEquals(List.of("resync.flow.assets:resume", "resync.runtime.resource-mutations:resume"), calls);
        assertTrue(foundationState.open);
        assertTrue(mutationState.open);
    }

    @Test
    void failedDependentResumeAtomicallyRequiescesTheOpenedFoundation() throws Exception {
        List<String> calls = new ArrayList<>();
        State foundationState = new State();
        State mutationState = new State();
        TrackingParticipant foundation = participant(ProductionPersistenceOwners.FLOW_ASSETS, Set.of(),
            foundationState, calls, null);
        TrackingParticipant mutation = participant("resync.runtime.resource-mutations",
            Set.of(ProductionPersistenceOwners.FLOW_ASSETS), mutationState, calls, foundationState);
        PersistenceParticipantRegistry registry = registry(foundation, mutation);
        registry.quiesceAll();
        calls.clear();
        mutationState.resumeFailure = new IOException("catalog recovery failed",
            new IllegalStateException("shared assets persistence is quiesced"));

        MigrationException failure = assertThrows(MigrationException.class, registry::resumeAll);

        assertEquals(List.of("resync.flow.assets:resume", "resync.runtime.resource-mutations:resume",
            "resync.flow.assets:quiesce"), calls);
        assertFalse(foundationState.open);
        assertFalse(mutationState.open);
        assertTrue(failure.getMessage().contains("shared assets persistence is quiesced"), failure::getMessage);
    }

    @Test
    void rejectsMissingDependenciesAndCyclesBeforeLifecycleMutation() throws Exception {
        List<String> missingCalls = new ArrayList<>();
        TrackingParticipant missing = participant("dependent", Set.of("missing"), new State(), missingCalls, null);
        PersistenceParticipantRegistry missingRegistry = registry(missing);

        assertThrows(MigrationException.class, missingRegistry::resumeAll);
        assertTrue(missingCalls.isEmpty());

        List<String> cycleCalls = new ArrayList<>();
        TrackingParticipant first = participant("first", Set.of("second"), new State(), cycleCalls, null);
        TrackingParticipant second = participant("second", Set.of("first"), new State(), cycleCalls, null);
        PersistenceParticipantRegistry cycleRegistry = registry(first, second);

        assertThrows(MigrationException.class, cycleRegistry::quiesceAll);
        assertTrue(cycleCalls.isEmpty());
    }

    @Test
    void keepsStableClassOrderingAfterDependencyConstraints() throws Exception {
        List<String> calls = new ArrayList<>();
        TrackingParticipant alpha = participant("alpha", Set.of(), new State(), calls, null);
        TrackingParticipant middle = participant("middle", Set.of("alpha"), new State(), calls, null);
        TrackingParticipant zulu = participant("zulu", Set.of(), new State(), calls, null);
        PersistenceParticipantRegistry registry = registry(alpha, middle, zulu);
        registry.quiesceAll();
        calls.clear();

        registry.resumeAll();

        assertEquals(List.of("zulu:resume", "alpha:resume", "middle:resume"), calls);
    }

    private PersistenceParticipantRegistry registry(TrackingParticipant... participants) throws IOException {
        Path root = Files.createDirectories(temporary.resolve("root-" + System.nanoTime()));
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(root);
        for (TrackingParticipant participant : participants) {
            participant.root = Files.createDirectory(root.resolve(participant.owner.replace('.', '-')));
            registry.register(participant);
        }
        return registry;
    }

    private static TrackingParticipant participant(String owner, Set<String> dependencies, State state,
                                                   List<String> calls, State requiredOpen) {
        return new TrackingParticipant(owner, dependencies, state, calls, requiredOpen);
    }

    private static final class State {
        private boolean open = true;
        private IOException resumeFailure;
    }

    private static final class TrackingParticipant implements RebindablePersistenceParticipant {
        private final String owner;
        private final Set<String> dependencies;
        private final State state;
        private final List<String> calls;
        private final State requiredOpen;
        private Path root;

        private TrackingParticipant(String owner, Set<String> dependencies, State state, List<String> calls,
                                    State requiredOpen) {
            this.owner = owner;
            this.dependencies = dependencies;
            this.state = state;
            this.calls = calls;
            this.requiredOpen = requiredOpen;
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
        public Set<String> resumeDependencies() {
            return dependencies;
        }

        @Override
        public void flush() {
        }

        @Override
        public void quiesce() {
            calls.add(owner + ":quiesce");
            state.open = false;
        }

        @Override
        public void resume() throws IOException {
            calls.add(owner + ":resume");
            if (requiredOpen != null && !requiredOpen.open) {
                throw new IOException("dependency is quiesced");
            }
            if (state.resumeFailure != null) {
                throw state.resumeFailure;
            }
            state.open = true;
        }

        @Override
        public void rebind(Path activeRoot) {
        }

        @Override
        public void healthCheck() {
        }
    }
}
