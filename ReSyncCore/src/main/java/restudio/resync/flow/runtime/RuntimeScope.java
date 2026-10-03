package restudio.resync.flow.runtime;

import restudio.resync.flow.function.FunctionInputMap;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.type.TypedValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class RuntimeScope {
    private final Map<String, Object> locals = Collections.synchronizedMap(new LinkedHashMap<>());
    private final FunctionInputMap functionInputs;
    private final FunctionSignature functionSignature;

    public RuntimeScope(FunctionInputMap functionInputs) {
        this(null, functionInputs);
    }

    public RuntimeScope(FunctionSignature functionSignature, FunctionInputMap functionInputs) {
        this(functionSignature, functionInputs, Map.of());
    }

    public RuntimeScope(FunctionSignature functionSignature, FunctionInputMap functionInputs, Map<String, TypedValue> defaults) {
        this.functionInputs = Objects.requireNonNull(functionInputs, "Runtime Function Inputs Are Required");
        this.functionSignature = functionSignature;
        Objects.requireNonNull(defaults, "Admitted Local Defaults Are Required").forEach((name, value) -> locals.put(name,
            switch (value.state()) {
                case ABSENT, NULL -> null;
                case LOCATOR -> value.locator();
                case VALUE, OPAQUE -> copy(value.value());
            }));
    }

    private static Object copy(Object value) {
        if (value instanceof List<?> list) {
            List<Object> copied = new ArrayList<>(list.size());
            list.forEach(entry -> copied.add(copy(entry)));
            return copied;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copied = new LinkedHashMap<>();
            map.forEach((key, entry) -> copied.put(key, copy(entry)));
            return copied;
        }
        return value;
    }

    public Map<String, Object> locals() {
        return locals;
    }

    public FunctionInputMap functionInputs() {
        return functionInputs;
    }

    public FunctionSignature functionSignature() {
        return functionSignature;
    }
}
