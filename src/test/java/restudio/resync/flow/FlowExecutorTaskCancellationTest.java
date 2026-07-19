package restudio.resync.flow;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.flow.handler.HandlerRegistry;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowExecutorTaskCancellationTest {
    private Plugin plugin;
    private FlowExecutor executor;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
        MockBukkit.unmock();
    }

    @Test
    void cancellationDistinguishesCancelledFinishedAndUnknownTasks() {
        BukkitTask cancellable = Bukkit.getScheduler().runTaskLater(plugin, () -> {}, 100L);
        executor.registerPendingTask("cancel-me", "graph", cancellable);

        assertEquals(FlowExecutor.TaskCancellationStatus.CANCELLED, executor.cancelPendingTaskWithStatus("cancel-me"));
        assertEquals(FlowExecutor.TaskCancellationStatus.ALREADY_CANCELLED, executor.cancelPendingTaskWithStatus("cancel-me"));

        BukkitTask finished = Bukkit.getScheduler().runTaskLater(plugin, () -> {}, 100L);
        executor.registerPendingTask("finished", "graph", finished);
        executor.unregisterPendingTask("finished");

        assertEquals(FlowExecutor.TaskCancellationStatus.FINISHED, executor.cancelPendingTaskWithStatus("finished"));
        assertEquals(FlowExecutor.TaskCancellationStatus.UNKNOWN, executor.cancelPendingTaskWithStatus("missing"));
    }

    @Test
    void targetDeletionCancelsOnlyOwnedTasksAndReloadCancelsTheRemainder() {
        BukkitTask first = Bukkit.getScheduler().runTaskLater(plugin, () -> {}, 100L);
        BukkitTask second = Bukkit.getScheduler().runTaskLater(plugin, () -> {}, 100L);
        executor.registerPendingTask("first", "deleted_graph", first);
        executor.registerPendingTask("second", "other_graph", second);

        assertEquals(1, executor.cancelPendingTasks("deleted_graph"));
        assertEquals(FlowExecutor.TaskCancellationStatus.ALREADY_CANCELLED, executor.cancelPendingTaskWithStatus("first"));
        assertEquals(FlowExecutor.TaskCancellationStatus.CANCELLED, executor.cancelPendingTaskWithStatus("second"));

        BukkitTask reloadTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {}, 100L);
        executor.registerPendingTask("reload", "other_graph", reloadTask);
        executor.cancelPendingTasks();

        assertEquals(FlowExecutor.TaskCancellationStatus.ALREADY_CANCELLED, executor.cancelPendingTaskWithStatus("reload"));
    }

    @Test
    void scheduledTaskMetadataRemainsObservableAfterCancellation() {
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {}, 100L);
        executor.registerPendingTask("schedule", "target_graph", "schedule", task, null, 1000L, 5000L, true);

        FlowExecutor.ScheduledTaskSnapshot active = executor.getScheduledTaskSnapshot("schedule");
        assertEquals("schedule", active.runtimeOwner());
        assertEquals("target_graph", active.graphId());
        assertEquals(1000L, active.createdAt());
        assertEquals(5000L, active.nextFireAt());
        assertEquals(FlowExecutor.ScheduledTaskState.ACTIVE, active.state());

        executor.updateScheduledTaskNextFireAt("schedule", 6000L);
        executor.recordScheduledTaskFailure("schedule", new IllegalStateException("Fixture Failure"));
        executor.cancelPendingTask("schedule");

        FlowExecutor.ScheduledTaskSnapshot cancelled = executor.getScheduledTaskSnapshot("schedule");
        assertEquals(6000L, cancelled.nextFireAt());
        assertEquals(FlowExecutor.ScheduledTaskState.CANCELLED, cancelled.state());
        assertEquals("Fixture Failure", cancelled.lastFailure());
    }

    @Test
    void completedTaskWithAnObservedFailureRemainsFailed() {
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {}, 100L);
        executor.registerPendingTask("failed", "target_graph", "schedule", task, null, 1000L, 5000L, false);

        executor.recordScheduledTaskFailure("failed", new IllegalStateException("Execution Failed"));
        executor.unregisterPendingTask("failed");

        FlowExecutor.ScheduledTaskSnapshot failed = executor.getScheduledTaskSnapshot("failed");
        assertEquals(FlowExecutor.ScheduledTaskState.FAILED, failed.state());
        assertEquals("Execution Failed", failed.lastFailure());
    }

    @Test
    void finishingTaskRecordsItsFailureBeforeUnregistering() {
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {}, 100L);
        executor.registerPendingTask("failed-finish", "target_graph", "flow_runtime", task, null, 1000L, 5000L, false);

        executor.finishPendingTask("failed-finish", new IllegalStateException("Handler Failed"));

        FlowExecutor.ScheduledTaskSnapshot failed = executor.getScheduledTaskSnapshot("failed-finish");
        assertEquals(FlowExecutor.ScheduledTaskState.FAILED, failed.state());
        assertEquals("Handler Failed", failed.lastFailure());
    }

    @Test
    void wallClockDelaysAreOwnedAndCancellable() {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        executor.scheduleWallClockTask("wall-delay", "wall_graph", 100_000L, () -> {
        }, completion);

        FlowExecutor.ScheduledTaskSnapshot active = executor.getScheduledTaskSnapshot("wall-delay");
        assertEquals("flow_wall_clock", active.runtimeOwner());
        assertEquals("wall_graph", active.graphId());
        assertEquals(FlowExecutor.ScheduledTaskState.ACTIVE, active.state());
        assertEquals(FlowExecutor.TaskCancellationStatus.CANCELLED, executor.cancelPendingTaskWithStatus("wall-delay"));
        assertEquals(FlowExecutor.TaskCancellationStatus.ALREADY_CANCELLED, executor.cancelPendingTaskWithStatus("wall-delay"));
    }

    @Test
    void calendarSchedulesPreserveTheirOwnerAndWallTimeMetadata() {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        executor.scheduleWallClockTask("calendar", "target_graph", "schedule", 100_000L, 1000L, 101_000L, true,
            () -> CompletableFuture.completedFuture(null), completion);

        FlowExecutor.ScheduledTaskSnapshot active = executor.getScheduledTaskSnapshot("calendar");
        assertEquals("schedule", active.runtimeOwner());
        assertEquals("target_graph", active.graphId());
        assertEquals(1000L, active.createdAt());
        assertEquals(101_000L, active.nextFireAt());
        assertEquals(true, active.recurring());

        executor.recordScheduledTaskFailure("calendar", new IllegalStateException("Calendar Failure"));
        executor.cancelPendingTask("calendar");

        FlowExecutor.ScheduledTaskSnapshot cancelled = executor.getScheduledTaskSnapshot("calendar");
        assertEquals("Calendar Failure", cancelled.lastFailure());
    }
}
