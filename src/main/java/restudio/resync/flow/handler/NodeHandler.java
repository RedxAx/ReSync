package restudio.resync.flow.handler;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;

import java.util.Set;

public interface NodeHandler {
    enum ThreadPolicy {
        MAIN,
        ASYNC,
        CURRENT
    }

    void execute(FlowContext ctx, FlowNode node);

    default ThreadPolicy getThreadPolicy() {
        return ThreadPolicy.MAIN;
    }

    default Set<String> getSupportedOperations() {
        return Set.of();
    }

    default void initialize(HandlerConfig config) {}

    default void shutdown() {}
}
