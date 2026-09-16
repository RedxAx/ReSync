package restudio.resync.protocol;


import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ReSyncProtocolInventory {
    private ReSyncProtocolInventory() {
    }

    public static List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> inventory = new ArrayList<>(Arrays.stream(ReSyncProtocolContract.class.getFields())
            .filter(field -> Modifier.isPublic(field.getModifiers()) && Modifier.isStatic(field.getModifiers()))
            .filter(field -> field.getType().isPrimitive() || field.getType() == String.class)
            .sorted((first, second) -> String.CASE_INSENSITIVE_ORDER.compare(first.getName(), second.getName()))
            .map(ReSyncProtocolInventory::entry)
            .toList());
        if (inventory.stream().noneMatch(item -> "MESSAGE_PROTOCOL_ENVELOPE".equals(item.get("id")))
            && Arrays.stream(MessageType.values()).anyMatch(type -> type == MessageType.PROTOCOL_ENVELOPE)) {
            inventory.add(protocolEnvelopeEntry());
        }
        inventory.sort((first, second) -> String.CASE_INSENSITIVE_ORDER.compare(
            String.valueOf(first.get("id")), String.valueOf(second.get("id"))));
        return List.copyOf(inventory);
    }

    public static Map<String, Object> protocolEnvelopeEntry() {
        return protocolEnvelopeEntry(MessageType.PROTOCOL_ENVELOPE);
    }

    private static Map<String, Object> entry(Field field) {
        if ("MESSAGE_PROTOCOL_ENVELOPE".equals(field.getName())) {
            return protocolEnvelopeEntry(MessageType.PROTOCOL_ENVELOPE);
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", field.getName());
        item.put("kind", kind(field.getName()));
        item.put("value", value(field));
        item.put("valueType", field.getType().getSimpleName());
        item.put("owner", "shared-contract");
        item.put("disposition", "supported");
        item.put("requirements", List.of("PROTO-001", "PROTO-002", "PROTO-010"));
        return Map.copyOf(item);
    }

    private static Object value(Field field) {
        try {
            Object value = field.get(null);
            if (value instanceof Byte byteValue) {
                return Byte.toUnsignedInt(byteValue);
            }
            if (value instanceof Short shortValue) {
                return Short.toUnsignedInt(shortValue);
            }
            return value;
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Shared protocol constant is inaccessible: " + field.getName(), exception);
        }
    }

    private static Map<String, Object> protocolEnvelopeEntry(MessageType messageType) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "MESSAGE_PROTOCOL_ENVELOPE");
        item.put("kind", "message");
        item.put("value", Byte.toUnsignedInt(messageType.getValue()));
        item.put("valueType", "byte");
        item.put("owner", "shared-contract");
        item.put("authority", "generic-envelope");
        item.put("disposition", "supported");
        item.put("resourceAuthority", "server-durable-when-advertised");
        var resourceContract = ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT;
        item.put("resourceContractVersion", Map.of(
            "generation", resourceContract.version().generation(),
            "minor", resourceContract.version().minor()));
        item.put("resourceCapabilities", resourceContract.capabilities().stream()
            .map(capability -> capability.id().value())
            .sorted()
            .toList());
        item.put("resourceOperations", Map.of(
            "read", List.of("list", "query", "load"),
            "mutate", List.of("create", "save", "delete", "duplicate", "activate"),
            "unsupported", List.of("rename", "move", "subscribe")));
        item.put("aggregateCreate", Map.of(
            "version", Map.of("generation", 1, "minor", 2),
            "capability", ReSyncProtocolContract.RESOURCE_CREATE_PRESENTATION_CAPABILITY.id().value(),
            "settlement", "resource-and-project-metadata"));
        item.put("legacyResourceRoutes", Map.of(
            "read", "compatibility-projection",
            "save", "blocked",
            "delete", "blocked"));
        item.put("requirements", List.of("PROTO-001", "PROTO-002", "PROTO-010"));
        return Map.copyOf(item);
    }

    private static String kind(String name) {
        if (name.contains("_PACKET_") || name.startsWith("FLOW_PACKET_")) {
            return "packet";
        }
        if (name.startsWith("MESSAGE_")) {
            return "message";
        }
        if (name.startsWith("CHANNEL_")) {
            return "channel";
        }
        if (name.endsWith("_VERSION") || name.endsWith("VERSION")) {
            return "version";
        }
        if (name.startsWith("MAX_")) {
            return "limit";
        }
        return "contract";
    }
}
