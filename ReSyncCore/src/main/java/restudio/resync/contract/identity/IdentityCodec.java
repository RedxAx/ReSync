package restudio.resync.contract.identity;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.IdentitySupport;
import restudio.resync.flow.identity.LocalId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceKey;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.identity.UuidIdentity;

import java.util.ArrayList;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

public final class IdentityCodec {
    public static final UUID MIGRATION_NAMESPACE = UUID.fromString("b6d7f3d4-3d48-5ce2-8a83-6fc6730f3f2f");

    private IdentityCodec() {
    }

    public static <T extends LocalId> JsonValue.JsonObject encodeReference(ContractRef<T> reference) {
        Objects.requireNonNull(reference, "Contract reference is required");
        return object(reference.unknown(), Map.of(
                "ownerId", reference.owner().canonicalText(),
                "localId", reference.id().canonicalText()));
    }

    public static JsonValue.JsonObject encode(ContractRef<?> reference) {
        return encodeReferenceUnchecked(reference);
    }

    public static <T extends LocalId> ContractRef<T> decodeReference(JsonValue value, Function<String, T> idFactory) {
        JsonValue.JsonObject object = object(value, "Contract reference");
        Objects.requireNonNull(idFactory, "Local ID factory is required");
        String owner = string(object, "ownerId");
        String local = string(object, "localId");
        return new ContractRef<>(new OwnerId(owner), idFactory.apply(local), unknown(object, "ownerId", "localId"));
    }

    public static <T extends LocalId> ContractRef<T> decodeReference(byte[] input, Function<String, T> idFactory) {
        return decodeReference(CanonicalCodec.decode(input), idFactory);
    }

    public static <T extends LocalId> ContractRef<T> decodeReference(String input, Function<String, T> idFactory) {
        return decodeReference(CanonicalCodec.decode(input), idFactory);
    }

    public static <T extends LocalId> CanonicalCodec<ContractRef<T>> referenceCodec(Function<String, T> idFactory) {
        Objects.requireNonNull(idFactory, "Local ID factory is required");
        return new CanonicalCodec<>() {
            @Override
            public JsonValue encode(ContractRef<T> value) {
                return encodeReference(value);
            }

            @Override
            public ContractRef<T> decode(JsonValue value) {
                return decodeReference(value, idFactory);
            }
        };
    }

    public static ResourceKey decodeResourceKey(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Resource key");
        ContractRef<ResourceTypeId> type = decodeReference(object.value("type"), ResourceTypeId::new);
        return new ResourceKey(type, string(object, "id"), unknown(object, "type", "id"));
    }

    public static ResourceKey decodeResourceKey(byte[] input) {
        return decodeResourceKey(CanonicalCodec.decode(input));
    }

    public static ResourceKey decodeResourceKey(String input) {
        return decodeResourceKey(CanonicalCodec.decode(input));
    }

    public static JsonValue.JsonObject encodeResourceKey(ResourceKey key) {
        Objects.requireNonNull(key, "Resource key is required");
        return object(key.unknown(), Map.of(
                "type", encodeReference(key.type()),
                "id", key.id()));
    }

    public static JsonValue.JsonObject encode(ResourceKey key) {
        return encodeResourceKey(key);
    }

    public static CanonicalCodec<ResourceKey> resourceKeyCodec() {
        return new CanonicalCodec<>() {
            @Override
            public JsonValue encode(ResourceKey value) {
                return encodeResourceKey(value);
            }

            @Override
            public ResourceKey decode(JsonValue value) {
                return decodeResourceKey(value);
            }
        };
    }

    public static ServerResourceLocator decodeLocator(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Server resource locator");
        ServerId server = ServerId.parseCanonicalText(string(object, "serverId"));
        ContractRef<ResourceTypeId> type = decodeReference(object.value("type"), ResourceTypeId::new);
        String id = string(object, "id");
        Map<String, Object> unknown = unknown(object, "serverId", "type", "id", "key");
        if (object.contains("key")) {
            ResourceKey key = decodeResourceKey(object.value("key"));
            if (!key.type().equals(type) || !key.id().equals(id)) {
                throw new IllegalArgumentException("Locator key must match its server, type, and ID fields");
            }
            return new ServerResourceLocator(server, key, unknown);
        }
        return new ServerResourceLocator(server, type, id, unknown);
    }

    public static ServerResourceLocator decodeLocator(byte[] input) {
        return decodeLocator(CanonicalCodec.decode(input));
    }

    public static ServerResourceLocator decodeLocator(String input) {
        return decodeLocator(CanonicalCodec.decode(input));
    }

    public static JsonValue.JsonObject encodeLocator(ServerResourceLocator locator) {
        Objects.requireNonNull(locator, "Server resource locator is required");
        return objectValue(locator.canonicalValue(), "Server resource locator");
    }

    public static JsonValue.JsonObject encode(ServerResourceLocator locator) {
        return encodeLocator(locator);
    }

    public static CanonicalCodec<ServerResourceLocator> locatorCodec() {
        return new CanonicalCodec<>() {
            @Override
            public JsonValue encode(ServerResourceLocator value) {
                return encodeLocator(value);
            }

            @Override
            public ServerResourceLocator decode(JsonValue value) {
                return decodeLocator(value);
            }
        };
    }

    public static JsonValue.JsonObject encodeRevision(Revision revision) {
        Objects.requireNonNull(revision, "Revision is required");
        return object(revision.unknown(), Map.of("revision", revision.value()));
    }

