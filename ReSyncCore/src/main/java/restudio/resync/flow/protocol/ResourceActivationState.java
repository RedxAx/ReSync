package restudio.resync.flow.protocol;

public enum ResourceActivationState {
    ACTIVE("active"),
    INACTIVE("inactive");

    private final String wireName;

    ResourceActivationState(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static ResourceActivationState fromWireName(String value) {
        for (ResourceActivationState state : values()) {
            if (state.wireName.equals(value)) {
                return state;
            }
        }
        throw new IllegalArgumentException("Unknown resource activation state: " + value);
    }
}
