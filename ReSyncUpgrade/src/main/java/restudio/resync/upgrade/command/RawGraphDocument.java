package restudio.resync.upgrade.command;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.contract.canonical.JsonValue;

import java.util.Objects;

public final class RawGraphDocument {
    private static final String HASH_DOMAIN = "resync.offline-upgrader.graph";

    private final JsonValue.JsonObject root;

    private RawGraphDocument(JsonValue.JsonObject root) {
        this.root = Objects.requireNonNull(root, "Graph root is required");
    }

    public static RawGraphDocument parse(String input) {
        return of(CanonicalCodec.decodePermissive(Objects.requireNonNull(input, "Graph input is required")));
    }

    public static RawGraphDocument parse(byte[] input) {
        return of(CanonicalCodec.decodePermissive(Objects.requireNonNull(input, "Graph input is required")));
    }

    public static RawGraphDocument of(JsonValue value) {
        if (!(Objects.requireNonNull(value, "Graph value is required") instanceof JsonValue.JsonObject object)) {
            throw new IllegalArgumentException("COMMAND_GRAPH_OBJECT_REQUIRED: Graph root must be a JSON object");
        }
        return new RawGraphDocument(object);
    }

    public JsonValue.JsonObject root() {
        return root;
    }

    public String canonicalText() {
        return root.canonicalText();
    }

    public byte[] canonicalBytes() {
        return root.canonicalBytes();
    }

    public String contentHash() {
        return CanonicalHash.sha256(HASH_DOMAIN, root);
    }

    public RawGraphDocument withRoot(JsonValue.JsonObject replacement) {
        return new RawGraphDocument(replacement);
    }
}
