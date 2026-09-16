package restudio.resync.flow.identity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public record ContractRef<T extends LocalId>(OwnerId owner, T id, Map<String, Object> unknown) implements Comparable<ContractRef<?>> {
    public ContractRef {
        owner = Objects.requireNonNull(owner, "Owner ID is required");
        id = Objects.requireNonNull(id, "Local ID is required");
        unknown = IdentitySupport.unknown(unknown, "contract reference unknown data");
    }

    public ContractRef(OwnerId owner, T id) {
        this(owner, id, Map.of());
    }

    public static <T extends LocalId> ContractRef<T> of(OwnerId owner, T id) {
        return new ContractRef<>(owner, id);
    }

    public static <T extends LocalId> ContractRef<T> of(OwnerId owner, T id, Map<String, ?> unknown) {
        return new ContractRef<T>(owner, id, IdentitySupport.unknown(unknown, "contract reference unknown data"));
    }

    public Map<String, Object> canonicalValue() {
        var known = new LinkedHashMap<String, Object>();
        known.put("localId", id.canonicalText());
        known.put("ownerId", owner.canonicalText());
        return IdentitySupport.merge(unknown, known);
    }

    public String canonicalText() {
        return owner.canonicalText() + "/" + id.canonicalText();
    }

    public String ownerId() {
        return owner.value();
    }

    public String localId() {
        return id.value();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ContractRef<?> reference
            && owner.equals(reference.owner)
            && id.equals(reference.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, id);
    }

    public static <T extends LocalId> ContractRef<T> parseCanonicalText(String value, Function<String, T> idFactory) {
        Objects.requireNonNull(value, "Reference text is required");
        Objects.requireNonNull(idFactory, "Local ID factory is required");
        String[] parts = value.split("/", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Reference text must contain one namespace separator");
        }
        ContractRef<T> reference = new ContractRef<>(new OwnerId(parts[0]), idFactory.apply(parts[1]));
        if (!reference.canonicalText().equals(value)) {
            throw new IllegalArgumentException("Reference text is not canonical");
        }
        return reference;
    }

    @Override
    public int compareTo(ContractRef<?> other) {
        int ownerOrder = owner.compareTo(other.owner);
        return ownerOrder != 0 ? ownerOrder : CanonicalText.compare(id.value(), other.id.value());
    }

    @Override
    public String toString() {
        return canonicalText();
    }
}
