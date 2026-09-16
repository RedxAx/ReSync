package restudio.resync.api;

import restudio.resync.flow.sync.FlowCategoryMetadata;
import restudio.resync.flow.sync.FlowConversionRule;
import restudio.resync.flow.sync.FlowOptionSourceMetadata;
import restudio.resync.flow.sync.FlowTypeMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

public class ReSyncExtensionData {
    private final Map<String, List<FlowTypeMetadata>> types = new ConcurrentHashMap<>();
    private final Map<String, List<FlowCategoryMetadata>> categories = new ConcurrentHashMap<>();
    private final Map<String, List<FlowOptionSourceMetadata>> optionSources = new ConcurrentHashMap<>();
    private final Map<String, List<FlowConversionRule>> conversions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> resources = new ConcurrentHashMap<>();
    private final Map<String, PluginMetadata> plugins = new ConcurrentHashMap<>();
    private volatile ExtensionRegistryActivation activation;

    public void bindActivation(ExtensionRegistryActivation activation) {
        this.activation = activation;
    }

    public void addPlugin(String pluginId, String version, String description) {
        if (pluginId != null && !pluginId.isBlank()) {
            ExtensionRegistryActivation current = activation;
            if (current != null) {
                current.update(ReSyncExtensionData.class, target -> {
                    target.addPluginLocal(pluginId, version, description);
                    return null;
                });
                return;
            }
            addPluginLocal(pluginId, version, description);
        }
    }

    private void addPluginLocal(String pluginId, String version, String description) {
        plugins.put(pluginId, new PluginMetadata(version, description));
    }

    public void addType(String pluginId, FlowTypeMetadata metadata) {
        if (metadata != null) {
            ExtensionRegistryActivation current = activation;
            if (current != null) {
                current.update(ReSyncExtensionData.class, target -> {
                    target.addTypeLocal(pluginId, metadata);
                    return null;
                });
                return;
            }
            addTypeLocal(pluginId, metadata);
        }
    }

    private void addTypeLocal(String pluginId, FlowTypeMetadata metadata) {
        metadata.setOwner(pluginId);
        List<FlowTypeMetadata> values = types.computeIfAbsent(pluginId, ignored -> new CopyOnWriteArrayList<>());
        values.removeIf(existing -> existing != null && existing.getId() != null && existing.getId().equalsIgnoreCase(metadata.getId()));
        values.add(metadata);
    }

    public void addCategory(String pluginId, FlowCategoryMetadata metadata) {
        if (metadata != null) {
            ExtensionRegistryActivation current = activation;
            if (current != null) {
                current.update(ReSyncExtensionData.class, target -> {
                    target.addCategoryLocal(pluginId, metadata);
                    return null;
                });
                return;
            }
            addCategoryLocal(pluginId, metadata);
        }
    }

    private void addCategoryLocal(String pluginId, FlowCategoryMetadata metadata) {
        List<FlowCategoryMetadata> values = categories.computeIfAbsent(pluginId, ignored -> new CopyOnWriteArrayList<>());
        values.removeIf(existing -> existing != null && existing.getId() != null && existing.getId().equalsIgnoreCase(metadata.getId()));
        values.add(metadata);
    }

    public void addOptionSource(String pluginId, FlowOptionSourceMetadata metadata) {
        if (metadata != null) {
            ExtensionRegistryActivation current = activation;
            if (current != null) {
                current.update(ReSyncExtensionData.class, target -> {
                    target.addOptionSourceLocal(pluginId, metadata);
                    return null;
                });
                return;
            }
            addOptionSourceLocal(pluginId, metadata);
        }
    }

    private void addOptionSourceLocal(String pluginId, FlowOptionSourceMetadata metadata) {
        List<FlowOptionSourceMetadata> values = optionSources.computeIfAbsent(pluginId, ignored -> new CopyOnWriteArrayList<>());
        values.removeIf(existing -> existing != null && existing.getId() != null && existing.getId().equalsIgnoreCase(metadata.getId()));
        values.add(metadata);
    }

    public void addConversion(String pluginId, FlowConversionRule rule) {
        if (rule != null) {
            ExtensionRegistryActivation current = activation;
            if (current != null) {
                current.update(ReSyncExtensionData.class, target -> {
                    target.addConversionLocal(pluginId, rule);
                    return null;
                });
                return;
            }
            addConversionLocal(pluginId, rule);
        }
    }

    private void addConversionLocal(String pluginId, FlowConversionRule rule) {
        conversions.computeIfAbsent(pluginId, ignored -> new CopyOnWriteArrayList<>()).add(rule);
    }

    public void addResource(String pluginId, String typeId) {
        if (pluginId != null && typeId != null && !typeId.isBlank()) {
            ExtensionRegistryActivation current = activation;
            if (current != null) {
                current.update(ReSyncExtensionData.class, target -> {
                    target.addResourceLocal(pluginId, typeId);
                    return null;
                });
                return;
            }
            addResourceLocal(pluginId, typeId);
        }
    }

    private void addResourceLocal(String pluginId, String typeId) {
        resources.computeIfAbsent(pluginId, ignored -> ConcurrentHashMap.newKeySet()).add(typeId);
    }

    public List<FlowTypeMetadata> types() {
        ReSyncExtensionData activeData = activeData();
        if (activeData != this) return activeData.types();
        return flatten(types);
    }

    public List<FlowCategoryMetadata> categories() {
        ReSyncExtensionData activeData = activeData();
        if (activeData != this) return activeData.categories();
        return flatten(categories);
    }

