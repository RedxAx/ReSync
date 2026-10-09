package restudio.resync.flow.automation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationTaskServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void timerLifecycleIsScopedAndGenerationSafe() {
        AutomationTaskService service = service(Instant.parse("2026-07-28T12:00:00Z"), null);
        TimerDefinition definition = timer("round", false);
        AutomationOwner owner = new AutomationOwner("server", null);
        AutomationInstanceKey key = new AutomationInstanceKey(definition.kind(), definition.id(), definition.scope(), owner.id());

        AutomationTaskService.TaskSnapshot first = service.startTimer(definition, owner, 60_000L, 1_000L);
        AutomationTaskService.TaskSnapshot replacement = service.startTimer(definition, owner, 30_000L, 0L);

        assertTrue(replacement.generation() > first.generation());
        assertEquals(AutomationTaskService.State.CANCELLED, service.cancel(first.taskId()).state());
        assertEquals(AutomationTaskService.State.ACTIVE, service.check(key).state());
        ScheduleDefinition schedule = schedule(definition.id());
        AutomationInstanceKey scheduleKey = new AutomationInstanceKey(schedule.kind(), schedule.id(), schedule.scope(), owner.id());
        service.startSchedule(new AutomationTaskService.ScheduleRequest(schedule, owner, 60_000L, 60_000L, null,
            () -> CompletableFuture.completedFuture(null), Map.of(), 1));
        assertEquals(replacement.taskId(), service.check(key).taskId());
        assertEquals(AutomationTaskService.Kind.SCHEDULE, service.check(scheduleKey).kind());
        assertEquals(AutomationTaskService.State.PAUSED, service.pause(key).state());
        assertEquals(AutomationTaskService.State.ACTIVE, service.resume(key).state());
        assertEquals(AutomationTaskService.State.CANCELLED, service.cancel(key).state());
        assertEquals(AutomationTaskService.State.INACTIVE, service.check(key).state());
        assertEquals(AutomationTaskService.State.ACTIVE, service.check(scheduleKey).state());
        service.shutdown();
    }

    @Test
    void persistentPausedTimerRestoresItsRemainingDuration() {
        Path file = temporaryDirectory.resolve("automation.json");
        AutomationTaskService first = service(Instant.parse("2026-07-28T12:00:00Z"), file);
        TimerDefinition definition = timer("persistent_round", true);
        AutomationOwner owner = new AutomationOwner("server", null);
        AutomationInstanceKey key = new AutomationInstanceKey(definition.kind(), definition.id(), definition.scope(), owner.id());
        first.startTimer(definition, owner, 45_000L, 0L);
        long remaining = first.pause(key).remaining();
        first.shutdown();

        AutomationTaskService restored = service(Instant.parse("2026-07-28T12:10:00Z"), file);
        restored.restorePersistentTimers(ignored -> definition);

        AutomationTaskService.TaskSnapshot snapshot = restored.check(key);
        assertEquals(AutomationTaskService.State.PAUSED, snapshot.state());
        assertEquals(remaining, snapshot.remaining());
        restored.shutdown();
    }

    @Test
    void persistentActiveTimerUsesItsWallClockDeadline() {
        Path file = temporaryDirectory.resolve("automation.json");
        AutomationTaskService first = service(Instant.parse("2026-07-28T12:00:00Z"), file);
        TimerDefinition definition = timer("deadline", true);
        AutomationOwner owner = new AutomationOwner("server", null);
        AutomationInstanceKey key = new AutomationInstanceKey(definition.kind(), definition.id(), definition.scope(), owner.id());
        first.startTimer(definition, owner, 1_000L, 0L);
        first.shutdown();

        AutomationTaskService restored = service(Instant.parse("2026-07-28T12:00:02Z"), file);
        restored.restorePersistentTimers(ignored -> definition);

        assertEquals(AutomationTaskService.State.INACTIVE, restored.check(key).state());
        restored.shutdown();
    }

    @Test
    void failedTimerRestorationRemainsDurableForRetry() {
        Path file = temporaryDirectory.resolve("automation-retry.json");
        TimerDefinition definition = timer("retry", true);
        AutomationOwner owner = new AutomationOwner("server", null);
        AutomationInstanceKey key = new AutomationInstanceKey(definition.kind(), definition.id(), definition.scope(), owner.id());
        AutomationTaskService first = service(Instant.parse("2026-07-28T12:00:00Z"), file);
        first.startTimer(definition, owner, 60_000L, 0L);
        first.shutdown();

        AutomationTaskService failed = service(Instant.parse("2026-07-28T12:00:01Z"), file);
        failed.restorePersistentTimers(ignored -> {
            throw new IllegalStateException("definitions are not ready");
        });
        assertEquals(AutomationTaskService.State.INACTIVE, failed.check(key).state());
        failed.shutdown();

        AutomationTaskService restored = service(Instant.parse("2026-07-28T12:00:02Z"), file);
        restored.restorePersistentTimers(ignored -> definition);
        assertEquals(AutomationTaskService.State.ACTIVE, restored.check(key).state());
        restored.shutdown();
    }

    @Test
    void persistentScheduleCapturesArgumentsIncludingNulls() {
        Path file = temporaryDirectory.resolve("automation.json");
        AutomationTaskService first = service(Instant.parse("2026-07-28T12:00:00Z"), file);
        ScheduleDefinition definition = schedule("heartbeat");
        AutomationOwner owner = new AutomationOwner("server", null);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("arena", "one");
        arguments.put("winner", null);
        first.startSchedule(new AutomationTaskService.ScheduleRequest(definition, owner, 60_000L, 60_000L,
            () -> 60_000L, () -> CompletableFuture.completedFuture(null), arguments, 7));
        first.shutdown();

        AutomationTaskService restored = service(Instant.parse("2026-07-28T12:00:10Z"), file);
        restored.restorePersistentSchedules(state -> {
            assertEquals(7, state.signatureVersion());
            assertTrue(state.arguments().containsKey("winner"));
            assertEquals("one", state.arguments().get("arena"));
            return new AutomationTaskService.ScheduleRequest(definition, owner, 50_000L, 60_000L,
                () -> 60_000L, () -> CompletableFuture.completedFuture(null), state.arguments(), state.signatureVersion());
        });

        assertFalse(restored.snapshots().isEmpty());
        assertEquals(AutomationTaskService.State.ACTIVE, restored.snapshots().getFirst().state());
        restored.shutdown();
    }

    @Test
    void missedOneShotScheduleCanSkipAfterRestart() {
        Path file = temporaryDirectory.resolve("automation.json");
        ScheduleDefinition definition = schedule("one_shot", ScheduleDefinition.TimingMode.AFTER_DELAY,
            ScheduleDefinition.MissedRunPolicy.SKIP);
        AutomationOwner owner = new AutomationOwner("server", null);
        AutomationInstanceKey key = new AutomationInstanceKey(definition.kind(), definition.id(), definition.scope(), owner.id());
        AutomationTaskService first = service(Instant.parse("2026-07-28T12:00:00Z"), file);
        first.startSchedule(new AutomationTaskService.ScheduleRequest(definition, owner, 1_000L, 0L,
            null, () -> CompletableFuture.completedFuture(null), Map.of(), 1));
        first.shutdown();

        AtomicInteger invocations = new AtomicInteger();
        AutomationTaskService restored = service(Instant.parse("2026-07-28T12:01:00Z"), file);
        restored.restorePersistentSchedules(state -> new AutomationTaskService.ScheduleRequest(definition, owner, 0L, 0L,
            null, () -> {
                invocations.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }, state.arguments(), state.signatureVersion()));

        assertEquals(AutomationTaskService.State.INACTIVE, restored.check(key).state());
        assertEquals(0, invocations.get());
        restored.shutdown();
    }

    @Test
    void persistentTimerDoesNotStartWhenItsJournalCannotCommit() throws Exception {
        Path blockedDirectory = temporaryDirectory.resolve("blocked");
        Files.writeString(blockedDirectory, "file");
        AutomationTaskService service = service(Instant.parse("2026-07-28T12:00:00Z"), blockedDirectory.resolve("automation.json"));
        TimerDefinition definition = timer("durable", true);
        AutomationOwner owner = new AutomationOwner("server", null);

        assertThrows(IllegalStateException.class, () -> service.startTimer(definition, owner, 60_000L, 0L));
        assertTrue(service.snapshots().isEmpty());

        assertThrows(IllegalStateException.class, service::shutdown);
    }

    @Test
    void persistentResumeDoesNotScheduleBeforeItsJournalCommits() throws Exception {
        Path file = temporaryDirectory.resolve("automation.json");
        CountingScheduler scheduler = new CountingScheduler();
        AutomationTaskService service = new AutomationTaskService(null, null, Clock.fixed(Instant.parse("2026-07-28T12:00:00Z"), ZoneOffset.UTC),
            scheduler, new AutomationTaskStore(file));
        TimerDefinition definition = timer("durable-resume", true);
        AutomationOwner owner = new AutomationOwner("server", null);
        AutomationInstanceKey key = new AutomationInstanceKey(definition.kind(), definition.id(), definition.scope(), owner.id());
        service.startTimer(definition, owner, 60_000L, 0L);
        service.pause(key);
        assertEquals(1, scheduler.schedules.get());
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("block"), "file");

        assertThrows(IllegalStateException.class, () -> service.resume(key));

        assertEquals(1, scheduler.schedules.get());
        assertEquals(AutomationTaskService.State.PAUSED, service.check(key).state());
        assertThrows(IllegalStateException.class, service::shutdown);
    }

    @Test
    void failedTerminalPersistenceUsesABoundedRetryDelay() throws Exception {
        Path file = temporaryDirectory.resolve("automation.json");
        CountingScheduler scheduler = new CountingScheduler();
        AutomationTaskService service = new AutomationTaskService(null, null, Clock.fixed(Instant.parse("2026-07-28T12:00:00Z"), ZoneOffset.UTC),
            scheduler, new AutomationTaskStore(file));
        TimerDefinition definition = timer("durable-terminal", true);
        AutomationOwner owner = new AutomationOwner("server", null);
        service.startTimer(definition, owner, 0L, 0L);
        Runnable due = scheduler.command;
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("block"), "file");

        assertThrows(IllegalStateException.class, due::run);

        assertEquals(2, scheduler.schedules.get());
        assertEquals(1_000L, scheduler.requestedDelay);
        assertThrows(IllegalStateException.class, service::shutdown);
    }

    @Test
    void inFlightScheduleCompletionCannotMutateAfterQuiesce() throws Exception {
        Path file = temporaryDirectory.resolve("automation-quiesce.json");
        CountingScheduler scheduler = new CountingScheduler();
        AutomationTaskService service = new AutomationTaskService(null, null,
            Clock.fixed(Instant.parse("2026-07-28T12:00:00Z"), ZoneOffset.UTC), scheduler, new AutomationTaskStore(file));
        ScheduleDefinition definition = schedule("quiesced-completion");
        AutomationOwner owner = new AutomationOwner("server", null);
        CompletableFuture<Object> invocation = new CompletableFuture<>();

        AutomationTaskService.StartResult started = service.startSchedule(new AutomationTaskService.ScheduleRequest(
            definition, owner, 0L, 1000L, () -> 1000L, () -> invocation, Map.of(), 1));
        scheduler.command.run();
        service.quiescePersistence();
        AutomationTaskService.PersistentTask pending = new AutomationTaskStore(file).load().getFirst();
        assertTrue(pending.invocationPending());
        assertEquals(1L, pending.runCount());
        assertEquals(started.task().nextRun(), pending.nextRun());

        invocation.complete("late");

        service.healthCheckPersistence();
        assertNull(service.snapshots().getFirst().lastResult());
        service.shutdown();
    }

    @Test
    void skippedOverlapKeepsTheNextRepeatingRun() {
        CountingScheduler scheduler = new CountingScheduler();
        AutomationTaskService service = new AutomationTaskService(null, null,
            Clock.fixed(Instant.parse("2026-07-28T12:00:00Z"), ZoneOffset.UTC), scheduler);
        CompletableFuture<Object> running = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        service.startSchedule(new AutomationTaskService.ScheduleRequest(schedule("overlap"),
            new AutomationOwner("server", null), 0L, 1000L, () -> 1000L, () -> {
                calls.incrementAndGet();
                return running;
            }, Map.of(), 1));
        try {
            scheduler.command.run();
            scheduler.command.run();
            assertEquals(1, calls.get());
            assertEquals(3, scheduler.schedules.get());
            running.complete("done");
            scheduler.command.run();
            assertEquals(2, calls.get());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void cancelledScheduleDoesNotStartQueuedInvocations() {
        CountingScheduler scheduler = new CountingScheduler();
        AutomationTaskService service = new AutomationTaskService(null, null,
            Clock.fixed(Instant.parse("2026-07-28T12:00:00Z"), ZoneOffset.UTC), scheduler);
        ScheduleDefinition definition = new ScheduleDefinition("queued", "queued", "", ScheduleDefinition.TargetType.FUNCTION,
            "heartbeat", ScheduleDefinition.TimingMode.REPEATING, 60D, TimerDefinition.TimeUnit.SECONDS, 0D, "", "UTC", "",
            AutomationScope.SERVER, false, ScheduleDefinition.OverlapPolicy.QUEUE, ScheduleDefinition.ExistingTaskPolicy.REPLACE,
            ScheduleDefinition.FailurePolicy.CONTINUE, ScheduleDefinition.OfflinePolicy.WAIT, ScheduleDefinition.MissedRunPolicy.RUN_ONCE);
        AutomationOwner owner = new AutomationOwner("server", null);
        AutomationInstanceKey key = new AutomationInstanceKey(definition.kind(), definition.id(), definition.scope(), owner.id());
        CompletableFuture<Object> running = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        service.startSchedule(new AutomationTaskService.ScheduleRequest(definition, owner, 60000L, 1000L,
            () -> 1000L, () -> {
                calls.incrementAndGet();
                return running;
            }, Map.of(), 1));
        try {
            CompletableFuture<Object> first = service.runNow(key);
            first.cancel(true);
            CompletableFuture<Object> queued = service.runNow(key);
            assertFalse(queued.isDone());
            assertEquals(1, calls.get());
            service.cancel(key);
            running.complete("done");
            assertTrue(queued.isCompletedExceptionally());
            assertEquals(1, calls.get());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void unreadableTaskJournalPreventsAdmission() throws Exception {
        Path file = temporaryDirectory.resolve("invalid-tasks.json");
        Files.createDirectory(file);
        assertThrows(IllegalStateException.class, () -> new AutomationTaskStore(file).load());
        assertTrue(Files.isDirectory(file));
    }

    @Test
    void aCancelledWakeCannotFireAfterTheSameScheduleResumes() {
        CountingScheduler scheduler = new CountingScheduler();
        AutomationTaskService service = new AutomationTaskService(null, null,
            Clock.fixed(Instant.parse("2026-07-28T12:00:00Z"), ZoneOffset.UTC), scheduler);
        ScheduleDefinition definition = schedule("resumed-wake");
        AutomationOwner owner = new AutomationOwner("server", null);
        AutomationInstanceKey key = new AutomationInstanceKey(definition.kind(), definition.id(), definition.scope(), owner.id());
        AtomicInteger calls = new AtomicInteger();
        service.startSchedule(new AutomationTaskService.ScheduleRequest(definition, owner, 60000L, 0L, null,
            () -> CompletableFuture.completedFuture(calls.incrementAndGet()), Map.of(), 1));
        try {
            Runnable stale = scheduler.command;
            service.pause(key);
            service.resume(key);
            stale.run();
            assertEquals(0, calls.get());
            scheduler.command.run();
            assertEquals(1, calls.get());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void failedScheduleAdmissionRetriesWithoutRunningOrCountingAnUncommittedInvocation() throws Exception {
        Path file = temporaryDirectory.resolve("retry-admission.json");
        CountingScheduler scheduler = new CountingScheduler();
        AutomationTaskService service = new AutomationTaskService(null, null,
            Clock.fixed(Instant.parse("2026-07-28T12:00:00Z"), ZoneOffset.UTC), scheduler, new AutomationTaskStore(file));
        AtomicInteger calls = new AtomicInteger();
        AutomationTaskService.StartResult started = service.startSchedule(new AutomationTaskService.ScheduleRequest(
            schedule("admission-retry"), new AutomationOwner("server", null), 0L, 0L, null,
            () -> CompletableFuture.completedFuture(calls.incrementAndGet()), Map.of(), 1));
        String committed = Files.readString(file);
        Files.delete(file);
        Files.createDirectory(file);
        try {
            assertThrows(IllegalStateException.class, scheduler.command::run);
            assertEquals(0, calls.get());
            assertEquals(0L, service.task(started.task().taskId()).runCount());
            assertEquals(1000L, scheduler.requestedDelay);
            Files.delete(file);
            Files.writeString(file, committed);
            scheduler.command.run();
            assertEquals(1, calls.get());
            assertEquals(1L, service.task(started.task().taskId()).runCount());
        } finally {
            service.shutdown();
        }
    }

    private AutomationTaskService service(Instant instant, Path file) {
        return new AutomationTaskService(null, null, Clock.fixed(instant, ZoneOffset.UTC),
            Executors.newSingleThreadScheduledExecutor(), file != null ? new AutomationTaskStore(file) : null);
    }

    private TimerDefinition timer(String id, boolean persistent) {
        return new TimerDefinition(id, id, "", AutomationScope.SERVER, persistent, 0D, TimerDefinition.TimeUnit.SECONDS, 0D);
    }

    private ScheduleDefinition schedule(String id) {
        return schedule(id, ScheduleDefinition.TimingMode.REPEATING, ScheduleDefinition.MissedRunPolicy.RUN_ONCE);
    }

    private ScheduleDefinition schedule(String id, ScheduleDefinition.TimingMode timingMode,
                                        ScheduleDefinition.MissedRunPolicy missedRunPolicy) {
        return new ScheduleDefinition(id, id, "", ScheduleDefinition.TargetType.FUNCTION, "heartbeat",
            timingMode, 60D, TimerDefinition.TimeUnit.SECONDS, 0D, "", "UTC", "",
            AutomationScope.SERVER, true, ScheduleDefinition.OverlapPolicy.SKIP, ScheduleDefinition.ExistingTaskPolicy.REPLACE,
            ScheduleDefinition.FailurePolicy.CONTINUE, ScheduleDefinition.OfflinePolicy.WAIT, missedRunPolicy);
    }

    private static final class CountingScheduler extends ScheduledThreadPoolExecutor {
        private final AtomicInteger schedules = new AtomicInteger();
        private Runnable command;
        private long requestedDelay;

        private CountingScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            schedules.incrementAndGet();
            this.command = command;
            requestedDelay = unit.toMillis(delay);
            return super.schedule(command, 1L, TimeUnit.DAYS);
        }
    }
}
