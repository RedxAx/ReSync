package restudio.resync.flow.identity;

import java.util.UUID;

public record RepeatableElementId(UUID value) implements UuidIdentity, Comparable<RepeatableElementId> {
    public RepeatableElementId {
        value = IdentityValidation.uuid(value, "Repeatable element ID");
    }

    public static RepeatableElementId of(UUID value) {
        return new RepeatableElementId(value);
    }

    public static RepeatableElementId random() {
        return new RepeatableElementId(UuidIdentity.interactive());
    }

    public static RepeatableElementId interactive() {
        return random();
    }

    public static RepeatableElementId deterministic(String name) {
        return deterministic(UuidIdentity.MIGRATION_NAMESPACE, name);
    }

    public static RepeatableElementId deterministic(UUID namespace, String name) {
        return new RepeatableElementId(UuidIdentity.deterministic(namespace, "repeatable-element", name));
    }

    public static RepeatableElementId deterministic(UUID namespace, String domain, String name) {
        return new RepeatableElementId(UuidIdentity.deterministic(namespace, domain, name));
    }

    public static RepeatableElementId parseCanonicalText(String value) {
        return new RepeatableElementId(IdentityValidation.uuid(value, "Repeatable element ID"));
    }

    @Override
    public int compareTo(RepeatableElementId other) {
        return CanonicalText.compare(canonicalText(), other.canonicalText());
    }

    @Override
    public String toString() {
        return canonicalText();
    }
}
