package restudio.resync.upgrade.flow;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.canonical.JsonValue.JsonArray;
import restudio.resync.contract.canonical.JsonValue.JsonBoolean;
import restudio.resync.contract.canonical.JsonValue.JsonNumber;
import restudio.resync.contract.canonical.JsonValue.JsonObject;
import restudio.resync.contract.canonical.JsonValue.JsonString;
import restudio.resync.flow.CoreGraphProjectionContext;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.canonical.CanonicalLimits;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.UuidIdentity;
import restudio.resync.flow.protocol.ResourceActivationState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class RawLegacyGraphAdmission {
    private static final Set<String> OUTER_FIELDS = Set.of(
        "activation", "activationState", "assetActivationState", "assetFormatVersion", "assetHash",
        "assetMutationId", "assetRevision", "enabled", "id", "resourceHash", "resourceMutationId",
        "resourceRevision", "resourceType");
    private static final Set<String> ACTIVATION_FIELDS = Set.of("activation", "activationState", "assetActivationState");
    private static final String NODE_ID_DOMAIN = "legacy-flow-node-v1";
    private static final String CONNECTION_ID_DOMAIN = "legacy-flow-connection-v1";
    private static final String NAMESPACE_DOMAIN = "legacy-flow-namespace-v1";

    private RawLegacyGraphAdmission() {
    }

    public static Admitted admit(byte[] source, CoreGraphProjectionContext context) {
        Objects.requireNonNull(source, "source");
        return admitParsed(CanonicalCodec.decodePermissive(source, CanonicalLimits.standard()), context);
    }

    public static Admitted admit(String source, CoreGraphProjectionContext context) {
        Objects.requireNonNull(source, "source");
        return admitParsed(CanonicalCodec.decodePermissive(source, CanonicalLimits.standard()), context);
    }

    private static Admitted admitParsed(JsonValue parsed, CoreGraphProjectionContext context) {
        Objects.requireNonNull(context, "context");
        if (!(parsed instanceof JsonObject raw)) {
            throw new IllegalArgumentException("Legacy graph must be a JSON object");
        }
        String canonicalRaw = raw.canonicalText(CanonicalLimits.standard());
        LegacyOuterEnvelope outer = envelope(raw, context);
        Map<String, JsonValue> innerFields = new LinkedHashMap<>(raw.fields());
        OUTER_FIELDS.forEach(innerFields::remove);
        JsonObject inner = JsonValue.object(innerFields);
        rejectReservedFields(inner);
        Map<String, NodeInstanceId> nodes = nodes(inner, context);
        List<AdmittedConnection> connections = connections(inner, context, nodes);
        String canonicalInner = inner.canonicalText(CanonicalLimits.standard());
        return new Admitted(context, outer, canonicalRaw, canonicalInner, nodes, connections);
    }

    private static LegacyOuterEnvelope envelope(JsonObject source, CoreGraphProjectionContext context) {
        String id = requiredText(source, "id");
        if (!id.equals(context.resource().id())) {
            throw new IllegalArgumentException("Legacy graph ID does not match the declared resource");
        }
        String resourceType = requiredText(source, "resourceType");
        if (!resourceType.equals(context.resource().resourceType().value())) {
            throw new IllegalArgumentException("Legacy graph resource type does not match the declared resource");
        }
        Integer assetFormatVersion = optionalInteger(source, "assetFormatVersion");
        Long resourceRevision = optionalLong(source, "resourceRevision");
        Long assetRevision = optionalLong(source, "assetRevision");
        if (resourceRevision != null && assetRevision != null && !resourceRevision.equals(assetRevision)) {
            throw new IllegalArgumentException("Legacy resource and asset revisions do not match");
        }
        UUID resourceMutationId = optionalUuid(source, "resourceMutationId");
        UUID assetMutationId = optionalUuid(source, "assetMutationId");
        if (resourceMutationId != null && assetMutationId != null && !resourceMutationId.equals(assetMutationId)) {
            throw new IllegalArgumentException("Legacy resource and asset mutation IDs do not match");
        }
        String resourceHash = optionalHash(source, "resourceHash");
        String assetHash = optionalHash(source, "assetHash");
        ResourceActivationState activationState = activationState(source);
        Boolean enabled = optionalBoolean(source, "enabled");
        if (enabled != null) {
            ResourceActivationState enabledState = enabled ? ResourceActivationState.ACTIVE : ResourceActivationState.INACTIVE;
            if (source.contains("assetActivationState") || source.contains("activationState") || source.contains("activation")) {
                if (enabledState != activationState) {
                    throw new IllegalArgumentException("Legacy enabled state conflicts with activation state");
                }
            } else {
                activationState = enabledState;
            }
        }
        return new LegacyOuterEnvelope(id, resourceType, assetFormatVersion, resourceRevision, assetRevision,
            resourceMutationId, assetMutationId, resourceHash, assetHash, activationState, enabled);
    }

    private static ResourceActivationState activationState(JsonObject source) {
        ResourceActivationState result = null;
        for (String field : ACTIVATION_FIELDS) {
            if (!source.contains(field)) {
                continue;
            }
            String value = requiredText(source, field);
            ResourceActivationState state = ResourceActivationState.fromWireName(value);
            if (result != null && result != state) {
                throw new IllegalArgumentException("Legacy activation state aliases do not match");
            }
            result = state;
        }
        return result == null ? ResourceActivationState.ACTIVE : result;
    }

    private static void rejectReservedFields(JsonObject source) {
        for (String key : source.fields().keySet()) {
            if (key.startsWith("asset") || key.startsWith("corePayload") || ACTIVATION_FIELDS.contains(key)) {
                throw new IllegalArgumentException("Legacy graph contains reserved outer field: " + key);
            }
        }
    }

    private static Map<String, NodeInstanceId> nodes(JsonObject source, CoreGraphProjectionContext context) {
        JsonObject rawNodes = requireObject(source, "nodes");
        Map<String, NodeInstanceId> result = new LinkedHashMap<>();
        Set<NodeInstanceId> identities = new HashSet<>();
        UUID namespace = namespace(context);
        rawNodes.fields().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String legacyId = requireText(entry.getKey(), "Legacy node ID");
            if (!(entry.getValue() instanceof JsonObject)) {
                throw new IllegalArgumentException("Legacy node must be an object: " + legacyId);
            }
            NodeInstanceId identity = NodeInstanceId.deterministic(namespace, NODE_ID_DOMAIN,
                CanonicalJson.canonicalize(List.of(context.resource().canonicalText(), legacyId)));
            if (!identities.add(identity) || result.putIfAbsent(legacyId, identity) != null) {
                throw new IllegalArgumentException("Duplicate legacy node identity: " + legacyId);
            }
        });
        return Map.copyOf(result);
    }

    private static List<AdmittedConnection> connections(JsonObject source, CoreGraphProjectionContext context,
                                                         Map<String, NodeInstanceId> nodes) {
        JsonArray rawConnections = requireArray(source, "connections");
        List<AdmittedConnection> result = new ArrayList<>(rawConnections.values().size());
        Set<String> endpoints = new HashSet<>();
        Set<ConnectionId> identities = new HashSet<>();
        UUID namespace = namespace(context);
        for (JsonValue element : rawConnections.values()) {
            if (!(element instanceof JsonObject connection)) {
                throw new IllegalArgumentException("Legacy connection must be an object");
            }
            String sourceNode = requireProperty(connection, "sourceNodeId");
            String sourcePin = requireProperty(connection, "sourcePin");
            String targetNode = requireProperty(connection, "targetNodeId");
            String targetPin = requireProperty(connection, "targetPin");
            NodeInstanceId sourceIdentity = requireNode(nodes, sourceNode);
            NodeInstanceId targetIdentity = requireNode(nodes, targetNode);
            String endpoint = CanonicalJson.canonicalize(List.of(sourceIdentity.canonicalText(), sourcePin,
                targetIdentity.canonicalText(), targetPin));
            if (!endpoints.add(endpoint)) {
                throw new IllegalArgumentException("Duplicate legacy connection endpoint tuple");
            }
            ConnectionId identity = ConnectionId.deterministic(namespace, CONNECTION_ID_DOMAIN,
                CanonicalJson.canonicalize(List.of(context.resource().canonicalText(), endpoint)));
            if (!identities.add(identity)) {
                throw new IllegalArgumentException("Legacy connection identity collision");
            }
            result.add(new AdmittedConnection(identity, sourceIdentity, sourcePin, targetIdentity, targetPin));
        }
        result.sort(Comparator.comparing(value -> value.identity().canonicalText()));
        return List.copyOf(result);
    }

    private static UUID namespace(CoreGraphProjectionContext context) {
        return UuidIdentity.deterministic(UuidIdentity.MIGRATION_NAMESPACE, NAMESPACE_DOMAIN,
            context.resource().canonicalText());
    }

    private static JsonObject requireObject(JsonObject source, String name) {
        JsonValue element = source.value(name);
        if (!(element instanceof JsonObject object)) {
            throw new IllegalArgumentException("Legacy graph requires object field: " + name);
        }
        return object;
    }

    private static JsonArray requireArray(JsonObject source, String name) {
        JsonValue element = source.value(name);
        if (!(element instanceof JsonArray array)) {
            throw new IllegalArgumentException("Legacy graph requires array field: " + name);
        }
        return array;
    }

    private static String requireProperty(JsonObject source, String name) {
        JsonValue element = source.value(name);
        if (!(element instanceof JsonString string)) {
            throw new IllegalArgumentException("Legacy connection requires string field: " + name);
        }
        return requireText(string.value(), name);
    }

    private static NodeInstanceId requireNode(Map<String, NodeInstanceId> nodes, String id) {
        NodeInstanceId identity = nodes.get(id);
        if (identity == null) {
            throw new IllegalArgumentException("Legacy connection references missing node: " + id);
        }
        return identity;
    }

    private static String requiredText(JsonObject source, String field) {
        JsonValue value = source.value(field);
        if (!(value instanceof JsonString string)) {
            throw new IllegalArgumentException("Legacy graph requires string field: " + field);
        }
        return requireText(string.value(), field);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " must be non-blank canonical text");
        }
        CanonicalJson.requireNfc(value);
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(field + " must not contain control characters");
            }
        }
        return value;
    }

    private static Integer optionalInteger(JsonObject source, String field) {
        JsonValue value = source.value(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof JsonNumber number)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            return number.value().intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " must be an integer", exception);
        }
    }

    private static Long optionalLong(JsonObject source, String field) {
        JsonValue value = source.value(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof JsonNumber number)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            long result = number.value().longValueExact();
            if (result < 0) {
                throw new IllegalArgumentException(field + " cannot be negative");
            }
            return result;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " must be an integer", exception);
        }
    }

    private static UUID optionalUuid(JsonObject source, String field) {
        if (!source.contains(field)) {
            return null;
        }
        String text = requiredText(source, field);
        try {
            UUID uuid = UUID.fromString(text);
            if (!uuid.toString().equals(text)) {
                throw new IllegalArgumentException(field + " must be a canonical UUID");
            }
            return uuid;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be a canonical UUID", exception);
        }
    }

    private static String optionalHash(JsonObject source, String field) {
        if (!source.contains(field)) {
            return null;
        }
        JsonValue value = source.value(field);
        if (!(value instanceof JsonString string)) {
            throw new IllegalArgumentException(field + " must be empty or a SHA-256 hash");
        }
        String text = string.value();
        if (!text.equals(text.strip())) {
            throw new IllegalArgumentException(field + " must be canonical text");
        }
        if (!text.isEmpty() && !text.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(field + " must be empty or a SHA-256 hash");
        }
        return text;
    }

    private static Boolean optionalBoolean(JsonObject source, String field) {
        if (!source.contains(field)) {
            return null;
        }
        JsonValue value = source.value(field);
        if (!(value instanceof JsonBoolean booleanValue)) {
            throw new IllegalArgumentException(field + " must be boolean");
        }
        return booleanValue.value();
    }

    private static Object value(JsonValue element) {
        if (element == null || element instanceof JsonValue.JsonNull) {
            return null;
        }
        if (element instanceof JsonObject object) {
            Map<String, Object> result = new LinkedHashMap<>();
            object.fields().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put(entry.getKey(), value(entry.getValue())));
            return result;
        }
        if (element instanceof JsonArray array) {
            List<Object> result = new ArrayList<>();
            array.values().forEach(entry -> result.add(value(entry)));
            return result;
        }
        if (element instanceof JsonBoolean booleanValue) {
            return booleanValue.value();
        }
        if (element instanceof JsonNumber number) {
            return number.value();
        }
        if (element instanceof JsonString string) {
            return string.value();
        }
        throw new IllegalArgumentException("Unsupported JSON value: " + element.getClass().getName());
    }

    public record LegacyOuterEnvelope(String id, String resourceType, Integer assetFormatVersion,
                                      Long resourceRevision, Long assetRevision, UUID resourceMutationId,
                                      UUID assetMutationId, String resourceHash, String assetHash,
                                      ResourceActivationState activationState, Boolean enabled) {
        public LegacyOuterEnvelope {
            id = requireText(id, "id");
            resourceType = requireText(resourceType, "resourceType");
            if (assetFormatVersion != null && assetFormatVersion < 1) {
                throw new IllegalArgumentException("assetFormatVersion must be positive");
            }
            if ((resourceRevision != null && resourceRevision < 0) || (assetRevision != null && assetRevision < 0)) {
                throw new IllegalArgumentException("Legacy revisions cannot be negative");
            }
            if (resourceRevision != null && assetRevision != null && !resourceRevision.equals(assetRevision)) {
                throw new IllegalArgumentException("Legacy resource and asset revisions do not match");
            }
            if (resourceMutationId != null && assetMutationId != null && !resourceMutationId.equals(assetMutationId)) {
                throw new IllegalArgumentException("Legacy resource and asset mutation IDs do not match");
            }
            if (resourceHash != null && !resourceHash.equals(resourceHash.strip())) {
                throw new IllegalArgumentException("resourceHash must be canonical text");
            }
            if (assetHash != null && !assetHash.equals(assetHash.strip())) {
                throw new IllegalArgumentException("assetHash must be canonical text");
            }
            if (resourceHash != null && !resourceHash.isEmpty() && !resourceHash.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("resourceHash must be empty or a SHA-256 hash");
            }
            if (assetHash != null && !assetHash.isEmpty() && !assetHash.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("assetHash must be empty or a SHA-256 hash");
            }
            activationState = Objects.requireNonNull(activationState, "activationState");
        }

        public long effectiveRevision(long fallback) {
            return assetRevision != null ? assetRevision : resourceRevision != null ? resourceRevision : fallback;
        }

        public UUID effectiveMutationId() {
            return assetMutationId != null ? assetMutationId : resourceMutationId;
        }

        public Map<String, Object> canonicalValue() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("activationState", activationState.wireName());
            if (enabled != null) {
                result.put("enabled", enabled);
            }
            result.put("id", id);
            result.put("resourceType", resourceType);
            if (assetFormatVersion != null) {
                result.put("assetFormatVersion", assetFormatVersion);
            }
            if (resourceRevision != null) {
                result.put("resourceRevision", resourceRevision);
            }
            if (assetRevision != null) {
                result.put("assetRevision", assetRevision);
            }
            if (resourceMutationId != null) {
                result.put("resourceMutationId", resourceMutationId.toString());
            }
            if (assetMutationId != null) {
                result.put("assetMutationId", assetMutationId.toString());
            }
            if (resourceHash != null) {
                result.put("resourceHash", resourceHash);
            }
            if (assetHash != null) {
                result.put("assetHash", assetHash);
            }
            return Map.copyOf(result);
        }

    }

    public record Admitted(CoreGraphProjectionContext context, LegacyOuterEnvelope outer, String canonicalRaw,
                           String canonicalInner, Map<String, NodeInstanceId> nodes, List<AdmittedConnection> connections) {
        public Admitted {
            context = Objects.requireNonNull(context, "context");
            outer = Objects.requireNonNull(outer, "outer");
            canonicalRaw = Objects.requireNonNull(canonicalRaw, "canonicalRaw");
            canonicalInner = Objects.requireNonNull(canonicalInner, "canonicalInner");
            nodes = Map.copyOf(nodes);
            connections = List.copyOf(connections);
        }

        public JsonObject rawJson() {
            return CanonicalCodec.requireObject(CanonicalCodec.decode(canonicalRaw));
        }

        public JsonObject innerJson() {
            return CanonicalCodec.requireObject(CanonicalCodec.decode(canonicalInner));
        }
    }

    public record AdmittedConnection(ConnectionId identity, NodeInstanceId sourceNode, String sourcePin,
                                     NodeInstanceId targetNode, String targetPin) {
        public AdmittedConnection {
            identity = Objects.requireNonNull(identity, "identity");
            sourceNode = Objects.requireNonNull(sourceNode, "sourceNode");
            sourcePin = requireText(sourcePin, "sourcePin");
            targetNode = Objects.requireNonNull(targetNode, "targetNode");
            targetPin = requireText(targetPin, "targetPin");
        }
    }
}
