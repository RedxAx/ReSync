package restudio.resync.flow.handler.generic;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;

public class FlowControlHandler implements NodeHandler {
    private static final Set<String> SUPPORTED_OPERATIONS = Set.of(
        "if", "switch_case", "branch_random", "branch_all", "break_loop", "continue_loop",
        "loop", "loop_count", "loop_for_each", "loop_for_each_player", "loop_for_each_entity", "loop_interval", "loop_while"
    );
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public FlowControlHandler() {
        operations.put("if", (ctx, node) -> {
            Boolean condition = ctx.getInputValue(node, "condition", Boolean.class, false);
            ctx.triggerOutput(Boolean.TRUE.equals(condition) ? "true" : "false");
        });

        operations.put("switch_case", (ctx, node) -> {
            Object value = ctx.getInputValue(node, "value", Object.class, null);
            List<?> cases = ctx.getInputValue(node, "cases", List.class, List.of());
            int matchedIndex = -1;
            for (int i = 0; i < cases.size(); i++) {
                if (caseMatches(cases.get(i), value)) {
                    matchedIndex = i;
                    break;
                }
            }
            ctx.setOutput(node, "matched", matchedIndex >= 0);
            ctx.setOutput(node, "index", matchedIndex);
            ctx.triggerOutput("flow");
        });

        operations.put("branch_random", (ctx, node) -> {
            int branches = ctx.getInputValue(node, "branches", Integer.class, 2);
            int selected = ThreadLocalRandom.current().nextInt(Math.clamp(branches, 1, 4));
            ctx.setOutput(node, "selected", selected);
            ctx.triggerOutput("branch_" + selected);
        });

        operations.put("branch_all", (ctx, node) -> {
            ctx.triggerOutput("branch_0");
            ctx.triggerOutput("branch_1");
            ctx.triggerOutput("branch_2");
            ctx.triggerOutput("branch_3");
        });

        operations.put("break_loop", (ctx, node) -> {
            if (!ctx.getRuntime().requestLoopBreak()) {
                throw new IllegalStateException("Break can only run inside a loop");
            }
            ctx.haltContinuation();
        });

        operations.put("continue_loop", (ctx, node) -> {
            if (!ctx.getRuntime().requestLoopContinue()) {
                throw new IllegalStateException("Continue can only run inside a loop");
            }
            ctx.haltContinuation();
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("FlowControlHandler", this);
    }

    private static boolean caseMatches(Object caseValue, Object value) {
        if (caseValue instanceof Number caseNumber && value instanceof Number valueNumber) {
            try {
                return new BigDecimal(caseNumber.toString()).compareTo(new BigDecimal(valueNumber.toString())) == 0;
            } catch (NumberFormatException exception) {
                return Double.compare(caseNumber.doubleValue(), valueNumber.doubleValue()) == 0;
            }
        }
        return Objects.equals(caseValue, value);
    }

    @Override
    public Set<String> getSupportedOperations() {
        return SUPPORTED_OPERATIONS;
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op != null) {
            op.accept(ctx, node);
        } else {
            throw new IllegalArgumentException("Unknown flow control operation: " + operation);
        }
    }
}
