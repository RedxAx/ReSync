package restudio.resync.modules;

import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleRegistryShutdownLifecycleTest {
    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void admitsEveryBukkitFacingModuleStopOnThePrimaryThreadBeforeAsyncDrain() throws Exception {
        MockBukkit.mock();
        ModuleRegistry registry = new ModuleRegistry();
        List<String> lifecycle = new ArrayList<>();
        List<Boolean> primary = new ArrayList<>();
        CompletableFuture<Void> flowDrain = new CompletableFuture<>();
        AtomicBoolean flowDrained = new AtomicBoolean();
        registry.registerModule(module("playerNpcPackets", lifecycle, primary, CompletableFuture.completedFuture(null), flowDrained, false));
        registry.registerModule(module("flow", lifecycle, primary, flowDrain, flowDrained, true));
        registry.initializeModules(null);

        CompletableFuture<Void> shutdown = registry.shutdownModulesAsync(null).toCompletableFuture();

        assertEquals(List.of("flow.prepare", "playerNpcPackets.prepare", "flow.finish"), lifecycle);
        assertTrue(primary.stream().allMatch(Boolean.TRUE::equals));
        assertFalse(shutdown.isDone());

        flowDrained.set(true);
        flowDrain.complete(null);
        shutdown.get(1, TimeUnit.SECONDS);
        assertEquals(List.of("flow.prepare", "playerNpcPackets.prepare", "flow.finish", "playerNpcPackets.finish"), lifecycle);
    }

    @Test
    void failedModuleShutdownCanBeRetriedWithoutLosingTheStartOrder() {
        ModuleRegistry registry = new ModuleRegistry();
        AtomicInteger attempts = new AtomicInteger();
        registry.registerModule(new Module() {
            @Override
            public ModuleMetadata getMetadata() {
                return ModuleMetadata.of("transient", "Transient");
            }

            @Override
            public CompletionStage<Void> finishStopAsync(ModuleContext context) {
                return attempts.incrementAndGet() == 1
                    ? CompletableFuture.failedFuture(new IllegalStateException("transient stop failure"))
                    : CompletableFuture.completedFuture(null);
            }
        });
        registry.initializeModules(null);

        assertTrue(registry.shutdownModulesAsync(null).toCompletableFuture().isCompletedExceptionally());
        registry.shutdownModulesAsync(null).toCompletableFuture().join();

        assertEquals(2, attempts.get());
        assertTrue(registry.getModules().isEmpty());
    }

    @Test
    void failedRuntimeRegistrationRetainsIdentityUntilItsPhysicalDrainCompletes() {
        ModuleRegistry registry = new ModuleRegistry();
        registry.initializeModules(null);
        registry.startModules(null);
        CompletableFuture<Void> drain = new CompletableFuture<>();
        AtomicInteger preparations = new AtomicInteger();
        Module candidate = new Module() {
            @Override
            public ModuleMetadata getMetadata() {
                return ModuleMetadata.of("candidate", "Candidate");
            }

            @Override
            public void initialize(ModuleContext context) {
                throw new IllegalStateException("Initialization Failed");
            }

            @Override
            public void prepareStop(ModuleContext context) {
                preparations.incrementAndGet();
            }

            @Override
            public CompletionStage<Void> finishStopAsync(ModuleContext context) {
                return drain;
            }
        };

        assertThrows(IllegalStateException.class, () -> registry.registerRuntimeModule(candidate, null));
        assertTrue(registry.hasModule("candidate"));
        assertTrue(registry.isRuntimeShutdownPending());
        assertTrue(registry.getModules().isEmpty());
        assertThrows(IllegalStateException.class, () -> registry.registerRuntimeModule(
            new RecordingModule("candidate", new ArrayList<>(), false, false), null));
        drain.complete(null);
        registry.retryRuntimeModuleStops();

        assertFalse(registry.hasModule("candidate"));
        assertFalse(registry.isRuntimeShutdownPending());
        assertEquals(1, preparations.get());
    }

    @Test
    void failedModulePreparationIsRetriedBeforeFinish() {
        ModuleRegistry registry = new ModuleRegistry();
        AtomicInteger preparations = new AtomicInteger();
        AtomicInteger finishes = new AtomicInteger();
        registry.registerModule(new Module() {
            @Override
            public ModuleMetadata getMetadata() {
                return ModuleMetadata.of("transient", "Transient");
            }

            @Override
            public void prepareStop(ModuleContext context) {
                if (preparations.incrementAndGet() == 1) {
                    throw new IllegalStateException("transient preparation failure");
                }
            }

            @Override
            public CompletionStage<Void> finishStopAsync(ModuleContext context) {
                finishes.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }
        });
        registry.initializeModules(null);

        assertTrue(registry.shutdownModulesAsync(null).toCompletableFuture().isCompletedExceptionally());
        assertEquals(1, preparations.get());
        assertEquals(0, finishes.get());
        registry.shutdownModulesAsync(null).toCompletableFuture().join();

        assertEquals(2, preparations.get());
        assertEquals(1, finishes.get());
        assertTrue(registry.getModules().isEmpty());
    }

    @Test
    void initializationAndStartAreSeparateAndIdempotent() {
        ModuleRegistry registry = new ModuleRegistry();
        List<String> lifecycle = new ArrayList<>();
        registry.registerModule(new RecordingModule("dependency", lifecycle, false, false));
        registry.registerModule(new RecordingModule("consumer", lifecycle, false, false)
            .withDependencies("dependency"));

        registry.initializeModules(null);
        registry.initializeModules(null);

        assertEquals(List.of("dependency.initialize", "consumer.initialize"), lifecycle);

        registry.startModules(null);
        registry.startModules(null);

        assertEquals(List.of(
            "dependency.initialize", "consumer.initialize",
            "dependency.start", "consumer.start"), lifecycle);
    }

    @Test
    void initializationFailureRollsBackOnlyInitializedModulesAndClosesLifecycle() {
        ModuleRegistry registry = new ModuleRegistry();
        List<String> lifecycle = new ArrayList<>();
        registry.registerModule(new RecordingModule("first", lifecycle, false, false));
        registry.registerModule(new RecordingModule("failing", lifecycle, true, false));
        registry.registerModule(new RecordingModule("last", lifecycle, false, false));

        assertThrows(IllegalStateException.class, () -> registry.initializeModules(null));
        assertEquals(List.of(
            "first.initialize", "failing.initialize",
            "failing.stop", "first.stop"), lifecycle);
        assertThrows(IllegalStateException.class, () -> registry.initializeModules(null));
        registry.shutdownModules(null);
        assertEquals(List.of(
            "first.initialize", "failing.initialize",
            "failing.stop", "first.stop"), lifecycle);
    }

    @Test
    void initializationFailureRetainsAsyncDrainUntilShutdownCompletes() {
        ModuleRegistry registry = new ModuleRegistry();
        List<String> lifecycle = new ArrayList<>();
        CompletableFuture<Void> drain = new CompletableFuture<>();
        registry.registerModule(module("flow", lifecycle, new ArrayList<>(), drain, new AtomicBoolean(), true));
        registry.registerModule(new RecordingModule("failing", lifecycle, true, false));

        assertThrows(IllegalStateException.class, () -> registry.initializeModules(null));
        CompletableFuture<Void> shutdown = registry.shutdownModulesAsync(null).toCompletableFuture();

        assertFalse(shutdown.isDone());
        assertEquals(List.of("failing.initialize", "failing.stop", "flow.prepare", "flow.finish"), lifecycle);
        drain.complete(null);
        shutdown.join();
        assertTrue(registry.shutdownModulesAsync(null).toCompletableFuture().isDone());
    }

    @Test
    void startFailureRollsBackEveryInitializedModuleOnceInReverseOrder() {
        ModuleRegistry registry = new ModuleRegistry();
        List<String> lifecycle = new ArrayList<>();
        registry.registerModule(new RecordingModule("first", lifecycle, false, false));
        registry.registerModule(new RecordingModule("failing", lifecycle, false, true));
        registry.registerModule(new RecordingModule("last", lifecycle, false, false));

        registry.initializeModules(null);
        assertThrows(IllegalStateException.class, () -> registry.startModules(null));

        assertEquals(List.of(
            "first.initialize", "failing.initialize", "last.initialize",
            "first.start", "failing.start",
            "last.stop", "failing.stop", "first.stop"), lifecycle);
        assertThrows(IllegalStateException.class, () -> registry.startModules(null));
        registry.shutdownModules(null);
        assertEquals(List.of(
            "first.initialize", "failing.initialize", "last.initialize",
            "first.start", "failing.start",
            "last.stop", "failing.stop", "first.stop"), lifecycle);
    }

    @Test
    void initializationErrorRollsBackAndRethrowsTheOriginalError() {
        ModuleRegistry registry = new ModuleRegistry();
        List<String> lifecycle = new ArrayList<>();
        AssertionError fatal = new AssertionError("initialize error");
        registry.registerModule(new RecordingModule("first", lifecycle, false, false));
        registry.registerModule(new RecordingModule("failing", lifecycle, false, false)
            .withInitializeFailure(fatal));

        AssertionError thrown = assertThrows(AssertionError.class, () -> registry.initializeModules(null));

        assertSame(fatal, thrown);
        assertEquals(List.of(
            "first.initialize", "failing.initialize",
            "failing.stop", "first.stop"), lifecycle);
    }

    @Test
    void startErrorRollsBackAndRethrowsTheOriginalError() {
        ModuleRegistry registry = new ModuleRegistry();
        List<String> lifecycle = new ArrayList<>();
        AssertionError fatal = new AssertionError("start error");
        registry.registerModule(new RecordingModule("first", lifecycle, false, false));
        registry.registerModule(new RecordingModule("failing", lifecycle, false, false)
            .withStartFailure(fatal));

        registry.initializeModules(null);
        AssertionError thrown = assertThrows(AssertionError.class, () -> registry.startModules(null));

        assertSame(fatal, thrown);
        assertEquals(List.of(
            "first.initialize", "failing.initialize",
            "first.start", "failing.start",
            "failing.stop", "first.stop"), lifecycle);
    }

    private static final class RecordingModule implements Module {
        private final String id;
        private final List<String> lifecycle;
        private final boolean failInitialize;
        private final boolean failStart;
        private Throwable initializeFailure;
        private Throwable startFailure;
        private List<String> dependencies = List.of();

        private RecordingModule(String id, List<String> lifecycle, boolean failInitialize, boolean failStart) {
            this.id = id;
            this.lifecycle = lifecycle;
            this.failInitialize = failInitialize;
            this.failStart = failStart;
        }

        private RecordingModule withDependencies(String... dependencies) {
            this.dependencies = List.of(dependencies);
            return this;
        }

        private RecordingModule withInitializeFailure(Throwable failure) {
            this.initializeFailure = failure;
            return this;
        }

        private RecordingModule withStartFailure(Throwable failure) {
            this.startFailure = failure;
            return this;
        }

        @Override
        public ModuleMetadata getMetadata() {
            return new ModuleMetadata(id, id, dependencies, Set.of());
        }

        @Override
        public void initialize(ModuleContext context) {
            lifecycle.add(id + ".initialize");
            if (failInitialize) {
                throw new IllegalStateException("initialize failure");
            }
            throwFailure(initializeFailure);
        }

        @Override
        public void start(ModuleContext context) {
            lifecycle.add(id + ".start");
            if (failStart) {
                throw new IllegalStateException("start failure");
            }
            throwFailure(startFailure);
        }

        @Override
        public void stop(ModuleContext context) {
            lifecycle.add(id + ".stop");
        }

        private static void throwFailure(Throwable failure) {
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
        }
    }

    private Module module(String id, List<String> lifecycle, List<Boolean> primary, CompletableFuture<Void> completion,
                          AtomicBoolean flowDrained, boolean flow) {
        return new Module() {
            @Override
            public ModuleMetadata getMetadata() {
                return ModuleMetadata.of(id, id);
            }

            @Override
            public void prepareStop(ModuleContext context) {
                lifecycle.add(id + ".prepare");
                primary.add(Bukkit.getServer() == null || Bukkit.isPrimaryThread());
            }

            @Override
            public CompletionStage<Void> finishStopAsync(ModuleContext context) {
                lifecycle.add(id + ".finish");
                if (!flow && !flowDrained.get()) {
                    throw new IllegalStateException("PlayerNpc Provider Finished Before Flow Drain");
                }
                if (flow) {
                    return completion.thenRun(() -> flowDrained.set(true));
                }
                return completion;
            }

            @Override
            public CompletionStage<Void> stopAsync(ModuleContext context) {
                prepareStop(context);
                return finishStopAsync(context);
            }
        };
    }
}
