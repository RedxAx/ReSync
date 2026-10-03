package restudio.resync.flow.graph;

import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogOwned;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypedValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class FunctionBoundaryPins {
    private static final String INPUT_PREFIX = "function-output-";
    private static final String OUTPUT_PREFIX = "function-input-";

    private FunctionBoundaryPins() {
    }

    public static boolean isBoundary(CatalogOwned<CatalogNodeDescriptor> owned, String operation) {
        BoundaryRole expected = switch (operation) {
            case "function_start" -> BoundaryRole.INPUTS;
            case "function_end" -> BoundaryRole.OUTPUTS;
            default -> null;
        };
        return expected != null && expected == role(owned);
    }

    public static Map<PinId, EffectivePin> resolve(CatalogOwned<CatalogNodeDescriptor> owned,
                                                   FunctionSignature signature) {
        return resolve(owned, signature, null);
    }

    public static Map<PinId, EffectivePin> resolve(CatalogOwned<CatalogNodeDescriptor> owned,
                                                   FunctionSignature signature, GraphNode node) {
        LinkedHashMap<PinId, EffectivePin> pins = new LinkedHashMap<>();
        owned.descriptor().pins().forEach(pin -> pins.put(pin.id(), EffectivePin.from(pin)));
        if (signature != null) {
            BoundaryRole role = role(owned);
            if (role == BoundaryRole.INPUTS) {
                signature.inputs().forEach(parameter -> add(pins, parameter, INPUT_PREFIX,
                    CatalogNodeDescriptor.Direction.OUTPUT));
            } else if (role == BoundaryRole.OUTPUTS) {
                signature.outputs().forEach(parameter -> add(pins, parameter, OUTPUT_PREFIX,
                    CatalogNodeDescriptor.Direction.INPUT));
            }
        }
        if (node != null) {
            Map<PinId, TypeExpr> inputs = new LinkedHashMap<>();
            pins.forEach((id, pin) -> {
                if (pin.direction() == CatalogNodeDescriptor.Direction.INPUT) {
                    inputs.put(id, pin.type());
                }
            });
            StringTemplatePins.derive(node, inputs, pins.keySet()).forEach((id, type) ->
                pins.putIfAbsent(id, new EffectivePin(id, CatalogNodeDescriptor.Direction.INPUT, type, null,
                    CatalogNodeDescriptor.RepeatableIntent.disabled())));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(pins));
    }

    private static void add(Map<PinId, EffectivePin> pins, FunctionParameterContract parameter, String prefix,
                            CatalogNodeDescriptor.Direction direction) {
        PinId id = PinId.of(prefix + parameter.id().canonicalText());
        EffectivePin previous = pins.putIfAbsent(id, new EffectivePin(id, direction, parameter.type(), null,
            CatalogNodeDescriptor.RepeatableIntent.disabled()));
        if (previous != null && (previous.direction() != direction || !previous.type().equals(parameter.type()))) {
            throw new IllegalArgumentException("Function boundary pin collides with a catalog pin: " + id.canonicalText());
        }
    }

    private static BoundaryRole role(CatalogOwned<CatalogNodeDescriptor> owned) {
        CatalogNodeDescriptor descriptor = owned.descriptor();
        boolean ownedHandler = owned.key().owner().equals(descriptor.handler().capability().owner())
            && owned.key().owner().equals(descriptor.handler().operation().owner());
        if (!ownedHandler) {
            return null;
        }
        BoundaryRole declared = declaredRole(descriptor.metadata());
        if (declared != null) {
            return declared;
        }
        Object authored = descriptor.metadata().get("authoredSource");
        if (authored instanceof Map<?, ?> source) {
            declared = declaredRole(source);
            if (declared != null) {
                return declared;
            }
            Object handlerConfig = source.get("handlerConfig");
            if (handlerConfig instanceof Map<?, ?> config) {
                declared = declaredRole(config);
                if (declared != null) {
                    return declared;
                }
            }
        }
        return switch (descriptor.handler().operation().id().value()) {
            case "function_start" -> BoundaryRole.INPUTS;
            case "function_end" -> BoundaryRole.OUTPUTS;
            default -> null;
        };
    }

    private static BoundaryRole declaredRole(Map<?, ?> metadata) {
        BoundaryRole role = roleValue(metadata.get("coreRole"));
        return role != null ? role : roleValue(metadata.get("functionBoundary"));
    }

    private static BoundaryRole roleValue(Object value) {
        if (value instanceof Map<?, ?> values) {
            return roleValue(values.get("role"));
        }
        if (!(value instanceof String text)) {
            return null;
        }
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "input", "inputs", "function-start", "function_start" -> BoundaryRole.INPUTS;
            case "output", "outputs", "function-end", "function_end" -> BoundaryRole.OUTPUTS;
            default -> null;
        };
    }

    public static BoundaryRole role(Map<?, ?> metadata, String operation) {
        BoundaryRole role = declaredRole(metadata);
        if (role == null && metadata.get("handlerConfig") instanceof Map<?, ?> config) {
            role = declaredRole(config);
        }
        if (role != null) {
            return role;
        }
        return switch (operation) {
            case "function_start" -> BoundaryRole.INPUTS;
            case "function_end" -> BoundaryRole.OUTPUTS;
            default -> null;
        };
    }

    public enum BoundaryRole {
        INPUTS,
        OUTPUTS
    }

    public record EffectivePin(PinId id, CatalogNodeDescriptor.Direction direction, TypeExpr type,
                               TypedValue defaultValue, CatalogNodeDescriptor.RepeatableIntent repeatable) {
        static EffectivePin from(CatalogNodeDescriptor.Pin pin) {
            return new EffectivePin(pin.id(), pin.direction(), pin.type(), pin.defaultValue(), pin.repeatable());
        }
    }
}
