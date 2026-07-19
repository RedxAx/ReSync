package restudio.resync.flow;

import java.util.List;

public class FlowFunctionInUseException extends IllegalStateException {
    private final String functionId;
    private final List<FlowFunctionReference> references;

    public FlowFunctionInUseException(String functionId, List<FlowFunctionReference> references) {
        super("Function " + functionId + " is used by " + references.size() + " caller references");
        this.functionId = functionId;
        this.references = List.copyOf(references);
    }

    public String getFunctionId() {
        return functionId;
    }

    public List<FlowFunctionReference> getReferences() {
        return references;
    }
}
