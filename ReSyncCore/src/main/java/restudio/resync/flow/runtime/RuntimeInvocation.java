package restudio.resync.flow.runtime;

import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.flow.graph.FunctionBinding;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;

public record RuntimeInvocation(
    RuntimeBindingKey binding,
    Map<PinId, TypedValue> inputs,
    String idempotencyKey,
    RuntimeCancellationToken cancellationToken,
    RuntimeExecutionContext executionContext,
    CompiledRuntimeContext runtimeContext,
    CorrelationId invocationId,
    List<FunctionBinding> functionBindings,
    RuntimeScope scope
) {
    public RuntimeInvocation(RuntimeBindingKey binding, Map<PinId, TypedValue> inputs, String idempotencyKey,
            RuntimeCancellationToken cancellationToken, RuntimeExecutionContext executionContext,
            CompiledRuntimeContext runtimeContext, CorrelationId invocationId) {
        this(binding, inputs, idempotencyKey, cancellationToken, executionContext, runtimeContext, invocationId, List.of(), null);
    }
    public RuntimeInvocation {
        binding = Objects.requireNonNull(binding, "Binding Is Required");
        inputs = immutableInputs(inputs);
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "Idempotency Key Is Required").trim();
        cancellationToken = Objects.requireNonNull(cancellationToken, "Cancellation Token Is Required");
        functionBindings = List.copyOf(Objects.requireNonNull(functionBindings, "Invocation Function Bindings Are Required"));
        if (idempotencyKey.isEmpty()) {
            throw new IllegalArgumentException("Idempotency Key Is Required");
        }
        if (executionContext != null && executionContext.principal() != null
            && runtimeContext != null && runtimeContext.principal() != null
            && !executionContext.principal().canonical().equals(runtimeContext.principal().canonical())) {
            throw new IllegalArgumentException("Runtime Invocation Principals Must Match");
        }
    }

    public RuntimeInvocation(RuntimeBindingKey binding, String idempotencyKey, RuntimeCancellationToken cancellationToken) {
        this(binding, Map.of(), idempotencyKey, cancellationToken, null, null, null);
    }

    public RuntimeInvocation(
        RuntimeBindingKey binding,
        Map<PinId, TypedValue> inputs,
        String idempotencyKey,
        RuntimeCancellationToken cancellationToken
    ) {
        this(binding, inputs, idempotencyKey, cancellationToken, null, null, null);
    }

    public RuntimeInvocation(
        RuntimeBindingKey binding,
        Map<PinId, TypedValue> inputs,
        String idempotencyKey,
        RuntimeCancellationToken cancellationToken,
        RuntimeExecutionContext executionContext
    ) {
        this(binding, inputs, idempotencyKey, cancellationToken, executionContext, null, null);
    }

    public RuntimeInvocation withCancellationToken(RuntimeCancellationToken token) {
        return new RuntimeInvocation(binding, inputs, idempotencyKey, token, executionContext, runtimeContext, invocationId, functionBindings, scope);
    }

    public RuntimeInvocation withExecutionContext(RuntimeExecutionContext context) {
        return new RuntimeInvocation(binding, inputs, idempotencyKey, cancellationToken, context, runtimeContext, invocationId, functionBindings, scope);
    }

    public RuntimeInvocation withRuntimeContext(CompiledRuntimeContext context) {
        return new RuntimeInvocation(binding, inputs, idempotencyKey, cancellationToken, executionContext, context, invocationId, functionBindings, scope);
    }

    public RuntimeInvocation withInvocationId(CorrelationId rootInvocationId) {
        return new RuntimeInvocation(binding, inputs, idempotencyKey, cancellationToken, executionContext, runtimeContext,
            Objects.requireNonNull(rootInvocationId, "Invocation ID Is Required"), functionBindings, scope);
    }

    public RuntimeInvocation withFunctionBindings(List<FunctionBinding> functions) {
        return new RuntimeInvocation(binding, inputs, idempotencyKey, cancellationToken, executionContext, runtimeContext,
            invocationId, functions, scope);
    }

    public RuntimeInvocation withScope(RuntimeScope runtimeScope) {
        return new RuntimeInvocation(binding, inputs, idempotencyKey, cancellationToken, executionContext, runtimeContext,
            invocationId, functionBindings, runtimeScope);
    }

    public long deadlineMillis() {
        long tokenDeadline = cancellationToken.deadlineMillis();
        long contextDeadline = executionContext == null
            ? RuntimeExecutionContext.NO_DEADLINE
            : executionContext.deadlineMillis();
        return Math.min(tokenDeadline, contextDeadline);
    }

    public RuntimePrincipal principal() {
        return executionContext == null ? null : executionContext.principal();
    }

    public boolean deadlineExceeded() {
        long deadline = deadlineMillis();
        return deadline != RuntimeExecutionContext.NO_DEADLINE && System.currentTimeMillis() >= deadline;
    }

    public void throwIfCancelled() {
        cancellationToken.throwIfCancelled();
    }

    private static Map<PinId, TypedValue> immutableInputs(Map<PinId, TypedValue> values) {
        Objects.requireNonNull(values, "Inputs Are Required");
        LinkedHashMap<PinId, TypedValue> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(
            Objects.requireNonNull(key, "Input Pin Cannot Be Null"),
            Objects.requireNonNull(value, "Input Value Cannot Be Null")));
        return Collections.unmodifiableMap(copy);
    }
}
