package restudio.resync.runtime;

import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import restudio.resync.flow.CompiledTriggerExecution;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.FunctionCallSupport;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.handler.FlowHandlerException;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.runtime.RuntimeExecutionContext;
import restudio.resync.server.TemporaryLifecycleDiagnostics;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class RuntimeFlowDispatcher {
    private final FlowStorage flowStorage;
    private final FlowExecutor executor;
    private volatile CompiledTriggerExecution compiledExecution;

    public RuntimeFlowDispatcher(FlowStorage flowStorage, FlowExecutor executor) {
        this.flowStorage = flowStorage;
        this.executor = executor;
    }

    public void setCompiledExecution(CompiledTriggerExecution compiledExecution) {
        this.compiledExecution = compiledExecution;
    }

    public boolean dispatch(String flowId, Player player, Event event, Map<String, Object> variables) {
        CompletableFuture<Void> dispatch = dispatchAsync(flowId, player, event, variables);
        return !dispatch.isCompletedExceptionally();
    }

    public CompletableFuture<Void> dispatchAsync(String flowId, Player player, Event event, Map<String, Object> variables) {
        CorrelationId invocationId = CorrelationId.random();
        long started = TemporaryLifecycleDiagnostics.start();
        String identityFlowId = flowId == null ? "" : flowId;
        Map<String, Object> ingress = TemporaryLifecycleDiagnostics.with(
            TemporaryLifecycleDiagnostics.identity(null, "flow:" + identityFlowId, "runtime-dispatch", null, null,
                invocationId, null, null, null, null),
            "source", "runtime", "resourceType", "flow", "resourceId", identityFlowId, "outcome", "received");
        TemporaryLifecycleDiagnostics.event("trigger_ingress", started, ingress);
        if (flowId == null || flowId.isBlank()) {
            return reject(invocationId, started, ingress, "FLOW_ID_REQUIRED", "Flow ID is required",
                "Select an existing Flow", Map.of());
        }
        if (flowStorage == null) {
            return reject(invocationId, started, ingress, "FLOW_STORAGE_UNAVAILABLE", "Flow storage is unavailable",
                "Restore Flow storage before dispatching " + flowId, Map.of("flowId", flowId));
        }
        if (executor == null) {
            return reject(invocationId, started, ingress, "FLOW_EXECUTOR_UNAVAILABLE", "Flow executor is unavailable",
                "Restore the Flow runtime before dispatching " + flowId, Map.of("flowId", flowId));
        }
        CompiledTriggerExecution execution = compiledExecution;
        if (execution == null) {
            return reject(invocationId, started, ingress, "FLOW_COMPILED_EXECUTION_UNAVAILABLE",
                "Compiled Flow execution is unavailable", "Initialize the compiled Flow runtime before dispatching " + flowId,
                Map.of("flowId", flowId));
        }
        Map<String, Object> terminalIdentity = ingress;
        try {
            CoreGraphStorageBoundary.Decoded source = execution.source("flow", flowId).orElse(null);
            if (source == null || source.graphDocument().nodes().isEmpty()) {
                return reject(invocationId, started, ingress, "FLOW_NOT_FOUND", "Flow not found or empty: " + flowId,
                    "Select an executable Flow or restore the missing graph", Map.of("flowId", flowId));
            }
            Map<String, Object> binding = TemporaryLifecycleDiagnostics.with(ingress, "revision", source.envelope().assetRevision(),
                "graphHash", source.envelope().assetHash().canonicalText(), "outcome", "selected");
            terminalIdentity = binding;
            TemporaryLifecycleDiagnostics.event("trigger_binding_selected", started, binding);
            String startNodeId = execution.findStartNode(source);
            if (startNodeId == null || startNodeId.isBlank()) {
                return reject(invocationId, started, binding, "FLOW_START_MISSING",
                    "Flow has no executable start node: " + flowId,
                    "Connect an executable start node or restore the missing graph", Map.of("flowId", flowId));
            }
            Map<String, Object> safeVariables = variables != null ? new HashMap<>(variables) : new HashMap<>();
            CompletableFuture<Void> future = execution.executeSource(source, startNodeId, player, event, safeVariables, null,
                invocationId, RuntimeExecutionContext.NO_DEADLINE);
            execution.observe(future, invocationId, "runtime-flow:" + flowId);
            return future;
        } catch (RuntimeException failure) {
            TemporaryLifecycleDiagnostics.terminal("trigger_execution_terminal", started, terminalIdentity, "failed",
                "FLOW_DISPATCH_REJECTED", "synchronous-rejection");
            CompiledTriggerExecution.warnInvocation("runtime-flow|" + flowId + "|FLOW_DISPATCH_REJECTED",
                "Runtime Flow invocation failed correlationId=" + invocationId.canonicalText()
                    + " diagnosticCode=FLOW_DISPATCH_REJECTED source=runtime-flow:" + flowId);
            return CompletableFuture.failedFuture(new FlowHandlerException("FLOW_START_UNAVAILABLE",
                "Flow dispatch could not be admitted: " + flowId, "Repair the Flow graph before dispatching it",
                Map.of("flowId", flowId, "failureType", failure.getClass().getName())));
        }
    }

    public boolean dispatchFunction(JsonObject call, Player player, Event event, Map<String, Object> variables) {
        CompletableFuture<Map<String, Object>> dispatch = dispatchFunctionAsync(call, player, event, variables);
        return !dispatch.isCompletedExceptionally();
    }

    public CompletableFuture<Map<String, Object>> dispatchFunctionAsync(JsonObject call, Player player, Event event, Map<String, Object> variables) {
        CorrelationId invocationId = CorrelationId.random();
        long started = TemporaryLifecycleDiagnostics.start();
        Map<String, Object> identity = TemporaryLifecycleDiagnostics.with(
            TemporaryLifecycleDiagnostics.identity(null, "function", "runtime-dispatch", null, null,
                invocationId, null, null, null, null),
            "source", "runtime", "resourceType", "function", "outcome", "received");
        TemporaryLifecycleDiagnostics.event("trigger_ingress", started, identity);
        if (call == null || call.isEmpty()) {
            return rejectFunction(invocationId, started, identity, "FUNCTION_CALL_REQUIRED", "Function call is required",
                "Select or configure a callable function");
        }
        if (flowStorage == null) {
            return rejectFunction(invocationId, started, identity, "FUNCTION_STORAGE_UNAVAILABLE",
                "Function storage is unavailable", "Restore Flow storage before dispatching this function");
        }
        if (executor == null) {
            return rejectFunction(invocationId, started, identity, "FUNCTION_EXECUTOR_UNAVAILABLE",
                "Function executor is unavailable", "Restore the Flow runtime before dispatching this function");
        }
        Map<String, Object> terminalIdentity = identity;
        try {
            JsonObject admittedCall = call.deepCopy();
            String functionId = admittedCall.has("functionId") && !admittedCall.get("functionId").isJsonNull()
                ? admittedCall.get("functionId").getAsString() : "";
            if (functionId.isBlank() && admittedCall.has("id") && !admittedCall.get("id").isJsonNull()) {
                functionId = admittedCall.get("id").getAsString();
            }
            if (functionId.isBlank() || "none".equalsIgnoreCase(functionId)) {
                return rejectFunction(invocationId, started, identity, "FUNCTION_CALL_REQUIRED", "Function ID is required",
                    "Select an existing callable Function");
            }
            Map<String, Object> selected = TemporaryLifecycleDiagnostics.with(identity, "resourceId", functionId);
            terminalIdentity = selected;
            CoreGraphStorageBoundary.Decoded decoded = FunctionCallSupport.requireSource(flowStorage, admittedCall);
            FunctionSourceDocument source = decoded.functionSourceDocument();
            Map<String, Object> binding = TemporaryLifecycleDiagnostics.with(selected, "revision", decoded.envelope().assetRevision(),
                "graphHash", decoded.envelope().assetHash().canonicalText(), "outcome", "selected");
            terminalIdentity = binding;
            Map<String, Object> safeVariables = variables != null ? new HashMap<>(variables) : new HashMap<>();
            FlowExecutor.FunctionInvocationContext invocation = executor.defaultFunctionInvocationContext(player, event,
                safeVariables, invocationId, RuntimeExecutionContext.NO_DEADLINE);
            if (invocation == null) {
                return rejectFunction(invocationId, started, binding, "FUNCTION_COMPILED_EXECUTION_UNAVAILABLE",
                    "Compiled Function execution is unavailable", "Initialize the compiled Function runtime before dispatching this Function");
            }
            TemporaryLifecycleDiagnostics.event("trigger_binding_selected", started, binding);
            CompletableFuture<Map<String, Object>> future = FunctionCallSupport.executeSource(source, executor, admittedCall,
                player, event, safeVariables, invocation);
            String diagnosticSource = "runtime-function:" + functionId;
            future.whenComplete((result, failure) -> functionTerminal(invocationId, started, binding, diagnosticSource, failure));
            return future;
        } catch (RuntimeException failure) {
            functionTerminal(invocationId, started, terminalIdentity, "runtime-function", failure);
            return CompletableFuture.failedFuture(failure);
        }
    }

    private void functionTerminal(CorrelationId invocationId, long started, Map<String, Object> identity,
                                  String source, Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String code = cause instanceof FlowHandlerException handler ? handler.getCode()
            : cause instanceof FlowExecutor.FlowExecutionException execution ? execution.getCode()
            : cause == null ? "" : "FUNCTION_DISPATCH_FAILED";
        TemporaryLifecycleDiagnostics.terminal("trigger_execution_terminal", started, identity,
            cause == null ? "succeeded" : "failed", code, "runtime-function-completion");
        if (cause != null) {
            CompiledTriggerExecution.warnInvocation(source + "|" + code,
                "Runtime Function invocation failed correlationId=" + invocationId.canonicalText()
                    + " diagnosticCode=" + code + " source=" + source);
        }
    }

    private CompletableFuture<Void> reject(CorrelationId invocationId, long started, Map<String, Object> identity,
                                           String code, String message, String remediation, Map<String, Object> details) {
        TemporaryLifecycleDiagnostics.terminal("trigger_execution_terminal", started, identity, "rejected", code,
            "runtime-dispatch-rejected");
        CompiledTriggerExecution.warnInvocation("runtime-flow|" + code,
            "Runtime Flow invocation rejected correlationId=" + invocationId.canonicalText()
                + " diagnosticCode=" + code + " source=runtime-flow");
        return CompletableFuture.failedFuture(new FlowHandlerException(code, message, remediation, details));
    }

    private CompletableFuture<Map<String, Object>> rejectFunction(CorrelationId invocationId, long started,
                                                                  Map<String, Object> identity, String code,
                                                                  String message, String remediation) {
        TemporaryLifecycleDiagnostics.terminal("trigger_execution_terminal", started, identity, "rejected", code,
            "runtime-function-dispatch-rejected");
        CompiledTriggerExecution.warnInvocation("runtime-function|" + code,
            "Runtime Function invocation rejected correlationId=" + invocationId.canonicalText()
                + " diagnosticCode=" + code + " source=runtime-function");
        return CompletableFuture.failedFuture(new FlowHandlerException(code, message, remediation));
    }

}