    public static JsonValue.JsonObject encode(Revision revision) {
        return encodeRevision(revision);
    }

    public static Revision decodeRevision(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Revision");
        JsonValue raw = object.value("revision");
        if (!(raw instanceof JsonValue.JsonNumber number)) {
            throw new IllegalArgumentException("Revision must be an integer");
        }
        BigDecimal numericValue = number.value();
        if (numericValue.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("Revision must be an integer");
        }
        if (numericValue.signum() < 0 || numericValue.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("Revision must be an integer from 0 through Long.MAX_VALUE");
        }
        try {
            return new Revision(numericValue.longValueExact(), unknown(object, "revision"));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Revision must be an integer from 0 through Long.MAX_VALUE", exception);
        }
    }

    public static Revision decodeRevision(byte[] input) {
        return decodeRevision(CanonicalCodec.decode(input));
    }

    public static Revision decodeRevision(String input) {
        return decodeRevision(CanonicalCodec.decode(input));
    }

    public static CanonicalCodec<Revision> revisionCodec() {
        return new CanonicalCodec<>() {
            @Override
            public JsonValue encode(Revision value) {
                return encodeRevision(value);
            }

            @Override
            public Revision decode(JsonValue value) {
                return decodeRevision(value);
            }
        };
    }

    public static JsonValue decode(byte[] input) {
        return CanonicalCodec.decode(input);
    }

    public static JsonValue decode(String input) {
        return CanonicalCodec.decode(input);
    }

    public static byte[] encodeBytes(ContractRef<?> reference) {
        return encodeReferenceUnchecked(reference).canonicalBytes();
    }

    public static byte[] encodeBytes(ResourceKey key) {
        return encodeResourceKey(key).canonicalBytes();
    }

    public static byte[] encodeBytes(ServerResourceLocator locator) {
        return encodeLocator(locator).canonicalBytes();
    }

    public static byte[] encodeBytes(Revision revision) {
        return encodeRevision(revision).canonicalBytes();
    }

    public static UUID interactiveUuid() {
        return UUID.randomUUID();
    }

    public static UUID deterministicUuid(String domain, String name) {
        return deterministicUuid(MIGRATION_NAMESPACE, domain, name);
    }

    public static UUID deterministicUuid(UUID namespace, String domain, String name) {
        return UuidIdentity.deterministic(namespace, domain, name);
    }

    private static JsonValue.JsonObject encodeReferenceUnchecked(ContractRef<?> reference) {
        Objects.requireNonNull(reference, "Contract reference is required");
        return object(reference.unknown(), Map.of(
                "ownerId", reference.owner().canonicalText(),
                "localId", reference.id().canonicalText()));
    }

    private static JsonValue.JsonObject object(Map<String, ?> unknown, Map<String, ?> known) {
        LinkedHashMap<String, JsonValue> values = new LinkedHashMap<>();
        if (unknown != null) {
            for (Map.Entry<String, ?> entry : unknown.entrySet()) {
                if (known.containsKey(entry.getKey())) {
                    throw new IllegalArgumentException("Unknown data collides with known field: " + entry.getKey());
                }
                values.put(entry.getKey(), toJson(entry.getValue()));
            }
        }
        for (Map.Entry<String, ?> entry : known.entrySet()) {
            if (values.containsKey(entry.getKey())) {
                throw new IllegalArgumentException("Known data contains duplicate field: " + entry.getKey());
            }
            values.put(entry.getKey(), toJson(entry.getValue()));
        }
        return JsonValue.object(values);
    }

    private static JsonValue toJson(Object value) {
        if (value instanceof ContractRef<?> reference) {
            return encodeReferenceUnchecked(reference);
        }
        if (value instanceof ResourceKey key) {
            return encodeResourceKey(key);
        }
        if (value instanceof ServerResourceLocator locator) {
            return encodeLocator(locator);
        }
        if (value instanceof Revision revision) {
            return encodeRevision(revision);
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, JsonValue> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Identity object keys must be strings");
                }
                if (result.put(key, toJson(entry.getValue())) != null) {
                    throw new IllegalArgumentException("Identity object contains duplicate field: " + key);
                }
            }
            return JsonValue.object(result);
        }
        if (value instanceof Iterable<?> iterable) {
            List<JsonValue> result = new ArrayList<>();
            for (Object entry : iterable) {
                result.add(toJson(entry));
            }
            return JsonValue.array(result);
        }
        return JsonValue.fromJava(value);
    }

    private static JsonValue.JsonObject object(JsonValue value, String name) {
        if (!(value instanceof JsonValue.JsonObject object)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return object;
    }

    private static JsonValue.JsonObject objectValue(Object value, String name) {
        return object(toJson(value), name);
    }

    private static String string(JsonValue.JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (!(value instanceof JsonValue.JsonString string)) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return string.value();
    }

    private static Map<String, Object> unknown(JsonValue.JsonObject object, String... known) {
        Map<String, Boolean> names = new HashMap<>();
        for (String name : known) {
            names.put(name, Boolean.TRUE);
        }
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : object.fields().entrySet()) {
            if (!names.containsKey(entry.getKey())) {
                values.put(entry.getKey(), entry.getValue().toJava());
            }
        }
        return IdentitySupport.unknown(values, "identity unknown data");
    }
}
