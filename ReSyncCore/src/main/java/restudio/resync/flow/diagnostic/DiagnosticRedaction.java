package restudio.resync.flow.diagnostic;

public enum DiagnosticRedaction {
    PUBLIC("public"),
    TECHNICAL("technical"),
    SENSITIVE("sensitive"),
    SECRET("secret");

    private final String wireName;

    DiagnosticRedaction(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