    public List<FlowOptionSourceMetadata> optionSources() {
        ReSyncExtensionData activeData = activeData();
        if (activeData != this) return activeData.optionSources();
        return flatten(optionSources);
    }

    public List<FlowConversionRule> conversions() {
        ReSyncExtensionData activeData = activeData();
        if (activeData != this) return activeData.conversions();
        return flatten(conversions);
    }

    public List<String> pluginIds() {
        ReSyncExtensionData activeData = activeData();
        if (activeData != this) return activeData.pluginIds();
        Set<String> ids = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        ids.addAll(plugins.keySet());
        ids.addAll(types.keySet());
        ids.addAll(categories.keySet());
        ids.addAll(optionSources.keySet());
        ids.addAll(conversions.keySet());
        ids.addAll(resources.keySet());
        return List.copyOf(ids);
    }

    public Map<String, Map<String, Integer>> contributionCounts() {
        ReSyncExtensionData activeData = activeData();
        if (activeData != this) return activeData.contributionCounts();
        Map<String, Map<String, Integer>> counts = new ConcurrentHashMap<>();
        for (String pluginId : pluginIds()) {
            counts.put(pluginId, Map.of(
                "types", size(types, pluginId),
                "categories", size(categories, pluginId),
                "catalogs", size(optionSources, pluginId),
                "conversions", size(conversions, pluginId),
                "resources", resourceSize(pluginId)
            ));
        }
        return Map.copyOf(counts);
    }

    public void removePlugin(String pluginId) {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(ReSyncExtensionData.class, target -> {
                target.removePluginLocal(pluginId);
                return null;
            });
            return;
        }
        removePluginLocal(pluginId);
    }

    private void removePluginLocal(String pluginId) {
        plugins.remove(pluginId);
        types.remove(pluginId);
        categories.remove(pluginId);
        optionSources.remove(pluginId);
        conversions.remove(pluginId);
        resources.remove(pluginId);
    }

    public synchronized ReSyncExtensionData copy() {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            return current.snapshot().extensionData();
        }
        ReSyncExtensionData copy = new ReSyncExtensionData();
        types.forEach((pluginId, values) -> copy.types.put(pluginId, new CopyOnWriteArrayList<>(values)));
        categories.forEach((pluginId, values) -> copy.categories.put(pluginId, new CopyOnWriteArrayList<>(values)));
        optionSources.forEach((pluginId, values) -> copy.optionSources.put(pluginId, new CopyOnWriteArrayList<>(values)));
        conversions.forEach((pluginId, values) -> copy.conversions.put(pluginId, new CopyOnWriteArrayList<>(values)));
        resources.forEach((pluginId, values) -> copy.resources.put(pluginId, ConcurrentHashMap.newKeySet()));
        resources.forEach((pluginId, values) -> copy.resources.get(pluginId).addAll(values));
        copy.plugins.putAll(plugins);
        return copy;
    }

    public synchronized void replaceFrom(ReSyncExtensionData staged) {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(ReSyncExtensionData.class, target -> {
                target.replaceFromLocal(staged);
                return null;
            });
            return;
        }
        replaceFromLocal(staged);
    }

    private synchronized void replaceFromLocal(ReSyncExtensionData staged) {
        if (staged == null) {
            throw new IllegalArgumentException("A staged extension data registry is required");
        }
        types.clear();
        staged.types.forEach((pluginId, values) -> types.put(pluginId, new CopyOnWriteArrayList<>(values)));
        categories.clear();
        staged.categories.forEach((pluginId, values) -> categories.put(pluginId, new CopyOnWriteArrayList<>(values)));
        optionSources.clear();
        staged.optionSources.forEach((pluginId, values) -> optionSources.put(pluginId, new CopyOnWriteArrayList<>(values)));
        conversions.clear();
        staged.conversions.forEach((pluginId, values) -> conversions.put(pluginId, new CopyOnWriteArrayList<>(values)));
        resources.clear();
        staged.resources.forEach((pluginId, values) -> {
            Set<String> copy = ConcurrentHashMap.newKeySet();
            copy.addAll(values);
            resources.put(pluginId, copy);
        });
        plugins.clear();
        plugins.putAll(staged.plugins);
    }

    public String version(String pluginId) {
        ReSyncExtensionData activeData = activeData();
        if (activeData != this) return activeData.version(pluginId);
        PluginMetadata metadata = plugins.get(pluginId);
        return metadata != null && metadata.version != null ? metadata.version : "extension";
    }

    public String description(String pluginId) {
        ReSyncExtensionData activeData = activeData();
        if (activeData != this) return activeData.description(pluginId);
        PluginMetadata metadata = plugins.get(pluginId);
        return metadata != null && metadata.description != null ? metadata.description : "ReSyncExtension";
    }

    private <T> List<T> flatten(Map<String, List<T>> source) {
        List<T> output = new ArrayList<>();
        for (List<T> values : source.values()) {
            output.addAll(values);
        }
        return output;
    }

    private <T> int size(Map<String, List<T>> source, String pluginId) {
        List<T> values = source.get(pluginId);
        return values != null ? values.size() : 0;
    }

    private int resourceSize(String pluginId) {
        Set<String> values = resources.get(pluginId);
        return values != null ? values.size() : 0;
    }

    private record PluginMetadata(String version, String description) {
    }

    private ReSyncExtensionData activeData() {
        ExtensionRegistryActivation current = activation;
        return current != null ? current.snapshot().extensionData() : this;
    }
}
