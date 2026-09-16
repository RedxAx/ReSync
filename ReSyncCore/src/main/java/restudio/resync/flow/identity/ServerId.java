package restudio.resync.flow.identity;

import java.util.UUID;

public record ServerId(UUID value) implements Comparable<ServerId> {
    public ServerId {
        value = IdentityValidation.uuid(value, "Server ID");
    }

    public static ServerId of(UUID value) {
        return new ServerId(value);
    }

    public static ServerId random() {
        return new ServerId(UuidIdentity.interactive());
    }

    public static ServerId interactive() {
        return random();
    }

    public static ServerId deterministic(String name) {
        return deterministic(UuidIdentity.MIGRATION_NAMESPACE, name);
    }

    public static ServerId deterministic(UUID namespace, String name) {
        return new ServerId(UuidIdentity.deterministic(namespace, "server", name));
    }

    public static ServerId deterministic(UUID namespace, String domain, String name) {
        return new ServerId(UuidIdentity.deterministic(namespace, domain, name));
    }

    public static ServerId parseCanonicalText(String value) {
        return new ServerId(IdentityValidation.uuid(value, "Server ID"));
    }

    public String canonicalText() {
        return value.toString();
    }

    @Override
    public int compareTo(ServerId other) {
        return CanonicalText.compare(canonicalText(), other.canonicalText());
    }

    @Override
    public String toString() {
        return canonicalText();
    }
}
