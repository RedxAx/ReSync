package restudio.resync.flow.handler.generic;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.FlowHandlerException;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.Set;

public final class CustomFunctionCallHandler implements NodeHandler {
    public static final String HANDLER_ID = "CustomFunctionCallHandler";
    public static final String OPERATION = "custom_function_call";

    public void registerTo(HandlerRegistry registry) {
        registry.register(HANDLER_ID, this);
    }

    @Override
    public void execute(FlowContext context, FlowNode node) {
        throw new FlowHandlerException("FUNCTION_DISPATCH_INVALID", "Custom function call bypassed executor dispatch",
            "Reload the Flow runtime and retry the function call");
    }

    @Override
    public Set<String> getSupportedOperations() {
        return Set.of(OPERATION);
    }
}
