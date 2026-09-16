package restudio.resync.flow.function;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record CompiledFunction(FunctionSignature signature, CompiledFunctionBody body) {
    public CompiledFunction {
        signature = Objects.requireNonNull(signature, "Compiled Function Signature Is Required");
        body = Objects.requireNonNull(body, "Compiled Function Body Is Required");
    }

    public Map<String, Object> canonicalValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("body", body.canonicalValue());
        value.put("signature", signature.canonicalValue());
        return Map.copyOf(value);
    }

    public String canonicalJson() {
        return FunctionContractSupport.canonicalJson(canonicalValue());
    }
}
