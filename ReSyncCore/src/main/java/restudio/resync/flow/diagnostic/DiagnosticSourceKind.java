package restudio.resync.flow.diagnostic;

public enum DiagnosticSourceKind {
    BUNDLED("bundled"),
    LOCAL("local"),
    FUNCTION("function"),
    EXTENSION("extension");

    private final String wireName;

    DiagnosticSourceKind(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
