package restudio.resync.network.paper;

import restudio.resync.migration.MigrationException;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.MigrationPaths;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class NetworkPersistenceDrainController implements NetworkPersistenceParticipant.Controller {
    public static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(10);

    private final Object monitor = new Object();
    private final MigrationFence fence;
    private final Duration defaultTimeout;
    private final Map<String, RegisteredComponent> components = new LinkedHashMap<>();
    private final Map<String, RegisteredProducer> producers = new LinkedHashMap<>();
    private final Map<String, String> producerFailures = new LinkedHashMap<>();
    private final Map<String, String> expectedComponents = new LinkedHashMap<>();
    private Path persistenceRoot;
    private State state = State.OPEN;
    private int activeLeases;
    private long generation;
    private boolean drainAdmissionClosed;
    private String replacementOwner;
    private boolean retainReplacementRegistrations;

    public NetworkPersistenceDrainController(Path persistenceRoot) {
        this(persistenceRoot, MigrationFence.systemWide(), DEFAULT_DRAIN_TIMEOUT);
    }

    public NetworkPersistenceDrainController(Path persistenceRoot, MigrationFence fence, Duration defaultTimeout) {
        this.persistenceRoot = MigrationPaths.requirePath(persistenceRoot, "persistenceRoot");
        this.fence = Objects.requireNonNull(fence, "fence");
        this.defaultTimeout = requireTimeout(defaultTimeout, "defaultTimeout");
    }

    public Registration register(Component component) {
        Objects.requireNonNull(component, "component");
        String owner = requireText(component.owner(), "component owner");
        Path root = MigrationPaths.requirePath(component.activePath(), "component path");
        synchronized (monitor) {
            requireState(State.OPEN);
            if (components.containsKey(owner) || producers.containsKey(owner)) {
                throw new IllegalArgumentException("Network Persistence Component Is Already Registered: " + owner);
            }
            if (!root.startsWith(persistenceRoot) || root.equals(persistenceRoot)) {
                throw new IllegalArgumentException("Network Persistence Component Is Outside The Network Root: " + owner);
            }
            for (RegisteredComponent existing : components.values()) {
                Path existingRoot = MigrationPaths.requirePath(existing.activePath(), "component path");
                if (root.equals(existingRoot) || root.startsWith(existingRoot) || existingRoot.startsWith(root)) {
                    throw new IllegalArgumentException("Network Persistence Components Overlap: " + owner + " And " + existing.owner());
                }
            }
            Registration registration = new Registration(this, RegistrationKind.COMPONENT, owner);
            components.put(owner, new RegisteredComponent(owner, component, registration));
            return registration;
        }
    }

    public Registration replace(Registration previous, Component component) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(component, "component");
        String owner = requireText(component.owner(), "component owner");
        Path root = MigrationPaths.requirePath(component.activePath(), "component path");
        synchronized (monitor) {
            requireState(State.OPEN);
            requireReplacement();
            RegisteredComponent current = components.get(owner);
            if (current == null || current.registration() != previous || previous.kind() != RegistrationKind.COMPONENT) {
                throw new IllegalStateException("Network Persistence Component Replacement Is Not Owned: " + owner);
            }
            if (!root.startsWith(persistenceRoot) || root.equals(persistenceRoot)) {
                throw new IllegalArgumentException("Network Persistence Component Is Outside The Network Root: " + owner);
            }
            for (RegisteredComponent existing : components.values()) {
                if (existing.registration() == previous) {
                    continue;
                }
                Path existingRoot = MigrationPaths.requirePath(existing.activePath(), "component path");
                if (root.equals(existingRoot) || root.startsWith(existingRoot) || existingRoot.startsWith(root)) {
                    throw new IllegalArgumentException("Network Persistence Components Overlap: " + owner + " And " + existing.owner());
                }
            }
            Registration replacement = new Registration(this, RegistrationKind.COMPONENT, owner);
            components.put(owner, new RegisteredComponent(owner, component, replacement));
            return replacement;
        }
    }

    public void expectComponent(String owner) {
        String expected = requireText(owner, "expected component owner");
        synchronized (monitor) {
            if (state != State.OPEN) {
                throw new IllegalStateException("Network Persistence Drain Is " + state.name());
            }
            expectedComponents.put(expected, expected);
        }
    }

    public void expectComponents(Iterable<String> owners) {
        Objects.requireNonNull(owners, "owners");
        for (String owner : owners) {
            expectComponent(owner);
        }
    }

    public Registration registerProducer(Producer producer) {
        Objects.requireNonNull(producer, "producer");
        String owner = requireText(producer.owner(), "producer owner");
        synchronized (monitor) {
            requireState(State.OPEN);
            if (producers.containsKey(owner) || components.containsKey(owner)) {
                throw new IllegalArgumentException("Network Persistence Producer Is Already Registered: " + owner);
            }
            Registration registration = new Registration(this, RegistrationKind.PRODUCER, owner);
            producers.put(owner, new RegisteredProducer(owner, producer, registration));
            return registration;
        }
    }

    public Registration replaceProducer(Registration previous, Producer producer) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(producer, "producer");
        String owner = requireText(producer.owner(), "producer owner");
        synchronized (monitor) {
            requireState(State.OPEN);
            requireReplacement();
            RegisteredProducer current = producers.get(owner);
            if (current == null || current.registration() != previous || previous.kind() != RegistrationKind.PRODUCER) {
                throw new IllegalStateException("Network Persistence Producer Replacement Is Not Owned: " + owner);
            }
            Registration replacement = new Registration(this, RegistrationKind.PRODUCER, owner);
            producers.put(owner, new RegisteredProducer(owner, producer, replacement));
            return replacement;
        }
    }

    public boolean restore(Registration replacement, Registration previous, Component component) {
        Objects.requireNonNull(replacement, "replacement");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(component, "component");
        synchronized (monitor) {
            requireReplacement();
            if (replacement.controller() != this || previous.controller() != this
                || replacement.kind() != RegistrationKind.COMPONENT || previous.kind() != RegistrationKind.COMPONENT
                || !Objects.equals(replacement.owner(), previous.owner())) {
                return false;
            }
            RegisteredComponent current = components.get(replacement.owner());
            if (current == null || current.registration() != replacement) {
                return false;
            }
            components.put(previous.owner(), new RegisteredComponent(previous.owner(), component, previous));
            replacement.markClosed();
            return true;
        }
    }

    public boolean restoreProducer(Registration replacement, Registration previous, Producer producer) {
        Objects.requireNonNull(replacement, "replacement");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(producer, "producer");
        synchronized (monitor) {
            requireReplacement();
            if (replacement.controller() != this || previous.controller() != this
                || replacement.kind() != RegistrationKind.PRODUCER || previous.kind() != RegistrationKind.PRODUCER
                || !Objects.equals(replacement.owner(), previous.owner())) {
                return false;
            }
            RegisteredProducer current = producers.get(replacement.owner());
            if (current == null || current.registration() != replacement) {
                return false;
            }
            producers.put(previous.owner(), new RegisteredProducer(previous.owner(), producer, previous));
            replacement.markClosed();
            return true;
        }
    }

    public Lease acquire(String operation) {
        try {
            return tryAcquire(operation, defaultTimeout).orElseThrow(() -> new IllegalStateException("Network Persistence Admission Is Closed"));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Network Persistence Admission Was Interrupted", exception);
        }
    }

    public Optional<Lease> tryAcquire(String operation, Duration timeout) throws InterruptedException {
        String label = requireText(operation, "operation");
        Duration wait = requireTimeout(timeout, "timeout");
        synchronized (monitor) {
            if (state != State.OPEN) {
                return Optional.empty();
            }
        }
        Optional<MigrationFence.MutationLease> mutation = fence.tryBeginMutation(wait);
        if (mutation.isEmpty()) {
            return Optional.empty();
        }
        synchronized (monitor) {
            if (state != State.OPEN) {
                mutation.get().close();
                return Optional.empty();
            }
            activeLeases++;
            return Optional.of(new Lease(label, mutation.get()));
        }
    }

    public <T> CompletableFuture<T> track(String operation, CompletionStage<T> stage) {
        Objects.requireNonNull(stage, "stage");
        Lease lease = acquire(operation);
        try {
            return stage.toCompletableFuture().whenComplete((unused, throwable) -> lease.close());
        } catch (RuntimeException exception) {
            lease.close();
            throw exception;
        }
    }

    public <T> CompletableFuture<T> track(String operation, Supplier<? extends CompletionStage<T>> operationSupplier) {
        Lease lease = acquire(operation);
        try {
            CompletionStage<T> stage = Objects.requireNonNull(operationSupplier.get(), "operation stage");
            return stage.toCompletableFuture().whenComplete((unused, throwable) -> lease.close());
        } catch (RuntimeException exception) {
            lease.close();
            throw exception;
        }
    }

    public <T> CompletableFuture<T> trackDuringDrain(String operation, CompletionStage<T> stage) {
        Objects.requireNonNull(stage, "stage");
        String label = requireText(operation, "operation");
        synchronized (monitor) {
            if (state != State.QUIESCING || drainAdmissionClosed) {
                throw new IllegalStateException("Network Persistence Drain Is " + state.name());
            }
            activeLeases++;
        }
        Lease lease = new Lease(label, null);
        try {
            return stage.toCompletableFuture().whenComplete((unused, throwable) -> lease.close());
        } catch (RuntimeException exception) {
            lease.close();
            throw exception;
        }
    }

    @Override
    public Path persistenceRoot() {
        synchronized (monitor) {
            return persistenceRoot;
        }
    }

    @Override
    public void flushPersistence() throws IOException {
        if (state() == State.CLOSED) {
            return;
        }
        List<Component> registered = componentsSnapshot();
        for (Component component : registered) {
            component.flush();
        }
    }

    @Override
    public void quiescePersistence() throws IOException {
        quiescePersistence(defaultTimeout);
    }

    public void beginShutdown() throws IOException {
        synchronized (monitor) {
            if (state == State.QUIESCED || state == State.CLOSED) {
                return;
            }
            if (state == State.FAILED) {
                throw new IOException("Network Persistence Drain Is FAILED");
            }
            if (state == State.OPEN) {
                state = State.QUIESCING;
                drainAdmissionClosed = false;
                generation++;
            }
        }
        IOException failure = null;
        for (Producer producer : producersSnapshot()) {
            try {
                producer.closeAdmission();
            } catch (IOException | RuntimeException exception) {
                failure = append(failure, "close admission failed for " + producer.owner(), exception);
            }
        }
        if (failure != null) {
            synchronized (monitor) {
                drainAdmissionClosed = true;
                state = State.FAILED;
                monitor.notifyAll();
            }
            throw failure;
        }
    }

    public void quiescePersistence(Duration timeout) throws IOException {
        Duration wait = requireTimeout(timeout, "timeout");
        synchronized (monitor) {
            if (state == State.QUIESCED || state == State.CLOSED) {
                return;
            }
            if (state != State.OPEN && state != State.QUIESCING) {
                throw new IOException("Network Persistence Drain Is " + state.name());
            }
            if (state == State.OPEN) {
                state = State.QUIESCING;
                drainAdmissionClosed = false;
                generation++;
            }
        }
        IOException failure = null;
        long deadline = System.nanoTime() + wait.toNanos();
        try {
            for (Producer producer : producersSnapshot()) {
                try {
                    producer.closeAdmission();
                } catch (IOException | RuntimeException exception) {
                    failure = append(failure, "close admission failed for " + producer.owner(), exception);
                }
            }
            try {
                IOException abortFailure = abortProducers(deadline, "durable abort");
                if (abortFailure != null) {
                    failure = append(failure, "network persistence durable abort failed", abortFailure);
                }
            } catch (RuntimeException exception) {
                failure = append(failure, "network persistence durable abort failed", exception);
            }
            try {
                if (!awaitDrained(deadline)) {
                    failure = append(failure, "network persistence drain wait timed out", new IOException("Network Persistence Drain Timed Out"));
                }
            } catch (IOException | RuntimeException exception) {
                failure = append(failure, "network persistence drain wait failed", exception);
            }
            if (failure == null) {
                Optional<MigrationFence.MigrationLease> migration;
                try {
                    migration = fence.tryAcquireMigration(remaining(deadline));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    failure = append(failure, "network persistence migration fence was interrupted", exception);
                    migration = Optional.empty();
                }
                if (migration.isEmpty()) {
                    if (failure == null) {
                        failure = new IOException("Network Persistence Migration Fence Timed Out");
                    }
                } else {
                    try {
                        for (Component component : componentsSnapshot()) {
                            component.quiesce();
                        }
                        for (Component component : componentsSnapshot()) {
                            component.flush();
                        }
                    } catch (IOException | RuntimeException exception) {
                        failure = append(failure, "component quiesce failed", exception);
                    } finally {
                        migration.get().close();
                    }
                }
            }
        } finally {
            if (failure != null) {
                synchronized (monitor) {
                    drainAdmissionClosed = true;
                    state = State.FAILED;
                    monitor.notifyAll();
                }
            } else {
                synchronized (monitor) {
                    drainAdmissionClosed = true;
                    state = State.QUIESCED;
                    monitor.notifyAll();
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public void recoverPersistence(Duration timeout) throws IOException {
        Duration wait = requireTimeout(timeout, "timeout");
        synchronized (monitor) {
            if (state != State.FAILED) {
                throw new IOException("Network Persistence Drain Is " + state.name());
            }
            state = State.QUIESCING;
            drainAdmissionClosed = false;
        }
        IOException failure = null;
        long deadline = System.nanoTime() + wait.toNanos();
        try {
            for (Producer producer : producersSnapshot()) {
                try {
                    producer.closeAdmission();
                } catch (IOException | RuntimeException exception) {
                    failure = append(failure, "recovery close admission failed for " + producer.owner(), exception);
                }
            }
            try {
                IOException abortFailure = abortProducers(deadline, "durable recovery abort");
                if (abortFailure != null) {
                    failure = append(failure, "network persistence durable recovery abort failed", abortFailure);
                }
            } catch (RuntimeException exception) {
                failure = append(failure, "network persistence durable recovery abort failed", exception);
            }
            try {
                if (!awaitDrained(deadline)) {
                    failure = append(failure, "network persistence recovery drain wait timed out", new IOException("Network Persistence Recovery Timed Out"));
                }
            } catch (IOException | RuntimeException exception) {
                failure = append(failure, "network persistence recovery drain wait failed", exception);
            }
            if (failure == null) {
                Optional<MigrationFence.MigrationLease> migration;
                try {
                    migration = fence.tryAcquireMigration(remaining(deadline));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    failure = append(failure, "network persistence recovery migration fence was interrupted", exception);
                    migration = Optional.empty();
                }
                if (migration.isEmpty()) {
                    if (failure == null) {
                        failure = new IOException("Network Persistence Recovery Migration Fence Timed Out");
                    }
                } else {
                    try {
                        for (Component component : componentsSnapshot()) {
                            component.quiesce();
                            component.flush();
                        }
                    } catch (IOException | RuntimeException exception) {
                        failure = append(failure, "component recovery failed", exception);
                    } finally {
                        migration.get().close();
                    }
                }
            }
        } finally {
            synchronized (monitor) {
                drainAdmissionClosed = true;
                state = failure == null ? State.QUIESCED : State.FAILED;
                monitor.notifyAll();
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public void retryDegradedPersistence(Duration timeout) throws IOException {
        Duration wait = requireTimeout(timeout, "timeout");
        synchronized (monitor) {
            if (state == State.CLOSED) {
                return;
            }
            if (state != State.QUIESCED) {
                throw new IOException("Network Persistence Drain Is " + state.name());
            }
            if (producerFailures.isEmpty()) {
                return;
            }
            state = State.QUIESCING;
            drainAdmissionClosed = false;
            generation++;
        }
        quiescePersistence(wait);
    }

    @Override
    public void resumePersistence() throws IOException {
        synchronized (monitor) {
            if (state == State.OPEN) {
                return;
            }
            if (state != State.QUIESCED) {
                throw new IOException("Network Persistence Drain Is " + state.name());
            }
        }
        IOException failure = null;
        List<Component> registered = componentsSnapshot();
        List<Component> resumedComponents = new ArrayList<>();
        for (int index = registered.size() - 1; index >= 0; index--) {
            Component component = registered.get(index);
            resumedComponents.add(component);
            try {
                component.resume();
            } catch (IOException | RuntimeException exception) {
                failure = append(failure, "component resume failed for " + component.owner(), exception);
                break;
            }
        }
        List<Producer> resumedProducers = new ArrayList<>();
        if (failure == null) {
            for (Producer producer : producersSnapshot()) {
                resumedProducers.add(producer);
                try {
                    producer.resumeAdmission();
                } catch (IOException | RuntimeException exception) {
                    failure = append(failure, "producer resume failed for " + producer.owner(), exception);
                    break;
                }
            }
        }
        if (failure != null) {
            IOException compensation = compensateResume(resumedComponents, resumedProducers);
            if (compensation != null) {
                failure.addSuppressed(compensation);
            }
            synchronized (monitor) {
                drainAdmissionClosed = true;
                state = State.FAILED;
                monitor.notifyAll();
            }
            throw failure;
        }
        synchronized (monitor) {
            state = State.OPEN;
            drainAdmissionClosed = false;
            producerFailures.clear();
            generation++;
            retireDeferredRegistrations();
            monitor.notifyAll();
        }
    }

    private IOException compensateResume(List<Component> resumedComponents, List<Producer> resumedProducers) {
        IOException failure = null;
        for (int index = resumedProducers.size() - 1; index >= 0; index--) {
            Producer producer = resumedProducers.get(index);
            try {
                producer.closeAdmission();
            } catch (IOException | RuntimeException exception) {
                failure = append(failure, "producer resume compensation failed for " + producer.owner(), exception);
            }
        }
        for (int index = resumedComponents.size() - 1; index >= 0; index--) {
            Component component = resumedComponents.get(index);
            try {
                component.quiesce();
            } catch (IOException | RuntimeException exception) {
                failure = append(failure, "component resume compensation failed for " + component.owner(), exception);
            }
        }
        return failure;
    }

    @Override
    public void rebindPersistence(Path activeRoot) throws IOException {
        Path candidate = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        Path previous;
        synchronized (monitor) {
            if (state != State.QUIESCED) {
                throw new IOException("Network Persistence Must Be Quiesced Before Rebind");
            }
            previous = persistenceRoot;
        }
        List<Component> registered = componentsSnapshot();
        for (Component component : registered) {
            component.validateRebind(candidate);
        }
        List<Component> attempted = new ArrayList<>();
        try {
            for (Component component : registered) {
                attempted.add(component);
                component.rebind(candidate);
            }
            for (Component component : registered) {
                component.healthCheck();
            }
        } catch (IOException | RuntimeException exception) {
            IOException rollbackFailure = null;
            for (Component component : attempted.reversed()) {
                try {
                    component.rebind(previous);
                } catch (IOException | RuntimeException rollback) {
                    rollbackFailure = append(rollbackFailure, "rebind rollback failed for " + component.owner(), rollback);
                }
            }
            if (rollbackFailure != null) {
                exception.addSuppressed(rollbackFailure);
                synchronized (monitor) {
                    state = State.FAILED;
                }
            }
            throw new MigrationException("Network Persistence Rebind Failed", exception);
        }
        synchronized (monitor) {
            persistenceRoot = candidate;
            generation++;
        }
    }

    @Override
    public void healthCheckPersistence() throws IOException {
        Health health = health();
        if (health.state() == State.CLOSED) {
            return;
        }
        if (health.state() != State.OPEN && health.state() != State.QUIESCED) {
            throw new IOException("Network Persistence Drain Is " + health.state().name());
        }
        if (!health.failures().isEmpty()) {
            throw new IOException("Network Persistence Health Check Failed: " + health.failures());
        }
    }

    public Health health() {
        Map<String, String> failures = new LinkedHashMap<>();
        for (Component component : componentsSnapshot()) {
            try {
                component.healthCheck();
            } catch (IOException | RuntimeException exception) {
                failures.put(component.owner(), reason(exception));
            }
        }
        State current;
        int active;
        long currentGeneration;
        synchronized (monitor) {
            current = state;
            active = activeLeases;
            currentGeneration = generation;
        }
        synchronized (monitor) {
            failures.putAll(producerFailures);
            for (String owner : expectedComponents.keySet()) {
                if (!components.containsKey(owner)) {
                    failures.put(owner, "Expected network persistence component is not registered");
                }
            }
        }
        return new Health((current == State.OPEN || current == State.QUIESCED) && failures.isEmpty(), current, active, currentGeneration, Map.copyOf(failures));
    }

    public State state() {
        synchronized (monitor) {
            return state;
        }
    }

    public int activeLeaseCount() {
        synchronized (monitor) {
            return activeLeases;
        }
    }

    public CompletableFuture<Void> awaitIdle(Duration timeout) {
        Duration wait = requireTimeout(timeout, "timeout");
        CompletableFuture<Void> result = new CompletableFuture<>();
        awaitIdle(result, System.nanoTime() + wait.toNanos());
        return result;
    }

    private void awaitIdle(CompletableFuture<Void> result, long deadline) {
        if (result.isDone()) {
            return;
        }
        synchronized (monitor) {
            if (activeLeases == 0) {
                result.complete(null);
                return;
            }
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            result.completeExceptionally(new TimeoutException("Network Persistence Drain Still Has Active Leases"));
            return;
        }
        long delay = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25));
        CompletableFuture.delayedExecutor(Math.max(1, delay), TimeUnit.NANOSECONDS).execute(() -> awaitIdle(result, deadline));
    }

    public long generation() {
        synchronized (monitor) {
            return generation;
        }
    }

    public int componentCount() {
        synchronized (monitor) {
            return components.size();
        }
    }

    public int producerCount() {
        synchronized (monitor) {
            return producers.size();
        }
    }

    public List<String> expectedComponentOwners() {
        synchronized (monitor) {
            return List.copyOf(expectedComponents.keySet());
        }
    }

    public boolean unregister(Registration registration) {
        return registration != null && registration.controller() == this && unregisterRegistration(registration);
    }

    private boolean unregisterRegistration(Registration registration) {
        synchronized (monitor) {
            if (registration.closed()) {
                return false;
            }
            if (state != State.OPEN && state != State.CLOSED) {
                registration.requestRetirement();
                return false;
            }
            registration.markClosed();
            if (registration.kind() == RegistrationKind.COMPONENT) {
                RegisteredComponent current = components.get(registration.owner());
                if (current != null && current.registration() == registration) {
                    components.remove(registration.owner());
                    return true;
                }
            } else {
                RegisteredProducer current = producers.get(registration.owner());
                if (current != null && current.registration() == registration) {
                    producers.remove(registration.owner());
                    return true;
                }
            }
            return false;
        }
    }

    private void retireDeferredRegistrations() {
        components.entrySet().removeIf(entry -> {
            Registration registration = entry.getValue().registration();
            if (!registration.retirementRequested()) {
                return false;
            }
            registration.markClosed();
            return true;
        });
        producers.entrySet().removeIf(entry -> {
            Registration registration = entry.getValue().registration();
            if (!registration.retirementRequested()) {
                return false;
            }
            registration.markClosed();
            return true;
        });
    }

    public void closePersistence() {
        synchronized (monitor) {
            if (state == State.CLOSED) {
                return;
            }
            if (replacementOwner != null) {
                throw new IllegalStateException("Network Persistence Replacement Is In Progress: " + replacementOwner);
            }
            state = State.CLOSED;
            drainAdmissionClosed = true;
            components.values().forEach(entry -> entry.registration().markClosed());
            producers.values().forEach(entry -> entry.registration().markClosed());
            components.clear();
            producers.clear();
            producerFailures.clear();
            expectedComponents.clear();
            replacementOwner = null;
            retainReplacementRegistrations = false;
            monitor.notifyAll();
        }
    }

    public ReplacementLease beginReplacement(String owner) throws IOException {
        String normalized = requireText(owner, "replacement owner");
        synchronized (monitor) {
            if (state != State.OPEN) {
                throw new IOException("Network Persistence Replacement Requires An Open Controller");
            }
            if (replacementOwner != null) {
                throw new IOException("Network Persistence Replacement Is Already In Progress: " + replacementOwner);
            }
            replacementOwner = normalized;
            return new ReplacementLease(this, normalized);
        }
    }

    private void finishReplacement(String owner) {
        synchronized (monitor) {
            if (Objects.equals(replacementOwner, owner)) {
                replacementOwner = null;
                retainReplacementRegistrations = false;
                monitor.notifyAll();
            }
        }
    }

    public void retainReplacementRegistrations() throws IOException {
        synchronized (monitor) {
            if (replacementOwner == null) {
                throw new IOException("Network Persistence Replacement Is Not Active");
            }
            retainReplacementRegistrations = true;
        }
    }

    public boolean retainReplacementRegistrationsOnFinalize() {
        synchronized (monitor) {
            return replacementOwner != null && retainReplacementRegistrations;
        }
    }

    private boolean registrationActive(Registration registration) {
        synchronized (monitor) {
            if (registration.closed()) {
                return false;
            }
            if (registration.kind() == RegistrationKind.COMPONENT) {
                RegisteredComponent current = components.get(registration.owner());
                return current != null && current.registration() == registration;
            }
            RegisteredProducer current = producers.get(registration.owner());
            return current != null && current.registration() == registration;
        }
    }

    private boolean awaitDrained(long deadline) throws IOException {
        synchronized (monitor) {
            while (activeLeases > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    drainAdmissionClosed = true;
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Network Persistence Drain Was Interrupted", exception);
                }
            }
            drainAdmissionClosed = true;
            return true;
        }
    }

    private List<Component> componentsSnapshot() {
        synchronized (monitor) {
            return components.values().stream()
                .sorted(Comparator.comparing(RegisteredComponent::owner))
                .map(component -> (Component) component)
                .toList();
        }
    }

    private List<Producer> producersSnapshot() {
        synchronized (monitor) {
            return producers.values().stream()
                .sorted(Comparator.comparing(RegisteredProducer::owner))
                .map(producer -> (Producer) producer)
                .toList();
        }
    }

    private IOException abortProducers(long deadline, String phase) {
        Map<String, CompletableFuture<AbortResult>> pending = new LinkedHashMap<>();
        IOException failure = null;
        for (Producer producer : producersSnapshot()) {
            try {
                CompletionStage<AbortResult> stage = Objects.requireNonNull(producer.abortPending(), "abort stage");
                pending.put(producer.owner(), stage.toCompletableFuture());
            } catch (IOException | RuntimeException exception) {
                failure = append(failure, phase + " failed for " + producer.owner(), exception);
            }
        }
        for (Map.Entry<String, CompletableFuture<AbortResult>> entry : pending.entrySet()) {
            try {
                AbortResult result = entry.getValue().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (result == null || !result.durable()) {
                    failure = append(failure, phase + " was not durable for " + entry.getKey(), new IOException(result == null ? "missing abort result" : result.detail()));
                } else if (result.degraded()) {
                    synchronized (monitor) {
                        producerFailures.put(entry.getKey(), result.detail());
                    }
                } else {
                    synchronized (monitor) {
                        producerFailures.remove(entry.getKey());
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                failure = append(failure, phase + " was interrupted for " + entry.getKey(), exception);
            } catch (ExecutionException | TimeoutException exception) {
                failure = append(failure, phase + " timed out for " + entry.getKey(), exception);
            }
        }
        return failure;
    }

    private void release(Lease lease) {
        if (lease.mutation != null) {
            lease.mutation.close();
        }
        synchronized (monitor) {
            activeLeases--;
            monitor.notifyAll();
        }
    }

    private void requireState(State expected) {
        if (state != expected) {
            throw new IllegalStateException("Network Persistence Drain Is " + state.name() + "; Expected " + expected.name());
        }
    }

    private void requireReplacement() {
        if (replacementOwner == null) {
            throw new IllegalStateException("Network Persistence Replacement Is Not Active");
        }
    }

    private static Duration requireTimeout(Duration timeout, String name) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException(name + " Must Be Non-Negative");
        }
        return timeout;
    }

    private static Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
    }

    private static IOException append(IOException primary, String message, Throwable failure) {
        IOException next = primary == null ? new IOException(message, failure) : primary;
        if (primary != null) {
            next.addSuppressed(failure);
        }
        return next;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " Must Not Be Blank");
        }
        return value.trim();
    }

    private static String reason(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private enum RegistrationKind {
        COMPONENT,
        PRODUCER
    }

    private record RegisteredComponent(String owner, Component component, Registration registration) implements Component {
        @Override
        public Path activePath() {
            return component.activePath();
        }

        @Override
        public void flush() throws IOException {
            component.flush();
        }

        @Override
        public void quiesce() throws IOException {
            component.quiesce();
        }

        @Override
        public void resume() throws IOException {
            component.resume();
        }

        @Override
        public void validateRebind(Path candidateNetworkRoot) throws IOException {
            component.validateRebind(candidateNetworkRoot);
        }

        @Override
        public void rebind(Path candidateNetworkRoot) throws IOException {
            component.rebind(candidateNetworkRoot);
        }

        @Override
        public void healthCheck() throws IOException {
            component.healthCheck();
        }
    }

    private record RegisteredProducer(String owner, Producer producer, Registration registration) implements Producer {
        @Override
        public void closeAdmission() throws IOException {
            producer.closeAdmission();
        }

        @Override
        public CompletionStage<AbortResult> abortPending() throws IOException {
            return producer.abortPending();
        }

        @Override
        public void resumeAdmission() throws IOException {
            producer.resumeAdmission();
        }
    }

    public static final class Registration implements AutoCloseable {
        private final NetworkPersistenceDrainController controller;
        private final RegistrationKind kind;
        private final String owner;
        private boolean closed;
        private boolean retirementRequested;

        private Registration(NetworkPersistenceDrainController controller, RegistrationKind kind, String owner) {
            this.controller = controller;
            this.kind = kind;
            this.owner = owner;
        }

        public String owner() {
            return owner;
        }

        public boolean active() {
            return controller.registrationActive(this);
        }

        public boolean retirementPending() {
            synchronized (controller.monitor) {
                return retirementRequested;
            }
        }

        @Override
        public void close() {
            controller.unregisterRegistration(this);
        }

        private NetworkPersistenceDrainController controller() {
            return controller;
        }

        private RegistrationKind kind() {
            return kind;
        }

        private boolean closed() {
            return closed;
        }

        private void markClosed() {
            closed = true;
        }

        private boolean retirementRequested() {
            return retirementRequested;
        }

        private void requestRetirement() {
            retirementRequested = true;
        }
    }

    public static final class ReplacementLease implements AutoCloseable {
        private final NetworkPersistenceDrainController controller;
        private final String owner;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ReplacementLease(NetworkPersistenceDrainController controller, String owner) {
            this.controller = controller;
            this.owner = owner;
        }

        public String owner() {
            return owner;
        }

        public boolean active() {
            synchronized (controller.monitor) {
                return !closed.get() && Objects.equals(controller.replacementOwner, owner);
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                controller.finishReplacement(owner);
            }
        }
    }

    public enum State {
        OPEN,
        QUIESCING,
        QUIESCED,
        FAILED,
        CLOSED
    }

    public record Health(boolean available, State state, int activeLeases, long generation, Map<String, String> failures) {
        public Health {
            failures = Map.copyOf(failures == null ? Map.of() : failures);
        }
    }

    public interface Component {
        String owner();

        Path activePath();

        void flush() throws IOException;

        void quiesce() throws IOException;

        void resume() throws IOException;

        void validateRebind(Path candidateNetworkRoot) throws IOException;

        void rebind(Path candidateNetworkRoot) throws IOException;

        void healthCheck() throws IOException;
    }

    public interface Producer {
        String owner();

        void closeAdmission() throws IOException;

        default CompletionStage<AbortResult> abortPending() throws IOException {
            return CompletableFuture.completedFuture(AbortResult.notNeeded());
        }

        default void resumeAdmission() throws IOException {
        }
    }

    public record AbortResult(boolean durable, boolean degraded, String detail) {
        public AbortResult {
            detail = detail == null ? "" : detail.trim();
        }

        public static AbortResult notNeeded() {
            return new AbortResult(true, false, "");
        }

        public static AbortResult retained(String detail) {
            return new AbortResult(true, true, detail);
        }

        public static AbortResult completed() {
            return new AbortResult(true, false, "");
        }
    }

    public final class Lease implements AutoCloseable {
        private final String operation;
        private final MigrationFence.MutationLease mutation;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(String operation, MigrationFence.MutationLease mutation) {
            this.operation = operation;
            this.mutation = mutation;
        }

        public String operation() {
            return operation;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release(this);
            }
        }
    }
}
