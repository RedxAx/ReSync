package restudio.resync.flow.diagnostic;

public enum DiagnosticMetricPolicy {
    NONE("none"),
    COUNT("count"),
    SAMPLED("sampled"),
    TIMED("timed");

    private final String wireName;

    DiagnosticMetricPolicy(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
