package restudio.resync.server;

import restudio.resync.api.OptionCatalogItem;
import restudio.flow.data.FlowDataType;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogOwned;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.inspector.InspectorOptionSource;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class CoreOptionCatalogSupport {
    static Binding binding(CatalogSnapshot catalog, ContractRef<InspectorFieldId> sourceRef,
                           ContractRef<CapabilityId> query) {
        Objects.requireNonNull(catalog, "Catalog snapshot is required");
        Objects.requireNonNull(sourceRef, "Option source reference is required");
        Objects.requireNonNull(query, "Option query capability is required");
        CatalogOwned<InspectorOptionSource> owned = catalog.optionSource(sourceRef)
            .orElseThrow(() -> rejected(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE,
                "Option source is unavailable"));
        InspectorOptionSource descriptor = owned.descriptor();
        if (!descriptor.capability().equals(query) || !owned.key().owner().equals(sourceRef.owner())) {
            throw rejected(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE,
                "Option source does not own the requested query capability");
        }
        List<String> sources = new ArrayList<>();
        for (CatalogOwned<CatalogNodeDescriptor> node : catalog.definitions()) {
            collect(node.descriptor(), CatalogNodeDescriptor.Direction.INPUT, "inputs", sourceRef, sources);
            collect(node.descriptor(), CatalogNodeDescriptor.Direction.OUTPUT, "outputs", sourceRef, sources);
        }
        List<String> distinct = new ArrayList<>();
        for (String source : sources) {
            if (source == null || source.isBlank()) {
                continue;
            }
            String value = source.strip();
            if (distinct.stream().noneMatch(existing -> existing.equalsIgnoreCase(value))) {
                distinct.add(value);
            }
        }
        if (distinct.size() != 1) {
            throw rejected(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE,
                "Option source provider binding is missing or ambiguous in the active catalog");
        }
        return new Binding(descriptor, distinct.getFirst());
    }

    static TypedValue value(TypeExpr type, ServerId serverId, OptionCatalogItem item) {
        Objects.requireNonNull(type, "Option type is required");
        Objects.requireNonNull(serverId, "Option server ID is required");
        if (item == null || item.value() == null || item.value().isBlank()) {
            throw rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                "Option provider returned a blank identity");
        }
        String value = item.value();
        if (type instanceof TypeExpr.ResourceType resource) {
            TypeReference reference = resource.resourceType();
            ContractRef<ResourceTypeId> resourceType = ContractRef.of(OwnerId.of(reference.ownerId()),
                ResourceTypeId.of(reference.localId()));
            return TypedValue.locator(type, new ServerResourceLocator(serverId, resourceType, value));
        }
        if (type instanceof TypeExpr.OpaqueType opaque) {
            return TypedValue.opaque(opaque, value);
        }
        if (!(type instanceof TypeExpr.Named named) || !named.arguments().isEmpty()
            || !"builtin".equals(named.reference().ownerId())) {
            throw rejected(ProtocolRejectionCode.UNSUPPORTED_GENERATION,
                "Option provider type does not have an exact scalar materializer");
        }
        Object material = switch (named.reference().localId()) {
            case "string" -> value;
            case "boolean" -> parseBoolean(value);
            case "integer" -> parseInteger(value);
            case "number" -> parseNumber(value);
            case "uuid" -> parseUuid(value);
            default -> symbolicValue(named, value);
        };
        return TypedValue.value(type, material);
    }

    private static String symbolicValue(TypeExpr.Named named, String value) {
        FlowDataType type = FlowDataType.fromString(named.reference().localId());
        if (type.isResolved() && "builtin".equals(type.getOwner())
            && named.reference().localId().equals(type.getId()) && type.getJavaType().isEnum()) {
            return value;
        }
        throw rejected(ProtocolRejectionCode.UNSUPPORTED_GENERATION,
            "Option provider type does not have an exact scalar materializer");
    }

    static Object material(TypedValue value) {
        if (value == null || value.state() == TypedValue.State.ABSENT) {
            return null;
        }
        return value.locator() != null ? value.locator().canonicalText() : value.value();
    }

    private static void collect(CatalogNodeDescriptor node, CatalogNodeDescriptor.Direction direction,
                                String field, ContractRef<InspectorFieldId> sourceRef, List<String> sources) {
        List<CatalogNodeDescriptor.Pin> pins = node.pins().stream()
            .filter(pin -> pin.direction() == direction).toList();
        List<?> authoredPins = authoredPins(node, field);
        if (authoredPins == null || authoredPins.size() != pins.size()) {
            if (pins.stream().anyMatch(pin -> sourceRef.equals(pin.optionSource()))) {
                throw rejected(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE,
                    "Option source provider metadata is unavailable in the active catalog");
            }
            return;
        }
        for (int index = 0; index < pins.size(); index++) {
            CatalogNodeDescriptor.Pin pin = pins.get(index);
            if (!sourceRef.equals(pin.optionSource())) {
                continue;
            }
            Object raw = authoredPins.get(index);
            if (!(raw instanceof Map<?, ?> values) || !validPinId(values, pin.id().value())
                || !validDirection(values.get("direction"), direction)
                || !(values.get("optionsSource") instanceof String source) || source.isBlank()) {
                throw rejected(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE,
                    "Option source provider metadata is unavailable in the active catalog");
            }
            sources.add(source);
        }
    }

    private static List<?> authoredPins(CatalogNodeDescriptor node, String field) {
        Map<String, Object> metadata = node.metadata();
        Object canonical = metadata.get("authoredSource");
        if (canonical instanceof Map<?, ?> authored && authored.get(field) instanceof List<?> pins) {
            return pins;
        }
        Object legacy = metadata.get(field);
        return legacy instanceof List<?> pins ? pins : null;
    }

    private static boolean validDirection(Object raw, CatalogNodeDescriptor.Direction direction) {
        return raw == null || direction.name().equalsIgnoreCase(String.valueOf(raw));
    }

    private static boolean validPinId(Map<?, ?> values, String pinId) {
        Object canonical = values.get("id");
        return canonical != null ? pinId.equals(canonical) : pinId.equals(values.get("name"));
    }

    private static Boolean parseBoolean(String value) {
        if ("true".equals(value)) {
            return Boolean.TRUE;
        }
        if ("false".equals(value)) {
            return Boolean.FALSE;
        }
        throw rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
            "Option provider returned a noncanonical boolean");
    }

    private static BigInteger parseInteger(String value) {
        if (!value.matches("0|-?[1-9][0-9]*")) {
            throw rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                "Option provider returned a noncanonical integer");
        }
        try {
            return new BigInteger(value);
        } catch (NumberFormatException exception) {
            throw rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                "Option provider returned an invalid integer", exception);
        }
    }

    private static BigDecimal parseNumber(String value) {
        try {
            BigDecimal parsed = CanonicalJson.parseDecimal(value);
            if (!CanonicalJson.canonicalDecimal(parsed).equals(value)) {
                throw rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                    "Option provider returned a noncanonical number");
            }
            return parsed;
        } catch (OptionQueryAuthority.Rejected rejection) {
            throw rejection;
        } catch (RuntimeException exception) {
            throw rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                "Option provider returned an invalid number", exception);
        }
    }

    private static UUID parseUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                    "Option provider returned a noncanonical UUID");
            }
            return parsed;
        } catch (OptionQueryAuthority.Rejected rejection) {
            throw rejection;
        } catch (RuntimeException exception) {
            throw rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                "Option provider returned an invalid UUID", exception);
        }
    }

    private static OptionQueryAuthority.Rejected rejected(ProtocolRejectionCode code, String message) {
        return new OptionQueryAuthority.Rejected(code, message);
    }

    private static OptionQueryAuthority.Rejected rejected(ProtocolRejectionCode code, String message,
                                                           Throwable cause) {
        return new OptionQueryAuthority.Rejected(code, message, cause);
    }

    record Binding(InspectorOptionSource descriptor, String providerSourceId) {
        Binding {
            Objects.requireNonNull(descriptor, "Option source descriptor is required");
            providerSourceId = Objects.requireNonNull(providerSourceId, "Provider source ID is required").strip();
            if (providerSourceId.isBlank()) {
                throw new IllegalArgumentException("Provider source ID is required");
            }
        }
    }

    private CoreOptionCatalogSupport() {
    }
}
