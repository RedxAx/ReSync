package restudio.resync.flow.identity;

import java.util.UUID;

public record NodeInstanceId(UUID value) implements UuidIdentity, Comparable<NodeInstanceId> {
    public NodeInstanceId {
        value = IdentityValidation.uuid(value, "Node instance ID");
    }

    public static NodeInstanceId of(UUID value) {
        return new NodeInstanceId(value);
    }

    public static NodeInstanceId random() {
        return new NodeInstanceId(UuidIdentity.interactive());
    }

    public static NodeInstanceId interactive() {
        return random();
    }

    public static NodeInstanceId deterministic(String name) {
        return deterministic(UuidIdentity.MIGRATION_NAMESPACE, name);
    }

    public static NodeInstanceId deterministic(UUID namespace, String name) {
        return new NodeInstanceId(UuidIdentity.deterministic(namespace, "node-instance", name));
    }

    public static NodeInstanceId deterministic(UUID namespace, String domain, String name) {
        return new NodeInstanceId(UuidIdentity.deterministic(namespace, domain, name));
    }

    public static NodeInstanceId parseCanonicalText(String value) {
        return new NodeInstanceId(IdentityValidation.uuid(value, "Node instance ID"));
    }

    @Override
    public int compareTo(NodeInstanceId other) {
        return CanonicalText.compare(canonicalText(), other.canonicalText());
    }

    @Override
    public String toString() {
        return canonicalText();
    }
}
