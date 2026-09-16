package restudio.flow.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

final class FlowResourceReferenceCodec {
    private static final Set<String> LEGACY_FIELDS = Set.of("kind", "id", "owner", "available", "metadata");
    private static final Set<String> LEGACY_DISCRIMINATOR_FIELDS = Set.of("kind", "owner", "available", "metadata");
    private static final Set<String> CANONICAL_FIELDS = Set.of("serverId", "type", "id", "key");
    private static final Set<String> JOB_FIELDS = Set.of("createdAt", "state", "progress", "outcome", "cancellationRequested");
    private static final Pattern OWNER_ID = Pattern.compile("[a-z][a-z0-9]{0,31}(?:[.-][a-z][a-z0-9]{0,31})*");
    private static final Pattern LOCAL_ID = Pattern.compile("[a-z][a-z0-9]{0,31}(?:[._-][a-z0-9][a-z0-9]{0,31})*");
    private static final Pattern RESOURCE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private FlowResourceReferenceCodec() {
    }

    static Object decode(Object value, String expectedServerId) {
        if (value instanceof Map<?, ?> map) {
            if (isLegacyCandidate(map)) {
                return decodeLegacy(map, expectedServerId);
            }
            if (isCanonicalCandidate(map)) {
                return decodeCanonical(map, expectedServerId);
            }
            return decodeMap(map, expectedServerId);
        }
        if (value instanceof List<?> list) {
            List<Object> decoded = new ArrayList<>(list.size());
            for (Object entry : list) {
                decoded.add(decode(entry, expectedServerId));
            }
            return decoded;
        }
        return value;
    }

