package restudio.resync.flow.testing;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import restudio.flow.data.FlowGraph;
import restudio.resync.flow.FlowExecutor;

import java.time.Clock;
import java.time.Duration;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class FlowFunctionTestHarness {
    @FunctionalInterface
    public interface FunctionRunner {
        CompletableFuture<Map<String, Object>> execute(FlowGraph graph, Player player, Event event, Map<String, Object> inputs, Map<String, Object> eventVariables);
    }

    public record Fixture(String name, Map<String, Object> inputs, Map<String, Object> expectedOutputs, Map<String, Object> serverContext,
                          Player player, Event event, Duration timeout) {
        public Fixture {
            name = name != null && !name.isBlank() ? name : "Fixture";
            inputs = immutableMap(inputs);
            expectedOutputs = immutableMap(expectedOutputs);
            serverContext = immutableMap(serverContext);
            timeout = timeout != null && !timeout.isNegative() && !timeout.isZero() ? timeout : Duration.ofSeconds(5);
        }
    }

    public record Mismatch(String output, Object expected, Object actual) {
    }

    public record Failure(String code, String message, String nodeId, String remediation, Map<String, Object> details) {
        public Failure {
            code = code != null ? code : "FLOW_TEST_FAILED";
            message = message != null ? message : "";
            nodeId = nodeId != null ? nodeId : "";
            remediation = remediation != null ? remediation : "";
            details = immutableMap(details);
        }
    }

    public record Result(String fixture, boolean passed, Map<String, Object> outputs, List<Mismatch> mismatches, Failure failure,
                         long durationNanos, String clockInstant, String zoneId) {
        public Result {
            outputs = immutableMap(outputs);
            mismatches = mismatches != null ? List.copyOf(mismatches) : List.of();
        }
    }

    private final FunctionRunner runner;
    private final Clock clock;

    public FlowFunctionTestHarness(FlowExecutor executor, Clock clock) {
        this(executor::executeFunction, clock);
    }

    public FlowFunctionTestHarness(FunctionRunner runner, Clock clock) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public CompletableFuture<Result> run(FlowGraph function, Fixture fixture) {
        Fixture normalizedFixture = fixture != null ? fixture : new Fixture("Fixture", Map.of(), Map.of(), Map.of(), null, null, Duration.ofSeconds(5));
        long started = System.nanoTime();
        Map<String, Object> eventVariables = new LinkedHashMap<>(normalizedFixture.serverContext());
        eventVariables.put("test.fixture", normalizedFixture.name());
        eventVariables.put("test.clock.instant", clock.instant().toString());
        eventVariables.put("test.clock.zone", clock.getZone().getId());
        CompletableFuture<Map<String, Object>> execution;
        try {
            execution = runner.execute(function, normalizedFixture.player(), normalizedFixture.event(), normalizedFixture.inputs(), immutableMap(eventVariables));
        } catch (Throwable error) {
            return CompletableFuture.completedFuture(failureResult(normalizedFixture, error, started));
        }
        return execution.orTimeout(Math.max(1L, normalizedFixture.timeout().toMillis()), TimeUnit.MILLISECONDS)
            .handle((outputs, error) -> error != null
                ? failureResult(normalizedFixture, error, started)
                : successResult(normalizedFixture, outputs, started));
    }

    public CompletableFuture<List<Result>> runAll(FlowGraph function, List<Fixture> fixtures) {
        List<Fixture> safeFixtures = fixtures != null ? List.copyOf(fixtures) : List.of();
        CompletableFuture<List<Result>> sequence = CompletableFuture.completedFuture(new ArrayList<>());
        for (Fixture fixture : safeFixtures) {
            sequence = sequence.thenCompose(results -> run(function, fixture).thenApply(result -> {
                results.add(result);
                return results;
            }));
        }
        return sequence.thenApply(List::copyOf);
    }

    private Result successResult(Fixture fixture, Map<String, Object> outputs, long started) {
        Map<String, Object> actualOutputs = outputs != null ? outputs : Map.of();
        List<Mismatch> mismatches = new ArrayList<>();
        for (Map.Entry<String, Object> expected : fixture.expectedOutputs().entrySet()) {
            Object actual = actualOutputs.get(expected.getKey());
            if (!valuesEqual(expected.getValue(), actual)) {
                mismatches.add(new Mismatch(expected.getKey(), expected.getValue(), actual));
            }
        }
        return new Result(fixture.name(), mismatches.isEmpty(), actualOutputs, mismatches, null, System.nanoTime() - started, clock.instant().toString(), clock.getZone().getId());
    }

    private Result failureResult(Fixture fixture, Throwable error, long started) {
        Throwable cause = unwrap(error);
        Failure failure;
        if (cause instanceof FlowExecutor.FlowExecutionException execution) {
            failure = new Failure(execution.getCode(), execution.getMessage(), execution.getNodeId(), execution.getRemediation(), execution.getDetails());
        } else {
            failure = new Failure("FLOW_TEST_FAILED", cause != null ? cause.getMessage() : "Unknown failure", "", "Inspect the fixture and function trace", Map.of());
        }
        return new Result(fixture.name(), false, Map.of(), List.of(), failure, System.nanoTime() - started, clock.instant().toString(), clock.getZone().getId());
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private boolean valuesEqual(Object expected, Object actual) {
        if (expected instanceof Number expectedNumber && actual instanceof Number actualNumber) {
            try {
                return new BigDecimal(expectedNumber.toString()).compareTo(new BigDecimal(actualNumber.toString())) == 0;
            } catch (NumberFormatException exception) {
                return Objects.equals(expectedNumber.doubleValue(), actualNumber.doubleValue());
            }
        }
        if (expected instanceof List<?> expectedList && actual instanceof List<?> actualList) {
            if (expectedList.size() != actualList.size()) {
                return false;
            }
            for (int index = 0; index < expectedList.size(); index++) {
                if (!valuesEqual(expectedList.get(index), actualList.get(index))) {
                    return false;
                }
            }
            return true;
        }
        if (expected instanceof Map<?, ?> expectedMap && actual instanceof Map<?, ?> actualMap) {
            if (!expectedMap.keySet().equals(actualMap.keySet())) {
                return false;
            }
            return expectedMap.entrySet().stream().allMatch(entry -> valuesEqual(entry.getValue(), actualMap.get(entry.getKey())));
        }
        return Objects.deepEquals(expected, actual);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> values) {
        return values != null ? Collections.unmodifiableMap(new LinkedHashMap<>(values)) : Map.of();
    }
}
