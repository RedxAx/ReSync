package restudio.resync.flow.catalog;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record CatalogFunctionShape(OwnerId owner, String resourceId, Map<PinId, CatalogNodeDescriptor.Pin> parameters) {
    private static final TypeExpr EXECUTION = TypeExpr.named(TypeReference.of("builtin", "execution"));

    public CatalogFunctionShape {
        parameters = Map.copyOf(parameters);
    }

    public static Optional<CatalogFunctionShape> from(OwnerId owner, CatalogNodeDescriptor node) {
        Object identity = node.metadata().get("customFunctionIdentity");
        if (!(identity instanceof Map<?, ?> function) || !owner.value().equals(function.get("owner"))
            || !"local".equals(function.get("namespace")) || !(function.get("id") instanceof String id) || id.isBlank()
            || node.schemaVersion() != 1 || !functionOperation(node)) {
            return Optional.empty();
        }
        Map<PinId, CatalogNodeDescriptor.Pin> parameters = new LinkedHashMap<>();
        Set<FunctionParameterId> identities = new HashSet<>();
        boolean inputFlow = false;
        boolean outputFlow = false;
        for (CatalogNodeDescriptor.Pin pin : node.pins()) {
            boolean input = pin.direction() == CatalogNodeDescriptor.Direction.INPUT;
            if (EXECUTION.equals(pin.type())) {
                if (input && pin.id().value().equals("flow") && !inputFlow) {
                    inputFlow = true;
                } else if (!input && pin.id().value().equals("output_flow") && !outputFlow) {
                    outputFlow = true;
                } else {
                    return Optional.empty();
                }
                continue;
            }
            String prefix = input ? "function-input-" : "function-output-";
            if (!pin.id().value().startsWith(prefix)) {
                return Optional.empty();
            }
            try {
                String value = pin.id().value().substring(prefix.length());
                FunctionParameterId parameter = new FunctionParameterId(UUID.fromString(value));
                if (!parameter.canonicalText().equals(value) || !identities.add(parameter)
                    || parameters.put(pin.id(), pin) != null) {
                    return Optional.empty();
                }
            } catch (IllegalArgumentException exception) {
                return Optional.empty();
            }
        }
        return inputFlow && outputFlow ? Optional.of(new CatalogFunctionShape(owner, id, parameters)) : Optional.empty();
    }

    private static boolean functionOperation(CatalogNodeDescriptor node) {
        if ("custom_function_call".equals(node.handler().operation().id().value())) {
            return true;
        }
        return operation(node.pins().stream().map(pin -> new RuntimeOperationDescriptor.Pin(pin.id(),
            pin.direction() == CatalogNodeDescriptor.Direction.INPUT ? RuntimeOperationDescriptor.Direction.INPUT
                : RuntimeOperationDescriptor.Direction.OUTPUT, pin.type())).toList()).equals(node.handler().operation().id());
    }

    public static OperationId operation(List<RuntimeOperationDescriptor.Pin> pins) {
        String hash = CanonicalJson.sha256("registered-function-shape-v1",
            pins.stream().map(RuntimeOperationDescriptor.Pin::canonicalValue).toList());
        return OperationId.of("function-shape-h" + hash.substring(0, 16) + "-h" + hash.substring(16, 32));
    }

    public static ContractRef<CapabilityId> capability(ContractRef<NodeId> node) {
        String hash = CanonicalJson.sha256("registered-function-call-v1", Map.of("node", node.canonicalText()));
        return ContractRef.of(node.owner(), CapabilityId.of("function-call-h" + hash.substring(0, 16)
            + "-h" + hash.substring(16, 32)));
    }

    public static boolean legacyCapability(ContractRef<?> capability, ContractRef<NodeId> node) {
        String prefix = "handler-customfunctioncallhandler-";
        String value = capability.id().value();
        if (!capability.owner().equals(node.owner()) || !value.startsWith(prefix)) {
            return false;
        }
        String stamp = value.substring(prefix.length()).split("-", 2)[0];
        if (stamp.matches("x[0-9][0-9a-f]{11}")) {
            stamp = stamp.substring(1);
        }
        if (!stamp.matches("[0-9a-f]{12}")) {
            return false;
        }
        return value.equals(legacyId("handler.CustomFunctionCallHandler." + stamp + "." + node.id().value()));
    }

    private static String legacyId(String value) {
        StringBuilder result = new StringBuilder();
        for (String part : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-").split("[._-]+")) {
            if (part.isBlank()) {
                continue;
            }
            String normalized = Character.isLetter(part.charAt(0)) ? part : "x" + part;
            if (normalized.length() > 32) {
                normalized = normalized.substring(0, 32);
            }
            if (!result.isEmpty()) {
                result.append('-');
            }
            result.append(normalized);
        }
        return result.substring(0, Math.min(result.length(), 128));
    }

    public boolean accepts(CatalogFunctionShape next) {
        if (!owner.equals(next.owner) || !resourceId.equals(next.resourceId)) {
            return false;
        }
        for (Map.Entry<PinId, CatalogNodeDescriptor.Pin> entry : parameters.entrySet()) {
            CatalogNodeDescriptor.Pin replacement = next.parameters.get(entry.getKey());
            CatalogNodeDescriptor.Pin previous = entry.getValue();
            if (replacement == null || replacement.direction() != previous.direction()
                || !replacement.type().equals(previous.type())
                || previous.direction() == CatalogNodeDescriptor.Direction.INPUT
                    && previous.requirement() != CatalogNodeDescriptor.Requirement.REQUIRED
                    && replacement.requirement() == CatalogNodeDescriptor.Requirement.REQUIRED) {
                return false;
            }
        }
        for (Map.Entry<PinId, CatalogNodeDescriptor.Pin> entry : next.parameters.entrySet()) {
            if (!parameters.containsKey(entry.getKey()) && entry.getValue().direction() == CatalogNodeDescriptor.Direction.INPUT
                && entry.getValue().requirement() == CatalogNodeDescriptor.Requirement.REQUIRED) {
                return false;
            }
        }
        return true;
    }
}
