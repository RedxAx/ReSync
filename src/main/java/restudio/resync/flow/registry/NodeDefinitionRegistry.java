package restudio.resync.flow.registry;

import restudio.resync.api.ExtensionRegistryActivation;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class NodeDefinitionRegistry {
    private static volatile NodeDefinitionRegistry INSTANCE;
    private volatile RegistryState state = RegistryState.empty();
    private volatile String defaultPluginId = "standard";
    private volatile ExtensionRegistryActivation activation;

    public NodeDefinitionRegistry() {
        this(true);
    }

    public NodeDefinitionRegistry(boolean installAsDefault) {
        if (installAsDefault) {
            INSTANCE = this;
        }
    }

    public static NodeDefinitionRegistry getInstance() {
        return INSTANCE;
    }

    public void bindActivation(ExtensionRegistryActivation activation) {
        this.activation = activation;
    }

    public ExtensionRegistryActivation activation() {
        return activation;
    }

    public void setDefaultPluginId(String pluginId) {
        if (pluginId != null && !pluginId.isBlank()) {
            ExtensionRegistryActivation current = activation;
            if (current != null) {
                current.update(NodeDefinitionRegistry.class, target -> {
                    target.setDefaultPluginId(pluginId);
                    return null;
                });
            } else {
                defaultPluginId = pluginId;
            }
        }
    }

    public String defaultPluginId() {
        NodeDefinitionRegistry activeRegistry = activeRegistry();
        return activeRegistry != this ? activeRegistry.defaultPluginId() : defaultPluginId;
    }

    public synchronized void register(NodeDefinition definition) {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(NodeDefinitionRegistry.class, target -> {
                target.register(definition);
                return null;
            });
            return;
        }
        register(defaultPluginId, definition);
    }

    public synchronized void register(String pluginId, NodeDefinition definition) {
        if (pluginId == null || definition == null || definition.getId() == null) {
            return;
        }
        registerAll(pluginId, List.of(definition));
    }

    public synchronized void registerAll(String pluginId, List<NodeDefinition> nodeDefinitions) {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(NodeDefinitionRegistry.class, target -> {
                target.registerAllLocal(pluginId, nodeDefinitions);
                return null;
            });
            return;
        }
        registerAllLocal(pluginId, nodeDefinitions);
    }

    private synchronized void registerAllLocal(String pluginId, List<NodeDefinition> nodeDefinitions) {
        if (pluginId == null || nodeDefinitions == null) {
            return;
        }
        MutableState next = new MutableState(state);
        for (NodeDefinition definition : nodeDefinitions) {
            if (definition != null && definition.getId() != null) {
                next.add(pluginId, definition);
            }
        }
        state = next.freeze();
    }

    public synchronized void unregisterPlugin(String pluginId) {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(NodeDefinitionRegistry.class, target -> {
                target.unregisterPluginLocal(pluginId);
                return null;
            });
            return;
        }
        unregisterPluginLocal(pluginId);
    }

    private synchronized void unregisterPluginLocal(String pluginId) {
        if (pluginId == null) {
            return;
        }
        MutableState next = new MutableState(state);
        if (next.pluginDefinitions.remove(pluginId) == null) {
            return;
        }
        List<String> identities = next.nodeToPlugin.entrySet().stream()
            .filter(entry -> pluginId.equals(entry.getValue()))
            .map(Map.Entry::getKey)
            .toList();
        for (String identity : identities) {
            NodeDefinition definition = next.definitions.remove(identity);
            next.nodeToPlugin.remove(identity);
            if (definition == null || definition.getId() == null) {
                continue;
            }
            List<String> nodeIdentities = next.identitiesByNodeId.get(definition.getId());
            if (nodeIdentities != null) {
                nodeIdentities.remove(identity);
                if (nodeIdentities.isEmpty()) {
                    next.identitiesByNodeId.remove(definition.getId());
                }
            }
        }
        state = next.freeze();
    }

    public List<NodeDefinition> getDefinitionsForPlugin(String pluginId) {
        NodeDefinitionRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.getDefinitionsForPlugin(pluginId);
        }
        RegistryState current = state;
        List<NodeDefinition> definitions = current.pluginDefinitions.get(pluginId);
        return definitions != null ? definitions : List.of();
    }

    public Map<String, NodeDefinition> getAllDefinitions() {
        NodeDefinitionRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.getAllDefinitions();
        }
        RegistryState current = state;
        Map<String, NodeDefinition> result = new LinkedHashMap<>();
        current.definitions.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(result);
    }

    public List<String> getPluginIds() {
        NodeDefinitionRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.getPluginIds();
        }
        RegistryState current = state;
        return List.copyOf(current.pluginDefinitions.keySet());
    }

    public String getPluginForNode(String nodeId) {
        NodeDefinitionRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.getPluginForNode(nodeId);
        }
        RegistryState current = state;
        List<String> identities = current.identitiesByNodeId.get(nodeId);
        return identities != null && identities.size() == 1 ? current.nodeToPlugin.get(identities.getFirst()) : null;
    }

    public String getPluginForNode(NodeDefinition definition) {
        NodeDefinitionRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.getPluginForNode(definition);
        }
        RegistryState current = state;
        if (definition == null) {
            return null;
        }
        String plugin = current.nodeToPlugin.get(identityKey(definition));
        if (plugin != null) {
            return plugin;
        }
        return current.definitions.entrySet().stream()
            .filter(entry -> entry.getValue() == definition)
            .map(Map.Entry::getKey)
            .findFirst()
            .map(current.nodeToPlugin::get)
            .orElse(null);
    }

    public NodeDefinition get(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        ExtensionRegistryActivation currentActivation = activation;
        if (currentActivation != null) {
            return currentActivation.snapshot().nodeDefinition(id);
        }
        RegistryState current = state;
        NodeDefinition direct = current.definitions.get(id);
        if (direct != null) {
            return direct;
        }
        try {
            ContractRef<NodeId> reference = ContractRef.parseCanonicalText(id, NodeId::new);
            NodeDefinition qualified = current.definitions.get(reference.ownerId() + '\u0000' + reference.localId());
            if (qualified != null) {
                return qualified;
            }
        } catch (IllegalArgumentException ignored) {
        }
        List<String> identities = current.identitiesByNodeId.get(id);
        return identities != null && identities.size() == 1 ? current.definitions.get(identities.getFirst()) : null;
    }

    public NodeDefinition get(String owner, String id) {
        if (owner == null || owner.isBlank() || id == null || id.isBlank()) {
            return null;
        }
        ExtensionRegistryActivation currentActivation = activation;
        if (currentActivation != null) {
            return currentActivation.snapshot().nodeDefinition(owner, id);
        }
        RegistryState current = state;
        return current.definitions.get(owner + '\u0000' + id);
    }

    public synchronized void clear() {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(NodeDefinitionRegistry.class, target -> {
                target.clearLocal();
                return null;
            });
            return;
        }
        clearLocal();
    }

    private synchronized void clearLocal() {
        state = RegistryState.empty();
    }

    public synchronized void replaceFrom(NodeDefinitionRegistry staged) {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(NodeDefinitionRegistry.class, target -> {
                target.replaceFrom(staged);
                return null;
            });
            return;
        }
        replaceFromLocal(staged);
    }

    private synchronized void replaceFromLocal(NodeDefinitionRegistry staged) {
        if (staged == null) {
            throw new IllegalArgumentException("A staged node definition registry is required");
        }
        state = staged.state;
        defaultPluginId = staged.defaultPluginId;
    }

    public synchronized NodeDefinitionRegistry copy() {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            return current.snapshot().nodeDefinitions();
        }
        return copyLocal();
    }

    private synchronized NodeDefinitionRegistry copyLocal() {
        NodeDefinitionRegistry copy = new NodeDefinitionRegistry(false);
        Map<String, NodeDefinition> definitions = new LinkedHashMap<>();
        state.definitions.forEach((identity, definition) -> definitions.put(identity, definition.copy()));
        Map<String, List<NodeDefinition>> pluginDefinitions = new LinkedHashMap<>();
        state.pluginDefinitions.forEach((plugin, values) -> pluginDefinitions.put(plugin,
            values.stream().map(NodeDefinition::copy).toList()));
        copy.state = new RegistryState(definitions, pluginDefinitions, state.nodeToPlugin, state.identitiesByNodeId);
        copy.defaultPluginId = defaultPluginId;
        return copy;
    }

    public Map<String, List<NodeDefinition>> snapshotByPlugin() {
        NodeDefinitionRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.snapshotByPlugin();
        }
        RegistryState current = state;
        return current.pluginDefinitions;
    }

    private NodeDefinitionRegistry activeRegistry() {
        ExtensionRegistryActivation current = activation;
        return current != null ? current.snapshot().nodeDefinitions() : this;
    }

    private static final class RegistryState {
        private final Map<String, NodeDefinition> definitions;
        private final Map<String, List<NodeDefinition>> pluginDefinitions;
        private final Map<String, String> nodeToPlugin;
        private final Map<String, List<String>> identitiesByNodeId;

        private RegistryState(Map<String, NodeDefinition> definitions,
                              Map<String, List<NodeDefinition>> pluginDefinitions,
                              Map<String, String> nodeToPlugin,
                              Map<String, List<String>> identitiesByNodeId) {
            this.definitions = immutableMap(definitions);
            this.pluginDefinitions = immutableNestedListMap(pluginDefinitions);
            this.nodeToPlugin = immutableMap(nodeToPlugin);
            this.identitiesByNodeId = immutableNestedListMap(identitiesByNodeId);
        }

        private static RegistryState empty() {
            return new RegistryState(Map.of(), Map.of(), Map.of(), Map.of());
        }

        private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(source));
        }

        private static <K, V> Map<K, List<V>> immutableNestedListMap(Map<K, List<V>> source) {
            Map<K, List<V>> copy = new LinkedHashMap<>();
            source.forEach((key, values) -> copy.put(key, List.copyOf(values)));
            return Collections.unmodifiableMap(copy);
        }
    }

    private static final class MutableState {
        private final Map<String, NodeDefinition> definitions;
        private final Map<String, List<NodeDefinition>> pluginDefinitions;
        private final Map<String, String> nodeToPlugin;
        private final Map<String, List<String>> identitiesByNodeId;

        private MutableState(RegistryState source) {
            definitions = new LinkedHashMap<>(source.definitions);
            pluginDefinitions = copyNestedLists(source.pluginDefinitions);
            nodeToPlugin = new LinkedHashMap<>(source.nodeToPlugin);
            identitiesByNodeId = copyNestedLists(source.identitiesByNodeId);
        }

        private void add(String pluginId, NodeDefinition definition) {
            String identity = identityKey(definition);
            if (definitions.containsKey(identity)) {
                throw new IllegalArgumentException("Duplicate owner-qualified node identity: " + identity);
            }
            definitions.put(identity, definition);
            nodeToPlugin.put(identity, pluginId);
            identitiesByNodeId.computeIfAbsent(definition.getId(), ignored -> new ArrayList<>()).add(identity);
            pluginDefinitions.computeIfAbsent(pluginId, ignored -> new ArrayList<>()).add(definition);
        }

        private RegistryState freeze() {
            return new RegistryState(definitions, pluginDefinitions, nodeToPlugin, identitiesByNodeId);
        }

        private static <K, V> Map<K, List<V>> copyNestedLists(Map<K, List<V>> source) {
            Map<K, List<V>> copy = new LinkedHashMap<>();
            source.forEach((key, values) -> copy.put(key, new ArrayList<>(values)));
            return copy;
        }
    }

    private static String identityKey(NodeDefinition definition) {
        return definition.getOwner() + '\u0000' + definition.getId();
    }
}
