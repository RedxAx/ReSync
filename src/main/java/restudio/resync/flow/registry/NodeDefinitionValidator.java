package restudio.resync.flow.registry;

import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class NodeDefinitionValidator {
    private static final Set<String> RESERVED_VISIBLE_WHEN_KEYS = Set.of("__flow_branches");
    private static final Set<String> CLOCK_DOMAINS = Set.of("wall_time", "monotonic_elapsed", "server_ticks", "world_day_time");

    public record ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }
    }

    private final HandlerRegistry handlerRegistry;
    private final OptionCatalogRegistry optionCatalogRegistry;
    private final boolean strict;

    public NodeDefinitionValidator(HandlerRegistry handlerRegistry, boolean strict) {
        this(handlerRegistry, null, strict);
    }

    public NodeDefinitionValidator(HandlerRegistry handlerRegistry, OptionCatalogRegistry optionCatalogRegistry, boolean strict) {
        this.handlerRegistry = handlerRegistry;
        this.optionCatalogRegistry = optionCatalogRegistry;
        this.strict = strict;
    }

    public NodeDefinitionValidator(HandlerRegistry handlerRegistry) {
        this(handlerRegistry, true);
    }

    public ValidationResult validate(NodeDefinition def) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (def == null) {
            errors.add("Node definition is null");
            return new ValidationResult(false, errors, warnings);
        }

        if (def.getId() == null || def.getId().isBlank()) {
            errors.add("Node ID is required");
        }

        if (def.getDisplayName() == null || def.getDisplayName().isBlank()) {
            errors.add("Display name is required");
        }

        if (def.getSchemaVersion() < 1) {
            errors.add("Schema version must be positive");
        }

        if (def.getDescription() == null || def.getDescription().isBlank()) {
            warnings.add("Node description is missing");
        }

        if (def.getTags() == null || def.getTags().isEmpty()) {
            warnings.add("Node search tags are missing");
        }

        if (def.getExamples() == null || def.getExamples().isEmpty()) {
            warnings.add("Node usage hint is missing");
        }

        if (def.getAuthorizationPolicy() == null || def.getAuthorizationPolicy().isBlank()) {
            errors.add("Node authorization policy is required");
        }

        if (def.isDestructive() && "none".equals(def.getAuditPolicy())) {
            errors.add("Destructive nodes must declare an audit policy");
        }

        if (def.isDestructive() && "none".equals(def.getConfirmationPolicy())) {
            errors.add("Destructive nodes must declare a confirmation policy");
        }

        if (def.isHidden() && def.getHiddenReason().isBlank()) {
            errors.add("Hidden nodes must declare a hidden reason");
        }

        if (def.isDeprecated() && (def.getReplacementFor() == null || def.getReplacementFor().isBlank())
            && (def.getCanonicalId() == null || def.getCanonicalId().isBlank())) {
            errors.add("Deprecated node must declare a replacement or canonical migration target");
        }

        validateClockDomain(def, errors);

        if (def.getKind() == null) {
            errors.add("Node kind is required");
        }

        if (!def.isTrigger()) {
            String handlerId = def.getHandler();
            if (handlerId == null || handlerId.isBlank()) {
                if (def.getKind() != NodeDefinition.NodeKind.ALIAS) {
                    errors.add("Non-trigger node must specify a handler");
                }
            } else if (handlerRegistry != null && !handlerRegistry.hasHandler(handlerId)) {
                String msg = "Handler not registered: " + handlerId;
                if (strict) {
                    errors.add(msg);
                } else {
                    warnings.add(msg);
                }
            } else if (handlerRegistry != null) {
                Object operation = def.getHandlerConfig() != null ? def.getHandlerConfig().get("operation") : null;
                if (operation instanceof String operationId && !handlerRegistry.hasOperation(handlerId, operationId)) {
                    errors.add("Handler " + handlerId + " does not declare operation: " + operationId);
                }
            }
        }

        validateAvailability(def, errors);

        Set<String> inputNames = new HashSet<>();
        if (def.getInputs() != null) {
            for (NodeDefinition.PinDefinition pin : def.getInputs()) {
                validatePin(pin, NodeDefinition.PinDirection.INPUT, errors, warnings);
                if (pin.getName() != null && !inputNames.add(pin.getName())) {
                    errors.add("Duplicate input pin name: " + pin.getName());
                }
            }
        }

        Set<String> outputNames = new HashSet<>();
        if (def.getOutputs() != null) {
            for (NodeDefinition.PinDefinition pin : def.getOutputs()) {
                validatePin(pin, NodeDefinition.PinDirection.OUTPUT, errors, warnings);
                if (pin.getName() != null && !outputNames.add(pin.getName())) {
                    errors.add("Duplicate output pin name: " + pin.getName());
                }
            }
        }

        validateVisibleWhen(def, inputNames, errors);
        validateKindContract(def, errors, warnings);

        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }

    private void validateClockDomain(NodeDefinition definition, List<String> errors) {
        String clockDomain = definition.getClockDomain();
        if (isTemporal(definition) && !definition.isHidden() && clockDomain.isBlank()) {
            errors.add("Temporal nodes must declare a clock domain");
            return;
        }
        if (clockDomain.isBlank()) {
            return;
        }
        for (String domain : clockDomain.split(",")) {
            if (!CLOCK_DOMAINS.contains(domain.strip())) {
                errors.add("Unknown clock domain: " + domain.strip());
            }
        }
    }

    private boolean isTemporal(NodeDefinition definition) {
        if ("TimeHandler".equals(definition.getHandler()) || "ScheduleHandler".equals(definition.getHandler())) {
            return true;
        }
        Object operation = definition.getHandlerConfig() != null ? definition.getHandlerConfig().get("operation") : null;
        return "delay".equals(operation) || "loop_interval".equals(operation);
    }

    private void validateAvailability(NodeDefinition def, List<String> errors) {
        NodeDefinition.Availability availability = def.getAvailability();
        if (availability == null) {
            return;
        }
        if (availability.getPlugin() != null && availability.getPlugin().isBlank()) {
            errors.add("Availability plugin cannot be blank");
        }
        if (availability.getPlatform() != null && availability.getPlatform().isBlank()) {
            errors.add("Availability platform cannot be blank");
        }
    }

    private void validateVisibleWhen(NodeDefinition def, Set<String> inputNames, List<String> errors) {
        validateVisibleWhen(def.getInputs(), inputNames, errors);
        validateVisibleWhen(def.getOutputs(), inputNames, errors);
    }

    private void validateVisibleWhen(List<NodeDefinition.PinDefinition> pins, Set<String> inputNames, List<String> errors) {
        if (pins == null) {
            return;
        }
        for (NodeDefinition.PinDefinition pin : pins) {
            for (Map.Entry<String, String> condition : pin.getVisibleWhen().entrySet()) {
                if (!inputNames.contains(condition.getKey()) && !RESERVED_VISIBLE_WHEN_KEYS.contains(condition.getKey())) {
                    errors.add("Pin " + pin.getName() + " visibleWhen references unknown input: " + condition.getKey());
                }
                if (condition.getValue() == null || condition.getValue().isBlank()) {
                    errors.add("Pin " + pin.getName() + " visibleWhen has blank expected value for " + condition.getKey());
                }
            }
        }
    }

    private void validateKindContract(NodeDefinition def, List<String> errors, List<String> warnings) {
        boolean hasFlowInput = hasPin(def.getInputs(), NodeDefinition.PinType.FLOW);
        boolean hasFlowOutput = hasPin(def.getOutputs(), NodeDefinition.PinType.FLOW);
        switch (def.getKind()) {
            case EVENT -> {
                if (!def.isTrigger()) {
                    errors.add("Event nodes must be trigger nodes");
                }
                if (!hasFlowOutput) {
                    errors.add("Event nodes must expose a flow output");
                }
            }
            case ACTION, FAMILY -> {
                if (!hasFlowInput) {
                    errors.add(def.getKind() + " nodes must expose a flow input");
                }
                if (!hasFlowOutput) {
                    warnings.add(def.getKind() + " nodes should expose a flow output");
                }
                if (def.getKind() == NodeDefinition.NodeKind.FAMILY && !hasModeOrActionInput(def)) {
                    errors.add("Family nodes must expose a mode or action input");
                }
            }
            case QUERY, PURE -> {
                if (hasFlowInput) {
                    warnings.add(def.getKind() + " nodes should not require a flow input");
                }
            }
            case ALIAS -> {
                if (def.getCanonicalId() == null || def.getCanonicalId().isBlank()) {
                    errors.add("Alias nodes must specify canonicalId");
                }
                if (!def.isHidden()) {
                    warnings.add("Alias nodes should be hidden from the palette");
                }
            }
        }
    }

    private boolean hasPin(List<NodeDefinition.PinDefinition> pins, NodeDefinition.PinType pinType) {
        if (pins == null) {
            return false;
        }
        return pins.stream().anyMatch(pin -> pin.getType() == pinType);
    }

    private boolean hasModeOrActionInput(NodeDefinition def) {
        if (def.getInputs() == null) {
            return false;
        }
        return def.getInputs().stream().anyMatch(pin ->
            pin.getType() == NodeDefinition.PinType.DATA
                && ("mode".equalsIgnoreCase(pin.getName()) || "action".equalsIgnoreCase(pin.getName()))
        );
    }

    private void validatePin(NodeDefinition.PinDefinition pin, NodeDefinition.PinDirection expectedDirection, List<String> errors, List<String> warnings) {
        if (pin == null) {
            errors.add("Pin is null in " + expectedDirection.name().toLowerCase() + "s");
            return;
        }

        if (pin.getName() == null || pin.getName().isBlank()) {
            errors.add("Pin name is required for " + expectedDirection.name().toLowerCase());
        }

        if (pin.getDirection() != expectedDirection) {
            errors.add("Pin " + pin.getName() + " has mismatched direction");
        }

        if (pin.getType() == null) {
            errors.add("Pin type is required for " + pin.getName());
        }

        FlowDataType dataType = pin.getDataType();
        if (dataType == null) {
            errors.add("Data type is required for pin " + pin.getName());
        } else if (!dataType.isResolved()) {
            errors.add("Unresolved data type for pin " + pin.getName() + ": " + dataType.getId() + " owned by " + dataType.getOwner());
        } else if (dataType == FlowDataType.ANY && strict) {
            if (pin.getType() == NodeDefinition.PinType.DATA) {
                warnings.add("Pin " + pin.getName() + " uses generic ANY data type");
            }
        }
        validateTypeRef(pin, errors, warnings);
        NodeDefinition.RepeatablePin repeatable = pin.getRepeatable();
        if (repeatable != null) {
            if (expectedDirection != NodeDefinition.PinDirection.INPUT || pin.getType() != NodeDefinition.PinType.DATA) {
                errors.add("Repeatable pin " + pin.getName() + " must be a data input");
            }
            if (repeatable.getGroupId() == null || repeatable.getGroupId().isBlank()) {
                errors.add("Repeatable pin " + pin.getName() + " requires a groupId");
            }
            if (repeatable.getMaxItems() < 1 || repeatable.getMinItems() > repeatable.getMaxItems()) {
                errors.add("Repeatable pin " + pin.getName() + " has invalid item bounds");
            }
        }

        if (pin.getOptionsSource() != null && !pin.getOptionsSource().isBlank() && (optionCatalogRegistry == null || !optionCatalogRegistry.contains(pin.getOptionsSource()))) {
            errors.add("Unknown optionsSource for pin " + pin.getName() + ": " + pin.getOptionsSource());
        }
    }

    private void validateTypeRef(NodeDefinition.PinDefinition pin, List<String> errors, List<String> warnings) {
        FlowTypeRef typeRef = pin.getTypeRef();
        if (typeRef == null) {
            errors.add("Type reference is required for pin " + pin.getName());
            return;
        }
        validateTypeRef(typeRef, pin.getName(), errors);
        int arguments = typeRef.getArguments().size();
        switch (typeRef.getTypeId()) {
            case "list", "set", "queue", "stack", "optional", "result", "job_reference" -> {
                if (arguments == 0) {
                    errors.add("Pin " + pin.getName() + " must declare an element type for " + typeRef.getTypeId());
                } else if (arguments != 1) {
                    errors.add("Pin " + pin.getName() + " requires one type argument for " + typeRef.getTypeId());
                }
            }
            case "map" -> {
                if (arguments == 0) {
                    errors.add("Pin " + pin.getName() + " must declare key and value types for map");
                } else if (arguments != 2) {
                    errors.add("Pin " + pin.getName() + " requires key and value type arguments for map");
                }
            }
            default -> {
                if (arguments != 0 && !"resource_reference".equals(typeRef.getTypeId())) {
                    errors.add("Pin " + pin.getName() + " uses type arguments on non-generic type " + typeRef.getTypeId());
                }
            }
        }
    }

    private void validateTypeRef(FlowTypeRef typeRef, String pinName, List<String> errors) {
        if (typeRef.isTypeVariable()) {
            return;
        }
        if ("resource_reference".equals(typeRef.getTypeId())) {
            if (typeRef.getArguments().size() > 1) {
                errors.add("Resource reference pin " + pinName + " accepts at most one resource kind");
            } else if (!typeRef.getArguments().isEmpty()) {
                String resourceKind = typeRef.getArguments().getFirst().getTypeId();
                if (ReSyncResourceCatalog.byType(resourceKind) == null && !resourceKind.contains(":")) {
                    errors.add("Unknown resource kind for pin " + pinName + ": " + resourceKind);
                }
            }
            return;
        }
        for (FlowTypeRef argument : typeRef.getArguments()) {
            if (argument.isTypeVariable()) {
                continue;
            }
            FlowDataType type = FlowDataType.fromString(argument.getTypeId());
            if (!type.isResolved()) {
                errors.add("Unresolved type argument for pin " + pinName + ": " + argument.getTypeId() + " owned by " + type.getOwner());
            }
            validateTypeRef(argument, pinName, errors);
        }
    }
}
