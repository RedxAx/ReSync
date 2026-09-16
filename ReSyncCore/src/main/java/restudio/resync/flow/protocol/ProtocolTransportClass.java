package restudio.resync.flow.protocol;

public enum ProtocolTransportClass {
    SUCCESS("success"),
    CLIENT_ERROR("client_error"),
    AUTHENTICATION("authentication"),
    AUTHORIZATION("authorization"),
    NOT_FOUND("not_found"),
    CONFLICT("conflict"),
    UNAVAILABLE("unavailable"),
    SERVER_ERROR("server_error");

    private final String wireValue;

    ProtocolTransportClass(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
