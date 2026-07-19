package restudio.resync.flow.validation;

public class FlowGraphValidationException extends IllegalArgumentException {
    private final FlowGraphValidationResult result;

    public FlowGraphValidationException(FlowGraphValidationResult result) {
        super(result != null ? result.summary() : "Flow graph validation failed");
        this.result = result != null ? result : new FlowGraphValidationResult(null);
    }

    public FlowGraphValidationResult getResult() {
        return result;
    }
}
