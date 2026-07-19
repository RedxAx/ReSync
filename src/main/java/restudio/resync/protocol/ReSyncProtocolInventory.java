package restudio.resync.protocol;

import restudio.resync.contracts.ReSyncProtocolContract;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ReSyncProtocolInventory {
    private ReSyncProtocolInventory() {
    }

    public static List<Map<String, Object>> snapshot() {
        return Arrays.stream(ReSyncProtocolContract.class.getFields())
            .filter(field -> Modifier.isPublic(field.getModifiers()) && Modifier.isStatic(field.getModifiers()))
            .filter(field -> field.getType().isPrimitive() || field.getType() == String.class)
            .sorted((first, second) -> String.CASE_INSENSITIVE_ORDER.compare(first.getName(), second.getName()))
            .map(ReSyncProtocolInventory::entry)
            .toList();
    }

    private static Map<String, Object> entry(Field field) {
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