    static Map<String, Object> decodeMap(Map<?, ?> value, String expectedServerId) {
        Map<String, Object> decoded = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw malformed("Resource value map keys must be strings");
            }
            decoded.put(key, decode(entry.getValue(), expectedServerId));
        }
        return decoded;
    }

    private static boolean isCanonicalCandidate(Map<?, ?> value) {
        Object type = value.get("type");
        return value.containsKey("serverId") && value.containsKey("id") && type instanceof Map<?, ?> typeMap
            && typeMap.containsKey("ownerId") && typeMap.containsKey("localId")
            && !value.keySet().stream().anyMatch(key -> key instanceof String field && LEGACY_DISCRIMINATOR_FIELDS.contains(field));
    }

    private static boolean isLegacyCandidate(Map<?, ?> value) {
        if (JOB_FIELDS.stream().anyMatch(value::containsKey)) {
            return false;
        }
        return value.containsKey("kind") && value.containsKey("id") && value.containsKey("owner")
            && value.containsKey("available") && value.containsKey("metadata")
            && value.get("kind") instanceof String
            && value.get("id") instanceof String
            && value.get("owner") instanceof String
            && value.get("available") instanceof Boolean
            && value.get("metadata") instanceof Map<?, ?>
            && !value.containsKey("type") && !value.containsKey("key");
    }

    private static FlowResourceReference decodeLegacy(Map<?, ?> value, String expectedServerId) {
        rejectCanonicalFields(value);
        String kind = text(value, "kind");
        String id = text(value, "id");
        String owner = text(value, "owner");
        Object availableValue = value.get("available");
        if (!(availableValue instanceof Boolean available)) {
            throw malformed("Resource reference available flag must be boolean");
        }
        Object metadataValue = value.get("metadata");
        if (!(metadataValue instanceof Map<?, ?> metadata)) {
            throw malformed("Resource reference metadata must be an object");
        }
        Map<String, Object> decodedMetadata = decodeMap(metadata, expectedServerId);
        String metadataServerId = optionalText(decodedMetadata.get("serverId"), "metadata.serverId");
        String topLevelServerId = optionalText(value.get("serverId"), "serverId");
        if (metadataServerId != null && topLevelServerId != null && !metadataServerId.equals(topLevelServerId)) {
            throw malformed("Resource reference server identity is inconsistent");
        }
        String declaredServerId = topLevelServerId != null ? topLevelServerId : metadataServerId;
        verifyServer(declaredServerId, expectedServerId);
        if (declaredServerId != null) {
            decodedMetadata.put("serverId", declaredServerId);
        }
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw malformed("Resource reference fields must be strings");
            }
            if (!LEGACY_FIELDS.contains(key) && !"serverId".equals(key)) {
                decodedMetadata.putIfAbsent(key, decode(entry.getValue(), expectedServerId));
            }
        }
        return new FlowResourceReference(kind, id, owner, available, decodedMetadata);
    }

    private static FlowResourceReference decodeCanonical(Map<?, ?> value, String expectedServerId) {
        if (value.keySet().stream().anyMatch(key -> key instanceof String field && LEGACY_DISCRIMINATOR_FIELDS.contains(field))) {
            throw malformed("Resource reference cannot mix legacy and canonical identity fields");
        }
        String serverId = text(value, "serverId");
        validateServerId(serverId);
        String id = canonicalText(value, "id", RESOURCE_ID);
        Object typeValue = value.get("type");
        if (!(typeValue instanceof Map<?, ?> type)) {
            throw malformed("Resource locator type must be an object");
        }
        String owner = canonicalText(type, "ownerId", OWNER_ID);
        String kind = canonicalText(type, "localId", LOCAL_ID);
        verifyServer(serverId, expectedServerId);
        if (value.containsKey("key")) {
            validateKey(value.get("key"), owner, kind, id);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("serverId", serverId);
        Map<String, Object> unknownType = unknown(type, Set.of("ownerId", "localId"), expectedServerId);
        if (!unknownType.isEmpty()) {
            metadata.put("type", unknownType);
        }
        if (value.containsKey("key") && value.get("key") instanceof Map<?, ?> key) {
            Map<String, Object> unknownKey = unknown(key, Set.of("type", "id"), expectedServerId);
            if (!unknownKey.isEmpty()) {
                metadata.put("key", unknownKey);
            }
        }
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            if (!(entry.getKey() instanceof String field)) {
                throw malformed("Resource locator fields must be strings");
            }
            if (!CANONICAL_FIELDS.contains(field)) {
                metadata.put(field, decode(entry.getValue(), expectedServerId));
            }
        }
        return new FlowResourceReference(kind, id, owner, true, metadata);
    }

    private static Map<String, Object> unknown(Map<?, ?> value, Set<String> known, String expectedServerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            if (!(entry.getKey() instanceof String field)) {
                throw malformed("Resource identity fields must be strings");
            }
            if (!known.contains(field)) {
                result.put(field, decode(entry.getValue(), expectedServerId));
            }
        }
        return result;
    }

    private static void validateKey(Object value, String owner, String kind, String id) {
        if (!(value instanceof Map<?, ?> key)) {
            throw malformed("Resource locator key must be an object");
        }
        Object typeValue = key.get("type");
        if (!(typeValue instanceof Map<?, ?> type)
            || !owner.equals(canonicalText(type, "ownerId", OWNER_ID))
            || !kind.equals(canonicalText(type, "localId", LOCAL_ID))
            || !id.equals(canonicalText(key, "id", RESOURCE_ID))) {
            throw malformed("Resource locator key does not match its identity");
        }
    }

    private static void rejectCanonicalFields(Map<?, ?> value) {
        if (value.containsKey("type") || value.containsKey("key")) {
            throw malformed("Resource reference cannot mix legacy and canonical identity fields");
        }
    }

    private static String text(Map<?, ?> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof String text) || text.isBlank() || !text.equals(text.strip())) {
            throw malformed("Resource identity field " + field + " is invalid");
        }
        return text;
    }

    private static String optionalText(Object raw, String field) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String text) || text.isBlank() || !text.equals(text.strip())) {
            throw malformed("Resource identity field " + field + " is invalid");
        }
        return text;
    }

    private static String canonicalText(Map<?, ?> value, String field, Pattern pattern) {
        String text = text(value, field);
        if (!pattern.matcher(text).matches()) {
            throw malformed("Resource identity field " + field + " is not canonical");
        }
        return text;
    }

    private static void validateServerId(String value) {
        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException failure) {
            throw malformed("Resource server identity is not canonical");
        }
        if (!parsed.toString().equals(value) || !value.equals(value.toLowerCase(Locale.ROOT))
            || (parsed.version() != 4 && parsed.version() != 5) || parsed.variant() != 2) {
            throw malformed("Resource server identity is not canonical");
        }
    }

    private static void verifyServer(String declaredServerId, String expectedServerId) {
        if (declaredServerId != null && expectedServerId != null && !declaredServerId.equals(expectedServerId)) {
            throw new IllegalArgumentException("RESOURCE_REFERENCE_SERVER_MISMATCH");
        }
    }

    private static IllegalArgumentException malformed(String message) {
        return new IllegalArgumentException("RESOURCE_REFERENCE_MALFORMED: " + message);
    }
}
