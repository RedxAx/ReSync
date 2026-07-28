package restudio.resync.flow.handler.generic;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.automation.AutomationDefinitionRegistry;
import restudio.resync.flow.automation.AutomationInstanceKey;
import restudio.resync.flow.automation.AutomationOwner;
import restudio.resync.flow.automation.AutomationReferences;
import restudio.resync.flow.automation.AutomationTaskService;
import restudio.resync.flow.automation.TimerDefinition;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class TimerHandler implements NodeHandler {
    public static final String HANDLER_ID = "TimerHandler";
    private static final Set<String> ACTIONS = Set.of("start", "stop", "pause", "resume", "check");
    private final AutomationDefinitionRegistry definitions;
    private final AutomationTaskService tasks;

    public TimerHandler(AutomationDefinitionRegistry definitions, AutomationTaskService tasks) {
        this.definitions = definitions;
        this.tasks = tasks;
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register(HANDLER_ID, this);
    }

    @Override
    public void execute(FlowContext context, FlowNode node) {
        String definitionId = AutomationReferences.id(context.getInputValue(node, "timer", Object.class, null));
        TimerDefinition definition = definitions.timer(definitionId);
        String action = context.getInputValue(node, "action", String.class, "check").trim().toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            throw new IllegalArgumentException("Unknown Timer action: " + action);
        }
        Object ownerValue = ownerValue(context, node, definition);
        AutomationOwner owner = AutomationOwner.resolve(definition.scope(), context, ownerValue);
        AutomationInstanceKey key = new AutomationInstanceKey(definition.id(), definition.scope(), owner.id());
        AutomationTaskService.TaskSnapshot snapshot = switch (action) {
            case "start" -> start(context, node, definition, owner);
            case "stop" -> tasks.cancel(key);
            case "pause" -> tasks.pause(key);
            case "resume" -> tasks.resume(key);
            default -> tasks.check(key);
        };
        Map<String, Object> value = snapshot.value();
        context.setOutput(node, "timer", definitions.reference(definition));
        context.setOutput(node, "state", value.get("state"));
        context.setOutput(node, "remaining", value.get("remaining"));
        context.setOutput(node, "elapsed", value.get("elapsed"));
        context.setOutput(node, "duration", value.get("duration"));
        context.setOutput(node, "progress", value.get("progress"));
        context.setOutput(node, "progress_percent", value.get("progressPercent"));
        context.triggerOutput(switch (snapshot.state()) {
            case ACTIVE -> "active";
            case PAUSED -> "paused";
            default -> "inactive";
        });
    }

    @Override
    public Set<String> getSupportedOperations() {
        return Set.of("timer");
    }

    private AutomationTaskService.TaskSnapshot start(FlowContext context, FlowNode node, TimerDefinition definition, AutomationOwner owner) {
        double duration = context.getInputValue(node, "duration", Double.class, definition.defaultDuration());
        String unitValue = context.getInputValue(node, "unit", String.class, definition.defaultUnit().name());
        TimerDefinition.TimeUnit unit = TimerDefinition.TimeUnit.parse(unitValue);
        long durationMillis = unit.toMillis(duration);
        long tickInterval = definition.tickInterval() > 0D ? definition.defaultUnit().toMillis(definition.tickInterval()) : 0L;
        return tasks.startTimer(definition, owner, durationMillis, tickInterval);
    }

    private Object ownerValue(FlowContext context, FlowNode node, TimerDefinition definition) {
        return switch (definition.scope()) {
            case PLAYER -> context.getInputValue(node, "owner", Object.class,
                context.getInputValue(node, "player", Object.class, context.getPlayer()));
            case ENTITY -> context.getInputValue(node, "owner", Object.class,
                context.getInputValue(node, "entity", Object.class, null));
            case NETWORK -> context.getInputValue(node, "owner", Object.class,
                context.getInputValue(node, "network", Object.class, null));
            default -> null;
        };
    }
}
