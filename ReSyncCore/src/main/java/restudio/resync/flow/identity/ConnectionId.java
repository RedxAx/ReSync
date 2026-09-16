package restudio.resync.flow.identity;

import java.util.UUID;

public record ConnectionId(UUID value) implements UuidIdentity, Comparable<ConnectionId> {
    public ConnectionId {
        value = IdentityValidation.uuid(value, "Connection ID");
    }

    public static ConnectionId of(UUID value) {
        return new ConnectionId(value);
    }

    public static ConnectionId random() {
        return new ConnectionId(UuidIdentity.interactive());
    }

    public static ConnectionId interactive() {
        return random();
    }

    public static ConnectionId deterministic(String name) {
        return deterministic(UuidIdentity.MIGRATION_NAMESPACE, name);
    }

    public static ConnectionId deterministic(UUID namespace, String name) {
        return new ConnectionId(UuidIdentity.deterministic(namespace, "connection", name));
    }

    public static ConnectionId deterministic(UUID namespace, String domain, String name) {
        return new ConnectionId(UuidIdentity.deterministic(namespace, domain, name));
    }

    public static ConnectionId parseCanonicalText(String value) {
        return new ConnectionId(IdentityValidation.uuid(value, "Connection ID"));
    }

    @Override
    public int compareTo(ConnectionId other) {
        return CanonicalText.compare(canonicalText(), other.canonicalText());
    }

    @Override
    public String toString() {
        return canonicalText();
    }
}
