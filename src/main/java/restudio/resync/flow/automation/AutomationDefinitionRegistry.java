package restudio.resync.flow.automation;

import com.google.gson.JsonObject;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.util.List;

public final class AutomationDefinitionRegistry {
    private final ReSyncJsonResourceStorage storage;

    public AutomationDefinitionRegistry(ReSyncJsonResourceStorage storage) {
        this.storage = storage;
    }

    public VariableDefinition variable(String id) {
        return VariableDefinition.from(require(ReSyncResourceCatalog.VARIABLE_DEFINITION, id), id);
    }

    public TimerDefinition timer(String id) {
        return TimerDefinition.from(require(ReSyncResourceCatalog.TIMER_DEFINITION, id), id);
    }

    public ScheduleDefinition schedule(String id) {
        return ScheduleDefinition.from(require(ReSyncResourceCatalog.SCHEDULE_DEFINITION, id), id);
    }

    public List<VariableDefinition> variables() {
        return storage.listIds(ReSyncResourceCatalog.VARIABLE_DEFINITION).stream().map(this::variable).toList();
    }

    public List<TimerDefinition> timers() {
        return storage.listIds(ReSyncResourceCatalog.TIMER_DEFINITION).stream().map(this::timer).toList();
    }

    public List<ScheduleDefinition> schedules() {
        return storage.listIds(ReSyncResourceCatalog.SCHEDULE_DEFINITION).stream().map(this::schedule).toList();
    }

    public FlowResourceReference reference(AutomationDefinition definition) {
        String kind = switch (definition) {
            case VariableDefinition ignored -> ReSyncResourceCatalog.VARIABLE_DEFINITION;
            case TimerDefinition ignored -> ReSyncResourceCatalog.TIMER_DEFINITION;
            case ScheduleDefinition ignored -> ReSyncResourceCatalog.SCHEDULE_DEFINITION;
            default -> throw new IllegalArgumentException("Unsupported automation definition: " + definition.getClass().getName());
        };
        return new FlowResourceReference(kind, definition.id(), "server", true, java.util.Map.of(
            "name", definition.name(),
            "scope", definition.scope().name().toLowerCase(),
            "persistent", definition.persistent()
        ));
    }

    private JsonObject require(String type, String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Automation definition is required");
        }
        JsonObject value = storage.get(type, id);
        if (value == null) {
            throw new IllegalArgumentException("Automation definition not found: " + id);
        }
        return value;
    }
}
