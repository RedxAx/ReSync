package restudio.resync.flow.registry;

import restudio.flow.data.FlowDataType;
import restudio.resync.flow.handler.HandlerRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class NodeDefinitionValidator {

    public record ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }
    }

    private final HandlerRegistry handlerRegistry;
    private final boolean strict;

    public NodeDefinitionValidator(HandlerRegistry handlerRegistry, boolean strict) {
        this.handlerRegistry = handlerRegistry;
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
                if (!inputNames.contains(condition.getKey())) {
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
        } else if (dataType == FlowDataType.ANY && strict) {
            if (pin.getType() == NodeDefinition.PinType.DATA) {
                warnings.add("Pin " + pin.getName() + " uses generic ANY data type");
            }
        }

        if (pin.getOptionsSource() != null && !pin.getOptionsSource().isBlank() && !isKnownOptionsSource(pin.getOptionsSource())) {
            errors.add("Unknown optionsSource for pin " + pin.getName() + ": " + pin.getOptionsSource());
        }
    }

    private boolean isKnownOptionsSource(String source) {
        if (source.startsWith("client:minecraft:") || source.startsWith("minecraft:")) {
            String category = source.startsWith("client:minecraft:") ? source.substring("client:minecraft:".length()) : source.substring("minecraft:".length());
            return Set.of(
                "advancement",
                "biome",
                "difficulty",
                "enchantment",
                "entity_type",
                "gamemode",
                "material",
                "particle",
                "potion_effect",
                "sound"
            ).contains(category);
        }
        return false;
    }
}
