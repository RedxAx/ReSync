package restudio.resync.flow.diagnostic;

public enum DiagnosticPhase {
    SYNTACTIC("syntactic"),
    SEMANTIC("semantic"),
    CAPABILITY("capability"),
    ENVIRONMENT("environment");

    private final String wireName;

    DiagnosticPhase(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
