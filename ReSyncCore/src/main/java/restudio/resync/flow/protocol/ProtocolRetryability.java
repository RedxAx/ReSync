package restudio.resync.flow.protocol;

public enum ProtocolRetryability {
    NEVER("never"),
    SAFE("safe"),
    AFTER_AUTHENTICATION("after_authentication"),
    AFTER_REFRESH("after_refresh"),
    LATER("later");

    private final String wireValue;

    ProtocolRetryability(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
