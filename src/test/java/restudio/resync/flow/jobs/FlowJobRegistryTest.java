package restudio.resync.flow.jobs;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowJobReference;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowJobRegistryTest {
    @Test
    void jobLifecyclePublishesCanonicalSnapshots() {
        FlowJobRegistry registry = new FlowJobRegistry();
        List<FlowJobReference.Snapshot<?>> snapshots = new ArrayList<>();
        registry.addListener(snapshots::add);

        FlowJobReference<String> job = registry.create("compile", "flow:test");
        registry.start(job);
        registry.update(job, 0.5, Map.of("phase", "compile"));
        registry.succeed(job, "ready");

        assertEquals(FlowJobReference.State.SUCCEEDED, job.getState());
        assertEquals(1.0, job.getProgress());
        assertEquals("ready", job.snapshot().outcome().value());
        assertEquals(4, snapshots.size());
        assertEquals(FlowJobReference.State.SUCCEEDED, snapshots.getLast().state());
    }

    @Test
    void cancellationInvokesTheOwnedCancellationAction() {
        FlowJobRegistry registry = new FlowJobRegistry();
        FlowJobReference<String> job = registry.create("preview", "flow:test");
        AtomicBoolean cancelled = new AtomicBoolean();
        job.setCancellation(() -> cancelled.set(true));
        registry.start(job);

        assertTrue(registry.cancel(job));
        assertTrue(cancelled.get());
        assertEquals(FlowJobReference.State.CANCELLED, job.getState());
        assertFalse(registry.cancel(job));
    }

    @Test
    void terminalRetentionRemovesExpiredJobs() {
        FlowJobRegistry registry = new FlowJobRegistry(16, Duration.ZERO);
        FlowJobReference<String> completed = registry.create("compile", "flow:test");
        registry.succeed(completed, "ready");

        registry.create("compile", "flow:test");

        assertFalse(registry.snapshots("flow:test").stream().anyMatch(snapshot -> snapshot.id().equals(completed.getId())));
    }
}
