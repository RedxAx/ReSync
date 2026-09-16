package restudio.resync.flow.function;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record FunctionRuntimeContext(
    FunctionLocator function,
    FunctionRevision revision,
    UUID invocationId,
    FunctionInputMap inputs,
    FunctionCancellation cancellation,
    Map<String, Object> attributes
) {
    public FunctionRuntimeContext {
        function = Objects.requireNonNull(function, "Function Locator Is Required");
        revision = Objects.requireNonNull(revision, "Function Revision Is Required");
        invocationId = Objects.requireNonNull(invocationId, "Function Invocation ID Is Required");
        inputs = Objects.requireNonNull(inputs, "Function Input Map Is Required");
        cancellation = cancellation == null ? FunctionCancellation.none() : cancellation;
        attributes = FunctionContractSupport.immutableMap(attributes, "Function Runtime Attributes");
    }

    public FunctionRuntimeContext(FunctionLocator function, FunctionRevision revision, UUID invocationId, FunctionInputMap inputs) {
        this(function, revision, invocationId, inputs, FunctionCancellation.none(), Map.of());
    }

    public FunctionRuntimeContext withCancellation(FunctionCancellation value) {
        return new FunctionRuntimeContext(function, revision, invocationId, inputs, value, attributes);
    }

    public FunctionRuntimeContext withAttribute(String key, Object value) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>(attributes);
        copy.put(FunctionContractSupport.text(key, "Function Runtime Attribute Key", 256), FunctionContractSupport.freeze(value, "Function Runtime Attribute"));
        return new FunctionRuntimeContext(function, revision, invocationId, inputs, cancellation, copy);
    }

    public Map<String, Object> canonicalValue() {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("function", function.canonicalValue());
        value.put("revision", revision.value());
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
