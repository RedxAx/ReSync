package restudio.resync.modules.flow;

public record FlowResourceMutationContext(String source, String flowId, String nodeId, String actor) {
    public FlowResourceMutationContext {
        source = source != null && !source.isBlank() ? source : "system";
        flowId = flowId != null ? flowId : "";
        nodeId = nodeId != null ? nodeId : "";
        actor = actor != null && !actor.isBlank() ? actor : "server";
    }

    public static FlowResourceMutationContext system() {
        return new FlowResourceMutationContext("system", "", "", "server");
    }
}
