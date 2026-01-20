package restudio.resync.flow;

import restudio.flow.data.FlowNode;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

public class FlowRegistry {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> executors;

    public FlowRegistry() {
        this.executors = new HashMap<>();
    }

    public void register(String type, BiConsumer<FlowContext, FlowNode> executor) {
        executors.put(type, executor);
    }

    public BiConsumer<FlowContext, FlowNode> getExecutor(String type) {
        return executors.get(type);
    }
}
