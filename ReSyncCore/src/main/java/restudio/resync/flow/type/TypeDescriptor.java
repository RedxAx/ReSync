package restudio.resync.flow.type;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.IdentitySupport;
import restudio.resync.flow.identity.OwnerId;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TypeDescriptor {
    private final TypeReference id;
    private final String displayName;
    private final TypeExpr expression;
    private final Object literalSchema;
    private final CodecDescriptor storageCodec;
    private final CodecDescriptor networkCodec;
    private final ContractRef<CapabilityId> editor;
    private final List<TypeReference> validators;
    private final boolean transportable;
    private final boolean persistable;
    private final Map<String, Object> unknown;

    public TypeDescriptor(TypeReference id, String displayName, TypeExpr expression, CodecDescriptor storageCodec,
                          CodecDescriptor networkCodec, List<TypeReference> validators, boolean transportable, boolean persistable) {
        this(id, displayName, expression, Map.of(), storageCodec, networkCodec,
            ContractRef.of(OwnerId.of(Objects.requireNonNull(id, "id").ownerId()), CapabilityId.of("generic-editor")), validators,
            transportable, persistable, Map.of());
    }

    public TypeDescriptor(TypeReference id, String displayName, TypeExpr expression, Object literalSchema,
                          CodecDescriptor storageCodec, CodecDescriptor networkCodec, ContractRef<CapabilityId> editor,
                          List<TypeReference> validators) {
        this(id, displayName, expression, literalSchema, storageCodec, networkCodec, editor, validators, true, true, Map.of());
    }

    public TypeDescriptor(TypeReference id, String displayName, TypeExpr expression, Object literalSchema,
                          CodecDescriptor storageCodec, CodecDescriptor networkCodec, ContractRef<CapabilityId> editor,
                          List<TypeReference> validators, boolean transportable, boolean persistable) {
        this(id, displayName, expression, literalSchema, storageCodec, networkCodec, editor, validators, transportable,
            persistable, Map.of());
    }

    public TypeDescriptor(TypeReference id, String displayName, TypeExpr expression, Object literalSchema,
                          CodecDescriptor storageCodec, CodecDescriptor networkCodec, ContractRef<CapabilityId> editor,
                          List<TypeReference> validators, boolean transportable, boolean persistable,
                          Map<String, ?> unknown) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = requireText(displayName, "displayName");
        this.expression = Objects.requireNonNull(expression, "expression");
        this.literalSchema = freeze(literalSchema, "literalSchema");
        this.storageCodec = Objects.requireNonNull(storageCodec, "storageCodec");
        this.networkCodec = Objects.requireNonNull(networkCodec, "networkCodec");
        this.editor = Objects.requireNonNull(editor, "editor");
        this.validators = validators == null ? List.of() : List.copyOf(validators);
        this.transportable = transportable;
        this.persistable = persistable;
        this.unknown = IdentitySupport.unknown(unknown, "type descriptor unknown data");
        if (persistable && !storageCodec.deterministic()) {
            throw new IllegalArgumentException("A persistable type needs a deterministic storage codec");
        }
        if (transportable && !networkCodec.deterministic()) {
            throw new IllegalArgumentException("A transportable type needs a deterministic network codec");
        }
    }

    public TypeReference id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public TypeExpr expression() {
        return expression;
    }

    public Object literalSchema() {
        return literalSchema;
    }

    public CodecDescriptor storageCodec() {
        return storageCodec;
    }

    public CodecDescriptor networkCodec() {
        return networkCodec;
    }

    public ContractRef<CapabilityId> editor() {
        return editor;
    }

    public List<TypeReference> validators() {
        return validators;
    }

    public boolean transportable() {
        return transportable;
    }

    public boolean persistable() {
        return persistable;
    }

    public Map<String, Object> unknown() {
        return unknown;
    }

    public String canonicalJson() {
        return CanonicalJson.canonicalize(canonicalValue());
    }

    public Map<String, Object> canonicalValue() {
        var values = new LinkedHashMap<String, Object>();
        values.put("displayName", displayName);
        values.put("editor", Map.of("ownerId", editor.owner().canonicalText(), "localId", editor.id().canonicalText()));
        values.put("expression", expression.canonicalValue());
        values.put("id", id.localId());
        values.put("literalSchema", literalSchema);
        values.put("networkCodec", networkCodec.id().canonicalValue());
        values.put("persistable", persistable);
        values.put("transportable", transportable);
        values.put("storageCodec", storageCodec.id().canonicalValue());
        values.put("validators", validators.stream().map(TypeReference::canonicalValue).toList());
        return IdentitySupport.merge(unknown, values);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof TypeDescriptor other)) {
            return false;
        }
        return transportable == other.transportable && persistable == other.persistable
            && id.equals(other.id) && displayName.equals(other.displayName) && expression.equals(other.expression)
            && Objects.equals(literalSchema, other.literalSchema) && storageCodec.equals(other.storageCodec)
            && networkCodec.equals(other.networkCodec) && editor.equals(other.editor) && validators.equals(other.validators)
            && unknown.equals(other.unknown);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, displayName, expression, literalSchema, storageCodec, networkCodec, editor, validators,
            transportable, persistable, unknown);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return value;
    }

    private static Object freeze(Object value, String name) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            if (value instanceof Float floatValue) {
                if (!Float.isFinite(floatValue)) {
                    throw new IllegalArgumentException(name + " contains a non-finite number");
                }
                return BigDecimal.valueOf(floatValue.doubleValue());
            }
            if (value instanceof Double doubleValue) {
                if (!Double.isFinite(doubleValue)) {
                    throw new IllegalArgumentException(name + " contains a non-finite number");
                }
                return BigDecimal.valueOf(doubleValue);
            }
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(name + " keys must be strings");
                }
                copy.put(key, freeze(entry.getValue(), name + "." + key));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(entry -> freeze(entry, name + "[]")).toList();
        }
        throw new IllegalArgumentException("Unsupported " + name + " value");
    }
}
