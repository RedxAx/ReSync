package restudio.resync.flow.inspector;

public enum InspectorFallback {
    GENERIC("generic"),
    READ_ONLY_FIELD("read-only-field"),
    READ_ONLY_NODE("read-only-node"),
    READ_ONLY_GRAPH("read-only-graph"),
    REJECT("reject");

    private final String wireName;

    InspectorFallback(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public String stateWireName() {
        if (this == REJECT) {
            throw new IllegalArgumentException("Reject is not a valid persisted inspector state fallback");
        }
        return this == GENERIC ? "editable" : wireName;
    }
}
