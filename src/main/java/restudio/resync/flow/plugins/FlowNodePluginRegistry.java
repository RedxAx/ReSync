package restudio.resync.flow.plugins;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowDataTypeAdapter;
import restudio.resync.Log;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.sync.FlowCategoryMetadata;
import restudio.resync.flow.sync.FlowConversionRule;
import restudio.resync.flow.sync.FlowOptionSourceMetadata;
import restudio.resync.flow.sync.FlowTypeMetadata;
import restudio.resync.flow.sync.NodePluginPayload;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class FlowNodePluginRegistry {
    public interface PluginChangeListener {
        void onPluginLoaded(NodePluginPayload payload);

        void onPluginUnloaded(String pluginId);
    }

    private static final long SCAN_INTERVAL_MS = 5000L;
    private final FlowRegistry flowRegistry;
    private final NodeDefinitionRegistry definitionRegistry;
    private final Path pluginDirectory;
    private final Map<String, PluginState> plugins = new ConcurrentHashMap<>();
    private final Map<Path, JarState> jarStates = new ConcurrentHashMap<>();
    private final List<PluginChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(FlowDataType.class, new FlowDataTypeAdapter())
            .create();
    private long lastScan = 0L;

    public FlowNodePluginRegistry(FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry, Path pluginDirectory) {
        this.flowRegistry = flowRegistry;
        this.definitionRegistry = definitionRegistry;
        this.pluginDirectory = pluginDirectory;
        ensureDirectory();
    }

    public void loadInitialPlugins() {
        scanDirectory(true);
    }

    public void registerBuiltinPlugin(FlowNodePlugin plugin) {
        if (plugin == null) {
            return;
        }
        registerPlugin(plugin, null, null);
    }

    public void tick() {
        long now = System.currentTimeMillis();
        if (now - lastScan < SCAN_INTERVAL_MS) {
            return;
        }
        lastScan = now;
        scanDirectory(false);
    }

    public void shutdown() {
        for (String pluginId : new ArrayList<>(plugins.keySet())) {
            unloadPlugin(pluginId);
        }
        for (JarState state : jarStates.values()) {
            closeClassLoader(state.classLoader);
        }
        jarStates.clear();
    }

    public void addListener(PluginChangeListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(PluginChangeListener listener) {
        listeners.remove(listener);
    }

    public Set<String> getPluginIds() {
        Set<String> ids = new HashSet<>(plugins.keySet());
        ids.addAll(definitionRegistry.getPluginIds());
        return ids;
    }

    public List<FlowTypeMetadata> getAllCustomTypes() {
        List<FlowTypeMetadata> list = new ArrayList<>();
        for (PluginState state : plugins.values()) {
            list.addAll(state.plugin.getCustomTypes());
        }
        return list;
    }

    public List<FlowCategoryMetadata> getAllCustomCategories() {
        List<FlowCategoryMetadata> list = new ArrayList<>();
        for (PluginState state : plugins.values()) {
            list.addAll(state.plugin.getCustomCategories());
        }
        return list;
    }

    public List<FlowOptionSourceMetadata> getAllCustomOptionSources() {
        List<FlowOptionSourceMetadata> list = new ArrayList<>();
        for (PluginState state : plugins.values()) {
            list.addAll(state.plugin.getCustomOptionSources());
        }
        return list;
    }

    public List<FlowConversionRule> getAllCustomConversionRules() {
        List<FlowConversionRule> list = new ArrayList<>();
        for (PluginState state : plugins.values()) {
            list.addAll(state.plugin.getCustomConversionRules());
        }
        return list;
    }

    public NodePluginPayload buildPayload(String pluginId) {
        PluginState state = plugins.get(pluginId);
        List<NodeDefinition> definitions = definitionRegistry.getDefinitionsForPlugin(pluginId);
        if ((state == null) && (definitions == null || definitions.isEmpty())) {
            return null;
        }
        NodePluginPayload payload = new NodePluginPayload();
        payload.setPluginId(state != null ? state.pluginId : pluginId);
        payload.setVersion(state != null ? state.version : "builtin");
        payload.setDescription(state != null ? state.description : "BuiltInNodeDefinitions");
        payload.setChecksum(state != null ? state.checksum : computeChecksum(definitions));
        payload.setNodes(definitions);
        return payload;
    }

    public String getChecksum(String pluginId) {
        PluginState state = plugins.get(pluginId);
        if (state != null) {
            return state.checksum;
        }
        List<NodeDefinition> definitions = definitionRegistry.getDefinitionsForPlugin(pluginId);
        if (definitions == null || definitions.isEmpty()) {
            return null;
        }
        return computeChecksum(definitions);
    }

    private void ensureDirectory() {
        try {
            Files.createDirectories(pluginDirectory);
        } catch (IOException e) {
            Log.error("[ReSync] Failed to create node plugin directory: " + e.getMessage(), e);
        }
    }

    private void scanDirectory(boolean initial) {
        ensureDirectory();
        Map<Path, Long> currentFiles = new HashMap<>();
        try (var stream = Files.list(pluginDirectory)) {
            stream.filter(path -> path.toString().toLowerCase().endsWith(".jar"))
                .forEach(path -> {
                    try {
                        currentFiles.put(path, Files.getLastModifiedTime(path).toMillis());
                    } catch (IOException e) {
                        Log.error("[ReSync] Failed to stat plugin jar: " + path + " - " + e.getMessage(), e);
                    }
                });
        } catch (IOException e) {
            Log.error("[ReSync] Failed to scan plugin directory: " + e.getMessage(), e);
            return;
        }

        for (Path existing : new ArrayList<>(jarStates.keySet())) {
            if (!currentFiles.containsKey(existing)) {
                unloadJar(existing);
            }
        }

        for (var entry : currentFiles.entrySet()) {
            Path jarPath = entry.getKey();
            long modified = entry.getValue();
            JarState state = jarStates.get(jarPath);
            if (state == null || state.lastModified != modified) {
                if (state != null) {
                    unloadJar(jarPath);
                }
                loadJar(jarPath, modified);
            }
        }
    }

    private void loadJar(Path jarPath, long modified) {
        try {
            URL url = jarPath.toUri().toURL();
            URLClassLoader loader = new URLClassLoader(new URL[]{url}, FlowNodePlugin.class.getClassLoader());
            ServiceLoader<FlowNodePlugin> serviceLoader = ServiceLoader.load(FlowNodePlugin.class, loader);
            List<String> pluginIds = new ArrayList<>();
            for (FlowNodePlugin plugin : serviceLoader) {
                if (plugin == null || plugin.getPluginId() == null || plugin.getPluginId().isBlank()) {
                    continue;
                }
                registerPlugin(plugin, loader, jarPath);
                pluginIds.add(plugin.getPluginId());
            }

            if (pluginIds.isEmpty()) {
                closeClassLoader(loader);
                return;
            }
            jarStates.put(jarPath, new JarState(modified, loader, pluginIds));
            Log.info("[ReSync] Loaded node plugins from " + jarPath.getFileName());
        } catch (Exception e) {
            Log.error("[ReSync] Failed to load plugin jar " + jarPath + ": " + e.getMessage(), e);
        }
    }

    private void unloadJar(Path jarPath) {
        JarState state = jarStates.remove(jarPath);
        if (state == null) {
            return;
        }
        for (String pluginId : state.pluginIds) {
            unloadPlugin(pluginId);
        }
        closeClassLoader(state.classLoader);
        Log.info("[ReSync] Unloaded node plugins from " + jarPath.getFileName());
    }

    private void registerPlugin(FlowNodePlugin plugin, URLClassLoader loader, Path jarPath) {
        String pluginId = plugin.getPluginId();
        if (pluginId == null || pluginId.isBlank()) {
            return;
        }
        if (plugins.containsKey(pluginId)) {
            unloadPlugin(pluginId);
            Log.info("[ReSync] Replacing node plugin: " + pluginId);
        }

        try {
            plugin.registerNodeDefinitions(definitionRegistry);
            plugin.registerNodes(flowRegistry);
        } catch (Exception e) {
            Log.error("[ReSync] Failed to register node plugin " + pluginId + ": " + e.getMessage(), e);
            try {
                plugin.unregisterNodes(flowRegistry);
                plugin.unregisterNodeDefinitions(definitionRegistry);
            } catch (Exception ignored) {
            }
            definitionRegistry.unregisterPlugin(pluginId);
            return;
        }

        List<NodeDefinition> definitions = definitionRegistry.getDefinitionsForPlugin(pluginId);
        String checksum = computeChecksum(definitions);

        PluginState state = new PluginState(pluginId, plugin.getVersion(), plugin.getDescription(), checksum, plugin, loader, jarPath);
        state.nodeIds.addAll(definitions.stream().map(NodeDefinition::getId).toList());
        plugins.put(pluginId, state);

        NodePluginPayload payload = buildPayload(pluginId);
        notifyPluginLoaded(payload);
    }

    private void unloadPlugin(String pluginId) {
        PluginState state = plugins.remove(pluginId);
        if (state == null) {
            return;
        }
        try {
            state.plugin.unregisterNodes(flowRegistry);
            state.plugin.unregisterNodeDefinitions(definitionRegistry);
        } catch (Exception e) {
            Log.error("[ReSync] Error unloading plugin " + pluginId + ": " + e.getMessage(), e);
        }

        for (String nodeId : state.nodeIds) {
            if (nodeId != null) {
                flowRegistry.unregister(nodeId);
            }
        }
        definitionRegistry.unregisterPlugin(pluginId);
        notifyPluginUnloaded(pluginId);
    }

    private void notifyPluginLoaded(NodePluginPayload payload) {
        if (payload == null) {
            return;
        }
        for (PluginChangeListener listener : listeners) {
            listener.onPluginLoaded(payload);
        }
    }

    private void notifyPluginUnloaded(String pluginId) {
        for (PluginChangeListener listener : listeners) {
            listener.onPluginUnloaded(pluginId);
        }
    }

    private String computeChecksum(List<NodeDefinition> definitions) {
        if (definitions == null) {
            return "";
        }
        List<NodeDefinition> sorted = new ArrayList<>(definitions);
        sorted.sort(Comparator.comparing(NodeDefinition::getId, String.CASE_INSENSITIVE_ORDER));
        String json = gson.toJson(sorted);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            return Integer.toHexString(json.hashCode());
        }
    }

    private void closeClassLoader(URLClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (IOException e) {
            Log.error("[ReSync] Failed to close plugin classloader: " + e.getMessage(), e);
        }
    }

    private static class JarState {
        private final long lastModified;
        private final URLClassLoader classLoader;
        private final List<String> pluginIds;

        private JarState(long lastModified, URLClassLoader classLoader, List<String> pluginIds) {
            this.lastModified = lastModified;
            this.classLoader = classLoader;
            this.pluginIds = pluginIds;
        }
    }

    private static class PluginState {
        private final String pluginId;
        private final String version;
        private final String description;
        private final String checksum;
        private final FlowNodePlugin plugin;
        private final URLClassLoader classLoader;
        private final Path jarPath;
        private final Set<String> nodeIds = new HashSet<>();

        private PluginState(String pluginId, String version, String description, String checksum, FlowNodePlugin plugin, URLClassLoader classLoader, Path jarPath) {
            this.pluginId = pluginId;
            this.version = version;
            this.description = description;
            this.checksum = checksum;
            this.plugin = plugin;
            this.classLoader = classLoader;
            this.jarPath = jarPath;
        }
    }
}
