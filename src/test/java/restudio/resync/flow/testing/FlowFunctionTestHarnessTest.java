package restudio.resync.flow.testing;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowGraph;
import restudio.resync.flow.FlowExecutor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowFunctionTestHarnessTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-29T00:30:00Z"), ZoneId.of("Europe/Berlin"));

    @Test
    void comparesOutputsWithDeterministicFixtureContext() {
        FlowFunctionTestHarness harness = new FlowFunctionTestHarness((graph, player, event, inputs, context) -> {
            assertEquals("2026-03-29T00:30:00Z", context.get("test.clock.instant"));
            assertEquals("Europe/Berlin", context.get("test.clock.zone"));
            return CompletableFuture.completedFuture(Map.of("result", 4));
        }, CLOCK);
        FlowFunctionTestHarness.Fixture fixture = new FlowFunctionTestHarness.Fixture(
            "Double", Map.of("value", 2.0D), Map.of("result", 4.0D), Map.of("world", "fixture"), null, null, Duration.ofSeconds(1)
        );

        FlowFunctionTestHarness.Result result = harness.run(new FlowGraph(), fixture).join();

        assertTrue(result.passed());
        assertTrue(result.mismatches().isEmpty());
        assertEquals("Europe/Berlin", result.zoneId());
    }

    @Test
    void returnsStructuredExecutionFailures() {
        FlowFunctionTestHarness harness = new FlowFunctionTestHarness((graph, player, event, inputs, context) -> CompletableFuture.failedFuture(
            new FlowExecutor.FlowExecutionException("FIXTURE_FAILURE", "Failed", null, "node", "Change Fixture")
        ), CLOCK);

        FlowFunctionTestHarness.Result result = harness.run(new FlowGraph(), new FlowFunctionTestHarness.Fixture(
            "Failure", Map.of(), Map.of(), Map.of(), null, null, Duration.ofSeconds(1)
        )).join();

        assertFalse(result.passed());
        assertEquals("FIXTURE_FAILURE", result.failure().code());
        assertEquals("node", result.failure().nodeId());
    }
}
