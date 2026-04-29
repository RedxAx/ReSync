package restudio.resync.worldgen.registry;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

public class WorldGenNodeRegistry {
    private static final WorldGenNodeRegistry INSTANCE = new WorldGenNodeRegistry();
    private final Map<String, WorldGenNodeDefinition> definitions = new LinkedHashMap<>();

    public static WorldGenNodeRegistry getInstance() {
        return INSTANCE;
    }

    public void register(WorldGenNodeDefinition definition) {
        definitions.put(definition.getId(), definition);
    }

    public WorldGenNodeDefinition getDefinition(String nodeId) {
        return definitions.get(nodeId);
    }

    public Collection<WorldGenNodeDefinition> getAllDefinitions() {
        return definitions.values().stream().sorted(Comparator.comparingInt(WorldGenNodeDefinition::getPriority)).toList();
    }

    public boolean hasDefinitions() {
        return !definitions.isEmpty();
    }
}
