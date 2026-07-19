package restudio.resync.runtime;

import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import restudio.flow.data.FlowGraph;
import restudio.resync.Log;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.FunctionCallSupport;
import restudio.resync.flow.handler.FlowHandlerException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class RuntimeFlowDispatcher {
    private final FlowStorage flowStorage;
    private final FlowExecutor executor;

    public RuntimeFlowDispatcher(FlowStorage flowStorage, FlowExecutor executor) {
        this.flowStorage = flowStorage;
        this.executor = executor;
    }

    public boolean dispatch(String flowId, Player player, Event event, Map<String, Object> variables) {
        CompletableFuture<Void> dispatch = dispatchAsync(flowId, player, event, variables);
        dispatch.whenComplete((result, failure) -> {
            if (failure != null) {
                Log.warn("Flow dispatch failed for " + flowId + ": " + failure.getMessage());
            }
        });
        return !dispatch.isCompletedExceptionally();
    }

    public CompletableFuture<Void> dispatchAsync(String flowId, Player player, Event event, Map<String, Object> variables) {
        if (flowId == null || flowId.isBlank()) {
            return CompletableFuture.failedFuture(new FlowHandlerException("FLOW_ID_REQUIRED", "Flow ID is required",
                "Select an existing Flow"));
        }
        if (flowStorage == null) {
            return CompletableFuture.failedFuture(new FlowHandlerException("FLOW_STORAGE_UNAVAILABLE", "Flow storage is unavailable",
                "Restore Flow storage before dispatching " + flowId, Map.of("flowId", flowId)));
        }
        if (executor == null) {
            return CompletableFuture.failedFuture(new FlowHandlerException("FLOW_EXECUTOR_UNAVAILABLE", "Flow executor is unavailable",
                "Restore the Flow runtime before dispatching " + flowId, Map.of("flowId", flowId)));
        }
        FlowGraph graph = flowStorage.getGraph(flowId);
        if (graph == null || graph.getNodes() == null || graph.getNodes().isEmpty()) {
            return CompletableFuture.failedFuture(new FlowHandlerException("FLOW_NOT_FOUND", "Flow not found or empty: " + flowId,
                "Select an executable Flow or restore the missing graph", Map.of("flowId", flowId)));
        }
        Map<String, Object> safeVariables = variables != null ? new HashMap<>(variables) : new HashMap<>();
        return executor.execute(graph, player, event, safeVariables);
    }

    public boolean dispatchFunction(JsonObject call, Player player, Event event, Map<String, Object> variables) {
        CompletableFuture<Map<String, Object>> dispatch = dispatchFunctionAsync(call, player, event, variables);
        dispatch.whenComplete((result, failure) -> {
            if (failure != null) {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                String detail = cause.getMessage() == null || cause.getMessage().isBlank() ? cause.getClass().getSimpleName() : cause.getMessage();
                Log.warn("Function dispatch failed: " + detail, cause);
            }
        });
        return !dispatch.isCompletedExceptionally();
    }

    public CompletableFuture<Map<String, Object>> dispatchFunctionAsync(JsonObject call, Player player, Event event, Map<String, Object> variables) {
        if (call == null || call.isEmpty()) {
            return CompletableFuture.failedFuture(new FlowHandlerException("FUNCTION_CALL_REQUIRED", "Function call is required",
                "Select or configure a callable function"));
        }
        if (flowStorage == null) {
            return CompletableFuture.failedFuture(new FlowHandlerException("FUNCTION_STORAGE_UNAVAILABLE", "Function storage is unavailable",
                "Restore Flow storage before dispatching this function"));
        }
        if (executor == null) {
            return CompletableFuture.failedFuture(new FlowHandlerException("FUNCTION_EXECUTOR_UNAVAILABLE", "Function executor is unavailable",
                "Restore the Flow runtime before dispatching this function"));
        }
        try {
            return FunctionCallSupport.execute(flowStorage, executor, call, player, event,
                variables != null ? new HashMap<>(variables) : new HashMap<>());
        } catch (FlowHandlerException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

}
