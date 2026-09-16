package restudio.resync.flow.identity;

import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ResourceKey implements Comparable<ResourceKey> {
    private final ContractRef<ResourceTypeId> type;
    private final String id;
    private final Map<String, Object> unknown;

    public ResourceKey(ContractRef<ResourceTypeId> type, String id) {
        this(type, id, Map.of());
    }

    public ResourceKey(ContractRef<ResourceTypeId> type, String id, Map<String, ?> unknown) {
        this.type = Objects.requireNonNull(type, "Resource type is required");
        this.id = IdentityValidation.resource(id);
        this.unknown = IdentitySupport.unknown(unknown, "resource key unknown data");
    }

    public static ResourceKey of(ContractRef<ResourceTypeId> type, String id) {
        return new ResourceKey(type, id);
    }

    public ContractRef<ResourceTypeId> type() {
        return type;
    }

    public OwnerId owner() {
        return type.owner();
    }

    public ResourceTypeId resourceType() {
        return type.id();
    }

    public String id() {
        return id;
    }

    public Map<String, Object> unknown() {
        return unknown;
    }

    public Map<String, Object> canonicalValue() {
        var known = new LinkedHashMap<String, Object>();
        known.put("id", id);
        known.put("type", type.canonicalValue());
        return IdentitySupport.merge(unknown, known);
    }

    public String canonicalText() {
        return type.canonicalText() + "/" + id;
    }

    public static ResourceKey parseCanonicalText(String value) {
        Objects.requireNonNull(value, "Resource key text is required");
        String[] parts = value.split("/", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Resource key text must contain owner, type, and ID");
        }
        ResourceKey key = new ResourceKey(new ContractRef<>(new OwnerId(parts[0]), new ResourceTypeId(parts[1])), parts[2]);
        if (!key.canonicalText().equals(value)) {
            throw new IllegalArgumentException("Resource key text is not canonical");
        }
        return key;
    }

    @Override
    public int compareTo(ResourceKey other) {
        int typeOrder = type.compareTo(other.type);
        return typeOrder != 0 ? typeOrder : CanonicalText.compare(id, other.id);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof ResourceKey other)) {
            return false;
        }
        return type.equals(other.type) && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, id);
    }

    @Override
    public String toString() {
        return canonicalText();
    }
}
