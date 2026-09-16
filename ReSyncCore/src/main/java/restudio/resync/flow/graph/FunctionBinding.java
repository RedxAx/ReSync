package restudio.resync.flow.graph;

import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class FunctionBinding {
    private final ServerResourceLocator function;
    private final long revision;
    private final List<FunctionParameter> inputs;
    private final List<FunctionParameter> outputs;
    private final OpaqueData unknown;

    public FunctionBinding(ServerResourceLocator function, long revision, List<FunctionParameter> inputs, List<FunctionParameter> outputs, OpaqueData unknown) {
        this.function = Objects.requireNonNull(function, "function");
        if (revision < 0) {
            throw new IllegalArgumentException("Function revision cannot be negative");
        }
        this.revision = revision;
        this.inputs = List.copyOf(inputs != null ? inputs : List.of());
        this.outputs = List.copyOf(outputs != null ? outputs : List.of());
        Set<FunctionParameterId> ids = new HashSet<>();
        this.inputs.forEach(parameter -> addId(ids, parameter));
        this.outputs.forEach(parameter -> addId(ids, parameter));
        this.unknown = unknown != null ? unknown : OpaqueData.empty();
        this.unknown.rejectKnownFields("function", "revision", "inputs", "outputs");
    }

    public FunctionBinding(ServerResourceLocator function, long revision, List<FunctionParameter> inputs, List<FunctionParameter> outputs) {
        this(function, revision, inputs, outputs, OpaqueData.empty());
    }

    public ServerResourceLocator function() {
        return function;
    }

    public long revision() {
        return revision;
    }

    public List<FunctionParameter> inputs() {
        return inputs;
    }

    public List<FunctionParameter> outputs() {
        return outputs;
    }

    public OpaqueData unknown() {
        return unknown;
    }

    Map<String, Object> canonicalValue() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("function", function.canonicalValue());
        values.put("revision", revision);
        values.put("inputs", inputs.stream().map(FunctionParameter::canonicalValue).toList());
        values.put("outputs", outputs.stream().map(FunctionParameter::canonicalValue).toList());
        return OpaqueData.mergeKnownFields(unknown, values);
    }

    private static void addId(Set<FunctionParameterId> ids, FunctionParameter parameter) {
        FunctionParameter value = Objects.requireNonNull(parameter, "parameter");
        if (!ids.add(value.parameterId())) {
            throw new IllegalArgumentException("Duplicate Function parameter ID: " + value.parameterId());
        }
    }
}
