package restudio.resync.flow.handler.generic;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.text.ReTextService;

import java.util.Set;

public class TextResourceHandler implements NodeHandler {
    private final ReTextService text;

    public TextResourceHandler(ReTextService text) {
        this.text = text;
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("TextResourceHandler", this);
    }

    @Override
    public Set<String> getSupportedOperations() {
        return Set.of("lines", "entries", "lookup");
    }

    @Override
    public void execute(FlowContext context, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        String resourceId = context.getInputValue(node, "text_id", String.class, "");
        switch (operation == null ? "" : operation) {
            case "lines" -> context.setOutput(node, "lines", text.lines(resourceId));
            case "entries" -> context.setOutput(node, "entries", text.entries(resourceId));
            case "lookup" -> {
                String key = context.getInputValue(node, "key", String.class, "");
                String fallback = context.getInputValue(node, "fallback", String.class, "");
                context.setOutput(node, "value", text.lookup(resourceId, key, fallback));
            }
            default -> throw new IllegalArgumentException("Unknown text resource operation: " + operation);
        }
        context.triggerOutput("flow");
    }
}
