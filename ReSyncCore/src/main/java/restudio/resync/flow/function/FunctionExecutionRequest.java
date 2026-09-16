package restudio.resync.flow.function;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record FunctionExecutionRequest(
    FunctionSignature signature,
    UUID invocationId,
    FunctionInputMap inputs,
    FunctionCancellation cancellation,
    Map<String, Object> attributes
) {
    public FunctionExecutionRequest {
        signature = Objects.requireNonNull(signature, "Function Execution Signature Is Required");
        invocationId = Objects.requireNonNull(invocationId, "Function Execution Invocation ID Is Required");
        inputs = Objects.requireNonNull(inputs, "Function Execution Inputs Are Required");
        cancellation = cancellation == null ? FunctionCancellation.none() : cancellation;
        attributes = FunctionContractSupport.immutableMap(attributes, "Function Execution Attributes");
    }

    public FunctionExecutionRequest(FunctionSignature signature, UUID invocationId, FunctionInputMap inputs) {
        this(signature, invocationId, inputs, FunctionCancellation.none(), Map.of());
    }

    public FunctionLocator function() {
        return signature.function();
    }

    public FunctionRevision revision() {
        return signature.revision();
    }

    public FunctionRuntimeContext context() {
        return new FunctionRuntimeContext(function(), revision(), invocationId, inputs, cancellation, attributes);
    }

    public FunctionExecutionRequest withCancellation(FunctionCancellation value) {
        return new FunctionExecutionRequest(signature, invocationId, inputs, value, attributes);
    }

    public FunctionExecutionRequest withAttribute(String key, Object value) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>(attributes);
        copy.put(FunctionContractSupport.text(key, "Function Execution Attribute Key", 256),
            FunctionContractSupport.freeze(value, "Function Execution Attribute"));
        return new FunctionExecutionRequest(signature, invocationId, inputs, cancellation, copy);
    }

    public Map<String, Object> canonicalValue() {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("signature", signature.canonicalValue());
        value.put("invocationId", invocationId.toString());
        value.put("inputs", inputs.canonicalValue());
        value.put("cancellation", cancellation.canonicalValue());
        if (!attributes.isEmpty()) {
            value.put("attributes", attributes);
        }
        return Map.copyOf(value);
    }

    public String canonicalJson() {
        return FunctionContractSupport.canonicalJson(canonicalValue());
    }
}
