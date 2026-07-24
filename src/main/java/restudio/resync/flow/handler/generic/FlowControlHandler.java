package restudio.resync.flow.handler.generic;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.registry.NodeDefinition;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
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
            boolean branching = ctx.getInputValue(node, "branch", Boolean.class, false);
            List<?> cases = branching ? switchCases(ctx, node) : ctx.getInputValue(node, "cases", List.class, List.of());
            int matchedIndex = -1;
            for (int i = 0; i < cases.size(); i++) {
                if (caseMatches(cases.get(i), value)) {
                    matchedIndex = i;
                    break;
                }
            }
            ctx.setOutput(node, "matched", matchedIndex >= 0);
            ctx.setOutput(node, "index", matchedIndex);
            if (!branching) {
                ctx.triggerOutput("flow");
                return;
            }
            ctx.triggerOutput(matchedIndex >= 0 ? switchCaseOutput(ctx, node, matchedIndex) : "default");
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

    private static List<Object> switchCases(FlowContext ctx, FlowNode node) {
        NodeDefinition definition = ctx.getRuntime().getDefinition(node);
        NodeDefinition.PinDefinition base = definition != null ? definition.getInputs().stream()
            .filter(pin -> "case".equals(pin.getName()))
            .findFirst()
            .orElse(null) : null;
        NodeDefinition.RepeatablePin repeatable = base != null ? base.getRepeatable() : null;
        if (repeatable == null) {
            List<Object> single = new ArrayList<>();
            single.add(ctx.getInputValue(node, "case", Object.class, null));
            return single;
        }
        int count = repeatable.getMinItems();
        Object storedCount = node.getInputValues() != null ? node.getInputValues().get("__repeatable_count:" + repeatable.getGroupId()) : null;
        if (storedCount instanceof Number number) {
            count = number.intValue();
        } else if (storedCount != null) {
            try {
                count = Integer.parseInt(storedCount.toString());
            } catch (NumberFormatException ignored) {
                count = repeatable.getMinItems();
            }
        }
        count = Math.clamp(count, repeatable.getMinItems(), repeatable.getMaxItems());
        Set<String> removed = new HashSet<>();
        if (node.getInputValues() != null && node.getInputValues().get("__removed_optional_inputs") instanceof Iterable<?> names) {
            for (Object name : names) {
                if (name != null) {
                    removed.add(name.toString());
                }
            }
        }
        List<Object> cases = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            String pinName = index == 1 ? "case" : "case_" + index;
            if (!removed.contains(pinName)) {
                cases.add(ctx.getInputValue(node, pinName, Object.class, null));
            }
        }
        return cases;
    }

    private static String switchCaseOutput(FlowContext ctx, FlowNode node, int matchedIndex) {
        NodeDefinition definition = ctx.getRuntime().getDefinition(node);
        NodeDefinition.PinDefinition base = definition != null ? definition.getInputs().stream()
            .filter(pin -> "case".equals(pin.getName()))
            .findFirst()
            .orElse(null) : null;
        NodeDefinition.RepeatablePin repeatable = base != null ? base.getRepeatable() : null;
        int count = repeatable != null ? repeatable.getMinItems() : matchedIndex + 1;
        Object storedCount = repeatable != null && node.getInputValues() != null ? node.getInputValues().get("__repeatable_count:" + repeatable.getGroupId()) : null;
        if (storedCount instanceof Number number) {
            count = number.intValue();
        } else if (storedCount != null) {
            try {
                count = Integer.parseInt(storedCount.toString());
            } catch (NumberFormatException ignored) {
                count = repeatable != null ? repeatable.getMinItems() : matchedIndex + 1;
            }
        }
        Set<String> removed = new HashSet<>();
        if (node.getInputValues() != null && node.getInputValues().get("__removed_optional_inputs") instanceof Iterable<?> names) {
            for (Object name : names) {
                if (name != null) {
                    removed.add(name.toString());
                }
            }
        }
        int activeIndex = -1;
        for (int index = 1; index <= count; index++) {
            String pinName = index == 1 ? "case" : "case_" + index;
            if (!removed.contains(pinName) && ++activeIndex == matchedIndex) {
                return pinName;
            }
        }
        return "default";
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
