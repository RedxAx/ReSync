package restudio.resync.api;

import restudio.resync.flow.sync.FlowCategoryMetadata;
import restudio.resync.flow.sync.FlowConversionRule;
import restudio.resync.flow.sync.FlowOptionSourceMetadata;
import restudio.resync.flow.sync.FlowTypeMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

public class ReSyncExtensionData {
    private final Map<String, List<FlowTypeMetadata>> types = new ConcurrentHashMap<>();
    private final Map<String, List<FlowCategoryMetadata>> categories = new ConcurrentHashMap<>();
    private final Map<String, List<FlowOptionSourceMetadata>> optionSources = new ConcurrentHashMap<>();
    private final Map<String, List<FlowConversionRule>> conversions = new ConcurrentHashMap<>();
    private final Map<String, PluginMetadata> plugins = new ConcurrentHashMap<>();

    public void addPlugin(String pluginId, String version, String description) {
        if (pluginId != null && !pluginId.isBlank()) {
            plugins.put(pluginId, new PluginMetadata(version, description));
        }
    }

    public void addType(String pluginId, FlowTypeMetadata metadata) {
        if (metadata != null) {
            List<FlowTypeMetadata> values = types.computeIfAbsent(pluginId, ignored -> new CopyOnWriteArrayList<>());
            values.removeIf(existing -> existing != null && existing.getId() != null && existing.getId().equalsIgnoreCase(metadata.getId()));
            values.add(metadata);
        }
    }

    public void addCategory(String pluginId, FlowCategoryMetadata metadata) {
        if (metadata != null) {
            List<FlowCategoryMetadata> values = categories.computeIfAbsent(pluginId, ignored -> new CopyOnWriteArrayList<>());
            values.removeIf(existing -> existing != null && existing.getId() != null && existing.getId().equalsIgnoreCase(metadata.getId()));
            values.add(metadata);
        }
    }

    public void addOptionSource(String pluginId, FlowOptionSourceMetadata metadata) {
        if (metadata != null) {
            List<FlowOptionSourceMetadata> values = optionSources.computeIfAbsent(pluginId, ignored -> new CopyOnWriteArrayList<>());
            values.removeIf(existing -> existing != null && existing.getId() != null && existing.getId().equalsIgnoreCase(metadata.getId()));
            values.add(metadata);
        }
    }

    public void addConversion(String pluginId, FlowConversionRule rule) {
        if (rule != null) {
            conversions.computeIfAbsent(pluginId, ignored -> new CopyOnWriteArrayList<>()).add(rule);
        }
    }

    public List<FlowTypeMetadata> types() {
        return flatten(types);
    }

    public List<FlowCategoryMetadata> categories() {
        return flatten(categories);
    }

    public List<FlowOptionSourceMetadata> optionSources() {
        return flatten(optionSources);
    }

    public List<FlowConversionRule> conversions() {
        return flatten(conversions);
    }

    public void removePlugin(String pluginId) {
        plugins.remove(pluginId);
        types.remove(pluginId);
        categories.remove(pluginId);
        optionSources.remove(pluginId);
        conversions.remove(pluginId);
    }

    public String version(String pluginId) {
        PluginMetadata metadata = plugins.get(pluginId);
        return metadata != null && metadata.version != null ? metadata.version : "extension";
    }

    public String description(String pluginId) {
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

    private record PluginMetadata(String version, String description) {
    }
}
