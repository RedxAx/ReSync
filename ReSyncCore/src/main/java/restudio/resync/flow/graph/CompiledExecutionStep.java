package restudio.resync.flow.graph;

import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.runtime.RuntimeBindingDescriptor;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypedValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class CompiledExecutionStep {
    private final UUID stepId;
    private final NodeInstanceId nodeId;
    private final ContractRef<NodeId> definition;
    private final CatalogNodeDescriptor.Handler handler;
    private final Map<PinId, TypedValue> inputBindings;
    private final Map<PinId, List<GraphEndpoint>> outputBindings;
    private final RuntimeSemantics semantics;
    private final RuntimeBindingDescriptor resolvedBinding;
    private final ContentHash resolvedBindingFingerprint;
    private final LoopControl loopControl;
    private final OpaqueData unknown;

    public CompiledExecutionStep(UUID stepId, NodeInstanceId nodeId, ContractRef<NodeId> definition, CatalogNodeDescriptor.Handler handler,
                                 Map<PinId, TypedValue> inputBindings, Map<PinId, List<GraphEndpoint>> outputBindings,
                                 RuntimeSemantics semantics, RuntimeBindingDescriptor resolvedBinding,
                                 ContentHash resolvedBindingFingerprint,
                                 OpaqueData unknown) {
        this.stepId = Objects.requireNonNull(stepId, "stepId");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.definition = Objects.requireNonNull(definition, "definition");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.inputBindings = immutableValues(inputBindings);
        this.outputBindings = immutableEndpoints(outputBindings);
        this.semantics = semantics;
        this.resolvedBinding = validateResolvedBinding(resolvedBinding, handler, semantics);
        this.resolvedBindingFingerprint = validateResolvedBindingFingerprint(resolvedBinding, resolvedBindingFingerprint);
        this.loopControl = null;
        this.unknown = unknown != null ? unknown : OpaqueData.empty();
    }

    private CompiledExecutionStep(CompiledExecutionStep source, LoopControl loopControl) {
        this.stepId = source.stepId;
        this.nodeId = source.nodeId;
        this.definition = source.definition;
        this.handler = source.handler;
        this.inputBindings = source.inputBindings;
        this.outputBindings = source.outputBindings;
        this.semantics = source.semantics;
        this.resolvedBinding = source.resolvedBinding;
        this.resolvedBindingFingerprint = source.resolvedBindingFingerprint;
        this.loopControl = loopControl;
        this.unknown = source.unknown;
    }

    public CompiledExecutionStep(UUID stepId, NodeInstanceId nodeId, ContractRef<NodeId> definition, CatalogNodeDescriptor.Handler handler,
                                 Map<PinId, TypedValue> inputBindings, Map<PinId, List<GraphEndpoint>> outputBindings,
                                 RuntimeSemantics semantics, OpaqueData unknown) {
        this(stepId, nodeId, definition, handler, inputBindings, outputBindings, semantics, null, null, unknown);
    }

    public CompiledExecutionStep(UUID stepId, NodeInstanceId nodeId, ContractRef<NodeId> definition, CatalogNodeDescriptor.Handler handler,
                                 Map<PinId, TypedValue> inputBindings, Map<PinId, List<GraphEndpoint>> outputBindings,
                                 RuntimeSemantics semantics, RuntimeBindingDescriptor resolvedBinding,
                                 OpaqueData unknown) {
        this(stepId, nodeId, definition, handler, inputBindings, outputBindings, semantics, resolvedBinding,
            resolvedBinding == null ? null : resolvedBinding.executionFingerprint(), unknown);
    }

    public CompiledExecutionStep(UUID stepId, NodeInstanceId nodeId, ContractRef<NodeId> definition, CatalogNodeDescriptor.Handler handler,
                                 Map<PinId, TypedValue> inputBindings, Map<PinId, List<GraphEndpoint>> outputBindings,
                                 OpaqueData unknown) {
        this(stepId, nodeId, definition, handler, inputBindings, outputBindings, null, unknown);
    }

    public UUID stepId() {
        return stepId;
    }

    public NodeInstanceId nodeId() {
        return nodeId;
    }

    public ContractRef<NodeId> definition() {
        return definition;
    }

    public CatalogNodeDescriptor.Handler handler() {
        return handler;
    }

    public Map<PinId, TypedValue> inputBindings() {
        return inputBindings;
    }

    public Map<PinId, List<GraphEndpoint>> outputBindings() {
        return outputBindings;
    }

    public RuntimeSemantics semantics() {
        return semantics;
    }

    public RuntimeBindingDescriptor resolvedBinding() {
        return resolvedBinding;
    }

    public ContentHash resolvedBindingFingerprint() {
        return resolvedBindingFingerprint;
    }

    public LoopControl loopControl() {
        return loopControl;
    }

    public CompiledExecutionStep withLoopControl(LoopControl value) {
        return new CompiledExecutionStep(this, Objects.requireNonNull(value, "loopControl"));
    }

    public OpaqueData unknown() {
        return unknown;
    }

    Map<String, Object> canonicalValue() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("stepId", stepId.toString());
        values.put("nodeId", nodeId.canonicalText());
        values.put("definition", Map.of("ownerId", definition.owner().canonicalText(), "localId", definition.id().canonicalText()));
        values.put("handler", Map.of(
            "capability", Map.of("ownerId", handler.capability().owner().canonicalText(), "localId", handler.capability().id().canonicalText()),
            "operation", Map.of("ownerId", handler.operation().owner().canonicalText(), "localId", handler.operation().id().canonicalText())));
        if (semantics != null) {
            values.put("semantics", CanonicalJson.parse(semantics.canonical()));
        }
        if (resolvedBinding != null) {
            values.put("resolvedBinding", CanonicalJson.parse(resolvedBinding.canonical()));
            values.put("resolvedBindingFingerprint", resolvedBindingFingerprint.canonicalText());
        }
        if (loopControl != null) {
            values.put("loopControl", loopControl.canonicalValue());
        }
        LinkedHashMap<String, Object> inputs = new LinkedHashMap<>();
        inputBindings.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> inputs.put(entry.getKey().canonicalText(), CanonicalJson.parse(entry.getValue().canonicalJson())));
        values.put("inputBindings", inputs);
        LinkedHashMap<String, Object> outputs = new LinkedHashMap<>();
        outputBindings.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> outputs.put(entry.getKey().canonicalText(), entry.getValue().stream().map(GraphEndpoint::canonicalValue).toList()));
        values.put("outputBindings", outputs);
        return OpaqueData.mergeKnownFields(unknown, values);
    }

    private static Map<PinId, TypedValue> immutableValues(Map<PinId, TypedValue> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<PinId, TypedValue> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(Objects.requireNonNull(key, "input pin ID"), Objects.requireNonNull(value, "input value")));
        return Collections.unmodifiableMap(copy);
    }

    private static Map<PinId, List<GraphEndpoint>> immutableEndpoints(Map<PinId, List<GraphEndpoint>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<PinId, List<GraphEndpoint>> copy = new LinkedHashMap<>();
        source.forEach((key, endpoints) -> copy.put(Objects.requireNonNull(key, "output pin ID"), List.copyOf(endpoints != null ? endpoints : List.of())));
        return Collections.unmodifiableMap(copy);
    }

    private static RuntimeBindingDescriptor validateResolvedBinding(RuntimeBindingDescriptor binding,
                                                                    CatalogNodeDescriptor.Handler handler,
                                                                    RuntimeSemantics semantics) {
        if (binding == null) {
            return null;
        }
        if (!binding.capability().equals(handler.capability()) || !binding.operation().equals(handler.operation())) {
            throw new IllegalArgumentException("Resolved Runtime Binding Does Not Match The Step Handler");
        }
        if (!binding.available()) {
            throw new IllegalArgumentException("Resolved Runtime Binding Must Be Available");
        }
        if (semantics != null && !binding.semantics().equals(semantics)) {
            throw new IllegalArgumentException("Resolved Runtime Binding Semantics Do Not Match The Step");
        }
        return binding;
    }

    private static ContentHash validateResolvedBindingFingerprint(RuntimeBindingDescriptor binding, ContentHash fingerprint) {
        if (binding == null && fingerprint != null) {
            throw new IllegalArgumentException("Resolved Runtime Binding Fingerprint Requires A Resolved Binding");
        }
        return fingerprint == null && binding != null ? binding.executionFingerprint() : fingerprint;
    }

    public record LoopControl(Kind kind, PinId entryInput, PinId sourceInput, PinId bodyOutput, PinId doneOutput,
                              PinId indexOutput, PinId elementOutput, PinId completedOutput, TypeExpr sourceType,
                              TypeExpr elementType, List<NodeInstanceId> bodySteps) {
        public LoopControl {
            kind = Objects.requireNonNull(kind, "loop kind");
            entryInput = Objects.requireNonNull(entryInput, "loop entry input");
            sourceInput = Objects.requireNonNull(sourceInput, "loop source input");
            bodyOutput = Objects.requireNonNull(bodyOutput, "loop body output");
            doneOutput = Objects.requireNonNull(doneOutput, "loop done output");
            indexOutput = Objects.requireNonNull(indexOutput, "loop index output");
            completedOutput = Objects.requireNonNull(completedOutput, "loop completed output");
            sourceType = Objects.requireNonNull(sourceType, "loop source type");
            bodySteps = List.copyOf(Objects.requireNonNull(bodySteps, "loop body steps"));
            if (kind == Kind.COUNT && (elementOutput != null || elementType != null)) {
                throw new IllegalArgumentException("Count Loop Control Cannot Declare An Element Output");
            }
            if (kind == Kind.FOR_EACH && (elementOutput == null || elementType == null)) {
                throw new IllegalArgumentException("For Each Loop Control Requires An Element Output");
            }
            if (bodySteps.stream().distinct().count() != bodySteps.size()) {
                throw new IllegalArgumentException("Loop Control Body Steps Must Be Unique");
            }
        }

        Map<String, Object> canonicalValue() {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            values.put("kind", kind.name());
            values.put("entryInput", entryInput.canonicalText());
            values.put("sourceInput", sourceInput.canonicalText());
            values.put("bodyOutput", bodyOutput.canonicalText());
            values.put("doneOutput", doneOutput.canonicalText());
            values.put("indexOutput", indexOutput.canonicalText());
            if (elementOutput != null) {
                values.put("elementOutput", elementOutput.canonicalText());
            }
            values.put("completedOutput", completedOutput.canonicalText());
            values.put("sourceType", sourceType.canonicalValue());
            if (elementType != null) {
                values.put("elementType", elementType.canonicalValue());
            }
            values.put("bodySteps", bodySteps.stream().map(NodeInstanceId::canonicalText).toList());
            return Map.copyOf(values);
        }

        public enum Kind {
            COUNT,
            FOR_EACH
        }
    }
}
