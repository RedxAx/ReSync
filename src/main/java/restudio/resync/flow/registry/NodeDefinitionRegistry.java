package restudio.resync.flow.registry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NodeDefinitionRegistry {
    private final Map<String, NodeDefinition> definitions = new HashMap<>();
    private final Map<String, List<NodeDefinition>> pluginDefinitions = new HashMap<>();
    private final Map<String, String> nodeToPlugin = new HashMap<>();
    private String defaultPluginId = "standard";

    public void setDefaultPluginId(String pluginId) {
        if (pluginId != null && !pluginId.isBlank()) {
            this.defaultPluginId = pluginId;
        }
    }

    public void register(NodeDefinition definition) {
        register(defaultPluginId, definition);
    }

    public void register(String pluginId, NodeDefinition definition) {
        if (pluginId == null || definition == null || definition.getId() == null) {
            return;
        }
        String nodeId = definition.getId();
        String previousPlugin = nodeToPlugin.get(nodeId);
        if (previousPlugin != null && !previousPlugin.equals(pluginId)) {
            List<NodeDefinition> previousList = pluginDefinitions.get(previousPlugin);
            if (previousList != null) {
                previousList.removeIf(existing -> nodeId.equals(existing.getId()));
            }
        }

        definitions.put(nodeId, definition);
        nodeToPlugin.put(nodeId, pluginId);
        pluginDefinitions.computeIfAbsent(pluginId, ignored -> new ArrayList<>()).removeIf(existing -> nodeId.equals(existing.getId()));
        pluginDefinitions.computeIfAbsent(pluginId, ignored -> new ArrayList<>()).add(definition);
    }

    public void registerAll(String pluginId, List<NodeDefinition> nodeDefinitions) {
        if (nodeDefinitions == null) {
            return;
        }
        for (NodeDefinition definition : nodeDefinitions) {
            register(pluginId, definition);
        }
    }

    public void unregisterPlugin(String pluginId) {
        if (pluginId == null) {
            return;
        }
        List<NodeDefinition> defs = pluginDefinitions.remove(pluginId);
        if (defs != null) {
            for (NodeDefinition def : defs) {
                if (def != null) {
                    String nodeId = def.getId();
                    if (nodeId != null) {
                        definitions.remove(nodeId);
                        nodeToPlugin.remove(nodeId);
                    }
                }
            }
        }
    }

    public List<NodeDefinition> getDefinitionsForPlugin(String pluginId) {
        List<NodeDefinition> defs = pluginDefinitions.get(pluginId);
        if (defs == null) {
            return List.of();
        }
        return new ArrayList<>(defs);
    }

    public Map<String, NodeDefinition> getAllDefinitions() {
        return new HashMap<>(definitions);
    }

    public List<String> getPluginIds() {
        return new ArrayList<>(pluginDefinitions.keySet());
    }

    public String getPluginForNode(String nodeId) {
        return nodeToPlugin.get(nodeId);
    }

    public void clear() {
        definitions.clear();
        pluginDefinitions.clear();
        nodeToPlugin.clear();
    }
}
