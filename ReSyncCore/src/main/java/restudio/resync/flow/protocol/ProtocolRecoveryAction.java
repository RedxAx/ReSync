package restudio.resync.flow.protocol;

public enum ProtocolRecoveryAction {
    NONE("none"),
    FIX_REQUEST("fix_request"),
    AUTHENTICATE("authenticate"),
    AUTHORIZE("authorize"),
    REFRESH_CAPABILITIES("refresh_capabilities"),
    REFRESH_RESOURCE("refresh_resource"),
    RETRY_LATER("retry_later"),
    CONTACT_SERVER("contact_server");

    private final String wireValue;

    ProtocolRecoveryAction(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
