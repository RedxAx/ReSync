package restudio.resync.flow.automation;

import com.google.gson.JsonObject;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.resources.JsonAssetStore.AssetStamp;
import restudio.resync.storage.AssetTransactionCoordinator.AssetKey;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

public final class AutomationDefinitionRegistry {
    private final ReSyncJsonResourceStorage storage;
    private final Map<AssetKey, Resident> definitions = new ConcurrentHashMap<>();

    public AutomationDefinitionRegistry(ReSyncJsonResourceStorage storage) {
        this.storage = storage;
        if (storage != null) {
            storage.addListener((type, id, value, deleted) -> definitions.remove(new AssetKey(type, id)));
        }
    }

    public VariableDefinition variable(String id) {
        return VariableDefinition.from(require(ReSyncResourceCatalog.VARIABLE_DEFINITION, id), id);
    }

    public TimerDefinition timer(String id) {
        return (TimerDefinition) definition(ReSyncResourceCatalog.TIMER_DEFINITION, id, TimerDefinition::from);
    }

    public ScheduleDefinition schedule(String id) {
        return (ScheduleDefinition) definition(ReSyncResourceCatalog.SCHEDULE_DEFINITION, id, ScheduleDefinition::from);
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
        return new FlowResourceReference(kind, definition.id(), "builtin", true, Map.of(
            "name", definition.name(),
            "scope", definition.scope().name().toLowerCase(),
            "persistent", definition.persistent()
        ));
    }

    private AutomationDefinition definition(String type, String id, BiFunction<JsonObject, String, AutomationDefinition> reader) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Automation definition is required");
        }
        AssetKey key = new AssetKey(type, id);
        for (int attempt = 0; attempt < 3; attempt++) {
            long generation = storage.snapshotGeneration();
            AssetStamp stamp = storage.readAssetStamp(type, id);
            if (stamp == null || stamp.deleted()) {
                definitions.remove(key);
                throw new IllegalArgumentException("Automation definition not found: " + id);
            }
            Resident resident = definitions.get(key);
            if (resident != null && resident.generation() == generation && resident.stamp().equals(stamp)) {
                if (storage.snapshotGeneration() == generation) {
                    return resident.definition();
                }
                continue;
            }
            definitions.remove(key);
            AutomationDefinition definition = reader.apply(require(type, id), id);
            if (storage.snapshotGeneration() != generation || !stamp.equals(storage.readAssetStamp(type, id))) {
                continue;
            }
            definitions.put(key, new Resident(generation, stamp, definition));
            return definition;
        }
        throw new IllegalStateException("Automation definition changed during admission: " + type + "/" + id);
    }

    private record Resident(long generation, AssetStamp stamp, AutomationDefinition definition) {
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
