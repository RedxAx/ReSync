package restudio.resync.flow.identity;

public record NodeId(String value) implements LocalId, Comparable<NodeId> {
    public NodeId {
        value = IdentityValidation.local(value, "Node ID");
    }

    public static NodeId of(String value) {
        return new NodeId(value);
    }

    @Override
    public int compareTo(NodeId other) {
        return CanonicalText.compare(value, other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
