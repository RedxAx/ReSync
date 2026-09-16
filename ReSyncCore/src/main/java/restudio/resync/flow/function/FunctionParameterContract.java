package restudio.resync.flow.function;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypedValue;

public record FunctionParameterContract(
    FunctionParameterId id,
    TypeExpr type,
    boolean required,
    TypedValue defaultValue,
    Map<String, Object> unknown
) {
    public FunctionParameterContract {
        id = Objects.requireNonNull(id, "Function Parameter ID Is Required");
        type = Objects.requireNonNull(type, "Function Parameter Type Is Required");
        if (defaultValue != null && !type.equals(defaultValue.type())) {
            throw new IllegalArgumentException("Function Parameter Default Type Must Match The Parameter Type");
        }
        if (required && defaultValue != null && defaultValue.state() == TypedValue.State.ABSENT) {
            throw new IllegalArgumentException("Required Function Parameters Cannot Use An Absent Default");
        }
        unknown = FunctionContractSupport.immutableMap(unknown, "Function Parameter Unknown Data");
    }

    public FunctionParameterContract(FunctionParameterId id, TypeExpr type) {
        this(id, type, true, null, Map.of());
    }

    public FunctionParameterContract(FunctionParameterId id, TypeExpr type, boolean required) {
        this(id, type, required, null, Map.of());
    }

    public FunctionParameterContract(FunctionParameterId id, TypeExpr type, boolean required, TypedValue defaultValue) {
        this(id, type, required, defaultValue, Map.of());
    }

    public Map<String, Object> canonicalValue() {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("id", id.canonicalText());
        value.put("required", required);
        value.put("type", type.canonicalValue());
        if (defaultValue != null) {
            value.put("defaultValue", defaultValue.canonicalValue());
        }
        return FunctionContractSupport.immutableMap(mergeUnknown(value), "Function Parameter Canonical Data");
    }

    private Map<String, Object> mergeUnknown(Map<String, Object> known) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>(unknown);
        known.forEach((key, value) -> {
            if (result.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Function Parameter Unknown Data Collides With Known Field: " + key);
            }
        });
        return result;
    }
}
