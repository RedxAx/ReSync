package restudio.resync.flow.type;

import restudio.resync.contract.canonical.CanonicalText;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record TypeReference(String ownerId, String localId, Map<String, Object> unknown) {
    private static final Pattern OWNER_PATTERN = Pattern.compile("^[a-z][a-z0-9]{0,31}(?:[.-][a-z][a-z0-9]{0,31})*$");
    private static final Pattern LOCAL_PATTERN = Pattern.compile("^[a-z][a-z0-9]{0,31}(?:[._-][a-z0-9][a-z0-9]{0,31})*$");

    public TypeReference {
        ownerId = requireOwnerId(ownerId);
        localId = requireLocalId(localId);
        unknown = TypeSupport.unknown(unknown, "type reference unknown data");
    }

    public TypeReference(String ownerId, String localId) {
        this(ownerId, localId, Map.of());
    }

    public static TypeReference of(String ownerId, String localId) {
        return new TypeReference(ownerId, localId);
    }

    public String canonicalKey() {
        return ownerId + '\u0000' + localId;
    }

    public Map<String, Object> canonicalValue() {
        var known = new LinkedHashMap<String, Object>();
        known.put("localId", localId);
        known.put("ownerId", ownerId);
        return TypeSupport.merge(unknown, known);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof TypeReference reference
            && ownerId.equals(reference.ownerId)
            && localId.equals(reference.localId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ownerId, localId);
    }

    public static String requireOwnerId(String value) {
        return requireIdentifier(value, OWNER_PATTERN, "ownerId");
    }

    public static String requireLocalId(String value) {
        return requireIdentifier(value, LOCAL_PATTERN, "localId");
    }

    private static String requireIdentifier(String value, Pattern pattern, String name) {
        Objects.requireNonNull(value, name);
        if (value.length() > 128 || !CanonicalText.isNfc(value) || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value);
        }
        return value;
    }
}
