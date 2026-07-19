package restudio.resync.flow;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import restudio.flow.data.FlowGraph;
import restudio.resync.flow.handler.FlowHandlerException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class FlowPredicateSupport {
    private FlowPredicateSupport() {
    }

    public static boolean evaluate(FlowStorage storage, FlowExecutor executor, String flowId, Player player, Event event, Map<String, Object> vars) {
        if (flowId == null || flowId.isBlank()) {
            return true;
        }
        if (storage == null) {
            throw new FlowHandlerException("PREDICATE_STORAGE_UNAVAILABLE", "Predicate Flow storage is unavailable",
                "Restore Flow storage before evaluating " + flowId, Map.of("flowId", flowId));
        }
        if (executor == null) {
            throw new FlowHandlerException("PREDICATE_EXECUTOR_UNAVAILABLE", "Predicate Flow executor is unavailable",
                "Restore the Flow runtime before evaluating " + flowId, Map.of("flowId", flowId));
        }
        FlowGraph graph = storage.getGraph(flowId);
        if (graph == null) {
            throw new FlowHandlerException("PREDICATE_FLOW_NOT_FOUND", "Predicate Flow not found: " + flowId,
                "Select an existing predicate Flow or restore the missing graph", Map.of("flowId", flowId));
        }
        String outputNodeId = FlowSubFlowSupport.requireOutputNodeId(graph, "condition");
        Map<String, Object> inputs = new HashMap<>();
        if (vars != null) {
            inputs.putAll(vars);
        }
        try {
            Object result = executor.executeSubFlow(graph, outputNodeId, "condition", player, event, inputs).get(5, TimeUnit.SECONDS);
            if (result instanceof Boolean value) {
                return value;
            }
            throw new FlowHandlerException("PREDICATE_RESULT_TYPE_INVALID", "Predicate Flow condition output is not boolean: " + flowId,
                "Return a boolean from the condition output", Map.of("flowId", flowId, "actualType", result != null ? result.getClass().getName() : "absent"));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new FlowHandlerException("PREDICATE_INTERRUPTED", "Predicate Flow evaluation was interrupted: " + flowId,
                "Retry when the Flow runtime is available", Map.of("flowId", flowId), exception);
        } catch (TimeoutException exception) {
            throw new FlowHandlerException("PREDICATE_TIMEOUT", "Predicate Flow evaluation timed out: " + flowId,
                "Reduce the predicate work or move it to an asynchronous action", Map.of("flowId", flowId, "timeoutSeconds", 5), exception);
        } catch (ExecutionException exception) {
            throw predicateFailure(flowId, exception);
        }
    }

    private static FlowHandlerException predicateFailure(String flowId, ExecutionException failure) {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof FlowHandlerException handlerFailure) {
            return handlerFailure;
        }
        if (cause instanceof FlowExecutor.FlowExecutionException executionFailure) {
            return new FlowHandlerException(executionFailure.getCode(), executionFailure.getMessage(), executionFailure.getRemediation(),
                executionFailure.getDetails(), executionFailure);
        }
        return new FlowHandlerException("PREDICATE_EXECUTION_FAILED", "Predicate Flow failed: " + flowId,
            "Inspect the predicate Flow and its inputs", Map.of("flowId", flowId), cause);
    }
}
