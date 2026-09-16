package restudio.resync.flow.inspector;

public enum InspectorValidationPhase {
    SYNTACTIC("syntactic"),
    SEMANTIC("semantic"),
    CAPABILITY("capability"),
    ENVIRONMENT("environment");

    private final String wireName;

    InspectorValidationPhase(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
