package restudio.resync.flow;

import restudio.flow.data.ScoreboardDefinition;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceMutationStamp;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class ScoreboardRuntimeCapability {
    private final FlowResourceRegistry resourceRegistry;
    private final Map<String, ResidentScoreboard> residents = new ConcurrentHashMap<>();

    private ScoreboardRuntimeCapability(FlowResourceRegistry resourceRegistry) {
        this.resourceRegistry = resourceRegistry;
    }

    public static ScoreboardRuntimeCapability unavailable() {
        return new ScoreboardRuntimeCapability(null);
    }

    public static ScoreboardRuntimeCapability of(FlowResourceRegistry resourceRegistry) {
        return new ScoreboardRuntimeCapability(Objects.requireNonNull(resourceRegistry, "Scoreboard resource registry is required"));
    }

    public boolean available() {
        return adapter() != null;
    }

    public ScoreboardDefinition get(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            FlowResourceAdapter<ScoreboardDefinition> adapter = adapter();
            if (adapter == null) {
                residents.remove(id);
                return null;
            }
            FlowResourceMutationStamp stamp = adapter.readMutationStamp(id);
            if (!live(stamp, id)) {
                residents.remove(id);
                return null;
            }
            ResidentScoreboard cached = residents.get(id);
            if (cached != null && stamp.equals(cached.stamp())) {
                return cached.definition();
            }
            ScoreboardDefinition definition = adapter.get(id);
            if (definition == null) {
                throw new IllegalStateException("Live scoreboard authority returned no payload for " + id);
            }
            if (!id.equals(definition.getId())) {
                throw new IllegalStateException("Live scoreboard authority returned the wrong identity for " + id);
            }
            residents.put(id, new ResidentScoreboard(stamp, definition));
            return definition;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Scoreboard authority could not read " + id, failure);
        }
    }

    public List<String> listIds() {
        try {
            FlowResourceAdapter<ScoreboardDefinition> adapter = adapter();
            if (adapter == null) {
                return List.of();
            }
            List<String> ids = adapter.listIds();
            if (ids == null || ids.isEmpty()) {
                return List.of();
            }
            return ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .filter(id -> live(adapter.readMutationStamp(id), id))
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Scoreboard authority could not list resources", failure);
        }
    }

    private FlowResourceAdapter<ScoreboardDefinition> adapter() {
        if (resourceRegistry == null) {
            return null;
        }
        FlowResourceAdapter<?> candidate = resourceRegistry.get(ReSyncResourceCatalog.SCOREBOARD);
        if (candidate == null || candidate.descriptor() == null
            || !ReSyncResourceCatalog.SCOREBOARD.equals(candidate.descriptor().typeId())
            || !candidate.supportedOperations().contains("get")
            || !candidate.supportsAuthoritativeMutationIdentity()) {
            return null;
        }
        @SuppressWarnings("unchecked")
        FlowResourceAdapter<ScoreboardDefinition> scoreboardAdapter =
            (FlowResourceAdapter<ScoreboardDefinition>) candidate;
        return scoreboardAdapter;
    }

    private boolean live(FlowResourceMutationStamp stamp, String id) {
        return stamp != null
            && ReSyncResourceCatalog.SCOREBOARD.equals(stamp.type())
            && id.equals(stamp.id())
            && !stamp.deleted();
    }

    private record ResidentScoreboard(FlowResourceMutationStamp stamp, ScoreboardDefinition definition) {
    }
}
