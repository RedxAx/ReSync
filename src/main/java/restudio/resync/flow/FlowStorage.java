package restudio.resync.flow;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.plugin.java.JavaPlugin;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.GuiDefinition;
import restudio.flow.data.ScoreboardDefinition;
import restudio.flow.data.TabDefinition;
import restudio.resync.Log;
import restudio.resync.storage.StorageSafety;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class FlowStorage {
    private final File flowDir;
    private final File guiDir;
    private final File scoreboardDir;
    private final File tabDir;
    private final File projectMetadataDir;
    private final File assetsDir;
    private final File configFile;
    private final Gson gson = new Gson();
    private static final String DEFAULT_SCOREBOARD_ID_KEY = "flow.default-scoreboard.id";
    private static final String DEFAULT_SCOREBOARD_USE_PAPI_KEY = "flow.default-scoreboard.usePapi";
    private static final String DEFAULT_TAB_ID_KEY = "flow.default-tab.id";
    private static final String DEFAULT_TAB_USE_PAPI_KEY = "flow.default-tab.usePapi";
    private static final String REFRESH_INTERVAL_KEY = "flow.refresh-interval-ticks";
    private final Map<String, FlowGraph> graphCache = new ConcurrentHashMap<>();
    private final Map<String, GuiDefinition> guiCache = new ConcurrentHashMap<>();
    private final Map<String, ScoreboardDefinition> scoreboardCache = new ConcurrentHashMap<>();
    private final Map<String, TabDefinition> tabCache = new ConcurrentHashMap<>();
    private final Map<String, String> projectMetadataCache = new ConcurrentHashMap<>();
    private volatile String defaultScoreboardId;
    private volatile boolean defaultScoreboardUsePapi = true;
    private volatile String defaultTabId;
    private volatile boolean defaultTabUsePapi = true;
    private volatile int tabRefreshIntervalTicks = 20;

    public FlowStorage(JavaPlugin plugin) {
        this(plugin.getDataFolder());
    }

    FlowStorage(File dataFolder) {
        this.flowDir = new File(dataFolder, "flows");
        this.guiDir = new File(dataFolder, "guis");
        this.scoreboardDir = new File(dataFolder, "scoreboards");
        this.tabDir = new File(dataFolder, "tabs");
        this.projectMetadataDir = new File(dataFolder, "project-metadata");
        this.assetsDir = new File(dataFolder, "assets");
        this.configFile = new File(dataFolder, "config.properties");

        if (!assetsDir.exists()) {
            assetsDir.mkdirs();
        }
        loadDefaultScoreboard();
        loadDefaultTab();
        loadTabRefreshConfig();
        cleanupBelowNameData();
        migrateLegacyAssets();
    }

    public FlowGraph getGraph(String id) {
        String safeId = safeId(id, "load flow");
        if (safeId == null) {
            return null;
        }
        FlowGraph cached = graphCache.get(safeId);
        if (cached != null) {
            return cached;
        }

        Path file = resourceFile(flowDir, "flow", safeId, "load flow");
        if (file != null && Files.exists(file)) {
            try {
                String json = StorageSafety.readUtf8(file);
                FlowGraph graph = FlowSerializer.deserialize(json);
                graphCache.put(safeId, graph);
                return graph;
            } catch (IOException e) {
                Log.warn("Failed to load flow: " + safeId + " - " + e.getMessage());
            }
        }
        return null;
    }

    public void saveGraph(FlowGraph graph) {
        String safeId = graph != null ? safeId(graph.getId(), "save flow") : null;
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid flow id");
        }
        Path file = writableGraphResourceFile(graph, safeId);
        if (file == null) {
            throw new IllegalStateException("Failed to resolve flow file");
        }
        try {
            String json = FlowSerializer.serialize(graph);
            StorageSafety.writeUtf8Atomic(file, json);
            graphCache.put(safeId, graph);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save flow: " + safeId, e);
        }
    }

    public void deleteGraph(String id) {
        String safeId = safeId(id, "delete flow");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid flow id");
        }
        try {
            deleteResourceFiles(flowDir, "flow", safeId);
            graphCache.remove(safeId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete flow: " + safeId, e);
        }
    }

    public GuiDefinition getGui(String id) {
        String safeId = safeId(id, "load GUI");
        if (safeId == null) {
            return null;
        }
        GuiDefinition cached = guiCache.get(safeId);
        if (cached != null) {
            return cached;
        }

        Path file = resourceFile(guiDir, "gui", safeId, "load GUI");
        if (file != null && Files.exists(file)) {
            try {
                String json = StorageSafety.readUtf8(file);
                GuiDefinition gui = FlowSerializer.deserializeGui(json);
                if (gui != null && (gui.getId() == null || gui.getId().isBlank())) {
                    gui.setId(safeId);
                }
                guiCache.put(safeId, gui);
                return gui;
            } catch (IOException e) {
                Log.warn("Failed to load GUI: " + safeId + " - " + e.getMessage());
            }
        }
        return null;
    }

    public void saveGui(GuiDefinition gui) {
        String safeId = gui != null ? safeId(gui.getId(), "save GUI") : null;
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid GUI id");
        }
        Path file = writableResourceFile(guiDir, "gui", safeId, "save GUI");
        if (file == null) {
            throw new IllegalStateException("Failed to resolve GUI file");
        }
        try {
            String json = FlowSerializer.serializeGui(gui);
            StorageSafety.writeUtf8Atomic(file, json);
            guiCache.put(safeId, gui);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save GUI: " + safeId, e);
        }
    }

    public void deleteGui(String id) {
        String safeId = safeId(id, "delete GUI");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid GUI id");
        }
        try {
            deleteResourceFiles(guiDir, "gui", safeId);
            guiCache.remove(safeId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete GUI: " + safeId, e);
        }
    }

    public ScoreboardDefinition getScoreboard(String id) {
        String safeId = safeId(id, "load scoreboard");
        if (safeId == null) {
            return null;
        }
        ScoreboardDefinition cached = scoreboardCache.get(safeId);
        if (cached != null) {
            return cached;
        }

        Path file = resourceFile(scoreboardDir, "scoreboard", safeId, "load scoreboard");
        if (file != null && Files.exists(file)) {
            try {
                String json = StorageSafety.readUtf8(file);
                ScoreboardDefinition scoreboard = FlowSerializer.deserializeScoreboard(json);
                if (scoreboard != null && (scoreboard.getId() == null || scoreboard.getId().isBlank())) {
                    scoreboard.setId(safeId);
                }
                scoreboardCache.put(safeId, scoreboard);
                return scoreboard;
            } catch (IOException e) {
                Log.warn("Failed to load scoreboard: " + safeId + " - " + e.getMessage());
            }
        }
        return null;
    }

    public void saveScoreboard(ScoreboardDefinition scoreboard) {
        String safeId = scoreboard != null ? safeId(scoreboard.getId(), "save scoreboard") : null;
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid scoreboard id");
        }
        Path file = writableResourceFile(scoreboardDir, "scoreboard", safeId, "save scoreboard");
        if (file == null) {
            throw new IllegalStateException("Failed to resolve scoreboard file");
        }
        try {
            String json = FlowSerializer.serializeScoreboard(scoreboard);
            StorageSafety.writeUtf8Atomic(file, json);
            scoreboardCache.put(safeId, scoreboard);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save scoreboard: " + safeId, e);
        }
    }

    public void deleteScoreboard(String id) {
        String safeId = safeId(id, "delete scoreboard");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid scoreboard id");
        }
        try {
            deleteResourceFiles(scoreboardDir, "scoreboard", safeId);
            scoreboardCache.remove(safeId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete scoreboard: " + safeId, e);
        }
    }

    public TabDefinition getTab(String id) {
        String safeId = safeId(id, "load tab");
        if (safeId == null) {
            return null;
        }
        TabDefinition cached = tabCache.get(safeId);
        if (cached != null) {
            return cached;
        }

        Path file = resourceFile(tabDir, "tab", safeId, "load tab");
        if (file != null && Files.exists(file)) {
            try {
                String json = StorageSafety.readUtf8(file);
                TabDefinition tab = FlowSerializer.deserializeTab(json);
                if (tab != null && (tab.getId() == null || tab.getId().isBlank())) {
                    tab.setId(safeId);
                }
                tabCache.put(safeId, tab);
                return tab;
            } catch (IOException e) {
                Log.warn("Failed to load tab: " + safeId + " - " + e.getMessage());
            }
        }
        return null;
    }

    public void saveTab(TabDefinition tab) {
        String safeId = tab != null ? safeId(tab.getId(), "save tab") : null;
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid tab id");
        }
        Path file = writableResourceFile(tabDir, "tab", safeId, "save tab");
        if (file == null) {
            throw new IllegalStateException("Failed to resolve tab file");
        }
        try {
            String json = FlowSerializer.serializeTab(tab);
            StorageSafety.writeUtf8Atomic(file, json);
            tabCache.put(safeId, tab);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save tab: " + safeId, e);
        }
    }

    public void deleteTab(String id) {
        String safeId = safeId(id, "delete tab");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid tab id");
        }
        try {
            deleteResourceFiles(tabDir, "tab", safeId);
            tabCache.remove(safeId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete tab: " + safeId, e);
        }
    }

    public Map<String, FlowGraph> getGraphCache() {
        return graphCache;
    }

    public String getProjectMetadata(String id) {
        String safeId = projectMetadataId(id);
        String cached = projectMetadataCache.get(safeId);
        if (cached != null) {
            return cached;
        }
        Path file = projectMetadataFile(safeId, "load project metadata");
        if (file != null && Files.exists(file)) {
            try {
                String json = StorageSafety.readUtf8(file);
                projectMetadataCache.put(safeId, json);
                return json;
            } catch (IOException e) {
                Log.warn("Failed to load project metadata: " + safeId + " - " + e.getMessage());
            }
        }
        return null;
    }

    public void saveProjectMetadata(String json) {
        String safeId = projectMetadataId("project");
        Path file = projectMetadataFile(safeId, "save project metadata");
        if (file == null) {
            throw new IllegalStateException("Failed to resolve project metadata file");
        }
        try {
            StorageSafety.writeUtf8Atomic(file, json);
            StorageSafety.writeUtf8Atomic(assetsDir.toPath().resolve(safeId + ".json"), json);
            projectMetadataCache.put(safeId, json);
            syncAssetsFromProjectMetadata(json);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save project metadata: " + safeId, e);
        }
    }

    public void deleteProjectMetadata(String id) {
        String safeId = projectMetadataId(id);
        try {
            Path file = projectMetadataFile(safeId, "delete project metadata");
            if (file != null) {
                StorageSafety.deleteIfExists(file);
            }
            StorageSafety.deleteIfExists(assetsDir.toPath().resolve(safeId + ".json"));
            projectMetadataCache.remove(safeId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete project metadata: " + safeId, e);
        }
    }

    public List<String> listProjectMetadataIds() {
        Set<String> ids = new HashSet<>();
        Path file = assetsDir.toPath().resolve("project.json");
        if (Files.exists(file)) {
            ids.add("project");
        }
        return ids.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public List<String> listFlowIds() {
        return listResourceIds(flowDir, "flow");
    }

    public boolean hasStoredGraphVersion(String id) {
        String safeId = safeId(id, "inspect flow version");
        if (safeId == null) {
            return false;
        }
        Path file = resourceFile(flowDir, "flow", safeId, "inspect flow version");
        if (file == null || !Files.exists(file)) {
            return false;
        }
        try {
            String json = StorageSafety.readUtf8(file);
            return json.contains("\"version\"");
        } catch (IOException e) {
            Log.warn("Failed to inspect flow version: " + safeId + " - " + e.getMessage());
            return false;
        }
    }

    public List<String> listGuiIds() {
        return listResourceIds(guiDir, "gui");
    }

    public Map<String, GuiDefinition> getGuiCache() {
        return guiCache;
    }

    public List<String> listScoreboardIds() {
        return listResourceIds(scoreboardDir, "scoreboard");
    }

    public Map<String, ScoreboardDefinition> getScoreboardCache() {
        return scoreboardCache;
    }

    public List<String> listTabIds() {
        return listResourceIds(tabDir, "tab");
    }

    public Map<String, TabDefinition> getTabCache() {
        return tabCache;
    }

    public synchronized String getDefaultScoreboardId() {
        return defaultScoreboardId;
    }

    public synchronized boolean isDefaultScoreboardUsePapi() {
        return defaultScoreboardUsePapi;
    }

    public synchronized void setDefaultScoreboard(String scoreboardId, boolean usePapi) {
        String normalized = scoreboardId != null ? scoreboardId.trim() : "";
        if (normalized.isBlank()) {
            clearDefaultScoreboard();
            return;
        }
        this.defaultScoreboardId = normalized;
        this.defaultScoreboardUsePapi = usePapi;
        persistDefaultScoreboard();
    }

    public synchronized void clearDefaultScoreboard() {
        this.defaultScoreboardId = null;
        this.defaultScoreboardUsePapi = true;
        persistDefaultScoreboard();
    }

    public synchronized String getDefaultTabId() {
        return defaultTabId;
    }

    public synchronized boolean isDefaultTabUsePapi() {
        return defaultTabUsePapi;
    }

    public synchronized void setDefaultTab(String tabId, boolean usePapi) {
        String normalized = tabId != null ? tabId.trim() : "";
        if (normalized.isBlank()) {
            clearDefaultTab();
            return;
        }
        this.defaultTabId = normalized;
        this.defaultTabUsePapi = usePapi;
        persistDefaultTab();
    }

    public synchronized void clearDefaultTab() {
        this.defaultTabId = null;
        this.defaultTabUsePapi = true;
        persistDefaultTab();
    }

    public synchronized int getTabRefreshIntervalTicks() {
        return tabRefreshIntervalTicks;
    }

    public synchronized void setTabRefreshIntervalTicks(int ticks) {
        this.tabRefreshIntervalTicks = Math.max(1, ticks);
        persistTabRefreshConfig();
    }

    public void preloadAll() {
        for (String id : listFlowIds()) {
            getGraph(id);
        }
        for (String id : listGuiIds()) {
            getGui(id);
        }
        for (String id : listScoreboardIds()) {
            getScoreboard(id);
        }
        for (String id : listTabIds()) {
            getTab(id);
        }
    }

    public void clearCache() {
        graphCache.clear();
        guiCache.clear();
        scoreboardCache.clear();
        tabCache.clear();
        projectMetadataCache.clear();
    }

    private String projectMetadataId(String id) {
        try {
            return StorageSafety.validateId(id);
        } catch (IllegalArgumentException e) {
            return "project";
        }
    }

    private synchronized void loadDefaultScoreboard() {
        Properties properties = loadConfigProperties();
        String configuredId = properties.getProperty(DEFAULT_SCOREBOARD_ID_KEY, "").trim();
        if (!configuredId.isBlank()) {
            defaultScoreboardId = configuredId;
        }
        defaultScoreboardUsePapi = Boolean.parseBoolean(properties.getProperty(DEFAULT_SCOREBOARD_USE_PAPI_KEY, "true"));
        if (defaultScoreboardId != null || !defaultScoreboardUsePapi) {
            return;
        }
        File legacyFile = new File(configFile.getParentFile(), "default-scoreboard.cfg");
        if (!legacyFile.exists()) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(legacyFile.toPath(), StandardCharsets.UTF_8);
            if (!lines.isEmpty()) {
                String id = lines.getFirst().trim();
                if (!id.isBlank()) {
                    defaultScoreboardId = id;
                }
            }
            if (lines.size() > 1) {
                defaultScoreboardUsePapi = Boolean.parseBoolean(lines.get(1).trim());
            }
            persistDefaultScoreboard();
        } catch (IOException e) {
            Log.warn("Failed to load default scoreboard config: " + e.getMessage());
        }
    }

    private synchronized void persistDefaultScoreboard() {
        Properties properties = loadConfigProperties();
        if (defaultScoreboardId == null || defaultScoreboardId.isBlank()) {
            properties.remove(DEFAULT_SCOREBOARD_ID_KEY);
            properties.remove(DEFAULT_SCOREBOARD_USE_PAPI_KEY);
        } else {
            properties.setProperty(DEFAULT_SCOREBOARD_ID_KEY, defaultScoreboardId);
            properties.setProperty(DEFAULT_SCOREBOARD_USE_PAPI_KEY, String.valueOf(defaultScoreboardUsePapi));
        }
        storeConfigProperties(properties);
    }

    private synchronized void loadDefaultTab() {
        Properties properties = loadConfigProperties();
        String configuredId = properties.getProperty(DEFAULT_TAB_ID_KEY, "").trim();
        if (!configuredId.isBlank()) {
            defaultTabId = configuredId;
        }
        defaultTabUsePapi = Boolean.parseBoolean(properties.getProperty(DEFAULT_TAB_USE_PAPI_KEY, "true"));
        if (defaultTabId != null || !defaultTabUsePapi) {
            return;
        }
        File legacyFile = new File(configFile.getParentFile(), "default-tab.cfg");
        if (!legacyFile.exists()) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(legacyFile.toPath(), StandardCharsets.UTF_8);
            if (!lines.isEmpty()) {
                String id = lines.getFirst().trim();
                if (!id.isBlank()) {
                    defaultTabId = id;
                }
            }
            if (lines.size() > 1) {
                defaultTabUsePapi = Boolean.parseBoolean(lines.get(1).trim());
            }
            persistDefaultTab();
        } catch (IOException e) {
            Log.warn("Failed to load default tab config: " + e.getMessage());
        }
    }

    private synchronized void persistDefaultTab() {
        Properties properties = loadConfigProperties();
        if (defaultTabId == null || defaultTabId.isBlank()) {
            properties.remove(DEFAULT_TAB_ID_KEY);
            properties.remove(DEFAULT_TAB_USE_PAPI_KEY);
        } else {
            properties.setProperty(DEFAULT_TAB_ID_KEY, defaultTabId);
            properties.setProperty(DEFAULT_TAB_USE_PAPI_KEY, String.valueOf(defaultTabUsePapi));
        }
        storeConfigProperties(properties);
    }

    private synchronized void loadTabRefreshConfig() {
        Properties properties = loadConfigProperties();
        String configuredInterval = properties.getProperty(REFRESH_INTERVAL_KEY, "").trim();
        if (!configuredInterval.isBlank()) {
            try {
                tabRefreshIntervalTicks = Math.max(1, Integer.parseInt(configuredInterval));
                return;
            } catch (NumberFormatException ignored) {
            }
        }
        File legacyFile = new File(configFile.getParentFile(), "tab-refresh.cfg");
        if (!legacyFile.exists()) {
            return;
        }
        try {
            String content = Files.readString(legacyFile.toPath(), StandardCharsets.UTF_8).trim();
            if (!content.isBlank()) {
                tabRefreshIntervalTicks = Math.max(1, Integer.parseInt(content));
                persistTabRefreshConfig();
            }
        } catch (IOException | NumberFormatException e) {
            Log.warn("Failed to load tab refresh config: " + e.getMessage());
        }
    }

    private synchronized void persistTabRefreshConfig() {
        Properties properties = loadConfigProperties();
        properties.setProperty(REFRESH_INTERVAL_KEY, String.valueOf(Math.max(1, tabRefreshIntervalTicks)));
        storeConfigProperties(properties);
    }

    private Properties loadConfigProperties() {
        Properties properties = new Properties();
        if (!configFile.exists()) {
            return properties;
        }
        try (FileInputStream fis = new FileInputStream(configFile)) {
            properties.load(fis);
        } catch (IOException e) {
            Log.warn("Failed to load config: " + e.getMessage());
        }
        return properties;
    }

    private void storeConfigProperties(Properties properties) {
        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            properties.store(fos, "ReSync Configuration");
        } catch (IOException e) {
            Log.warn("Failed to save config: " + e.getMessage());
        }
    }

    private void cleanupBelowNameData() {
        try {
            File legacyBelowNamesDir = new File(configFile.getParentFile(), "below-names");
            if (legacyBelowNamesDir.exists() && legacyBelowNamesDir.isDirectory()) {
                File[] files = legacyBelowNamesDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file != null && file.isFile()) {
                            Files.deleteIfExists(file.toPath());
                        }
                    }
                }
                Files.deleteIfExists(legacyBelowNamesDir.toPath());
            }
            File legacyDefault = new File(configFile.getParentFile(), "default-below-name.cfg");
            if (legacyDefault.exists()) {
                Files.deleteIfExists(legacyDefault.toPath());
            }
            Properties properties = loadConfigProperties();
            properties.remove("flow.default-below-name.id");
            properties.remove("flow.default-below-name.usePapi");
            storeConfigProperties(properties);
        } catch (IOException e) {
            Log.warn("Failed to cleanup below-name data: " + e.getMessage());
        }
    }

    private Path resourceFile(File legacyDirectory, String type, String id, String action) {
        Path assetFile = findAssetResourceFile(type, id);
        if (assetFile != null) {
            return assetFile;
        }
        return legacyDirectory.exists() ? jsonFile(legacyDirectory, id, action) : null;
    }

    private Path writableResourceFile(File legacyDirectory, String type, String id, String action) {
        Path assetFile = findAssetResourceFile(type, id);
        if (assetFile != null) {
            return assetFile;
        }
        String json = getProjectMetadata("project");
        ProjectMetadataSnapshot metadata = parseProjectMetadata(json);
        ResourceEntrySnapshot resource = metadata != null ? metadata.findResource(type, id) : null;
        if (resource != null) {
            return assetResourceFile(resource);
        }
        return assetResourceFile(newResource(type, id, id, defaultFolderForType(type)));
    }

    private Path writableGraphResourceFile(FlowGraph graph, String id) {
        Path assetFile = findAssetResourceFile("flow", id);
        if (assetFile != null) {
            return assetFile;
        }
        String json = getProjectMetadata("project");
        ProjectMetadataSnapshot metadata = parseProjectMetadata(json);
        ResourceEntrySnapshot resource = metadata != null ? metadata.findGraphResource(id) : null;
        if (resource != null) {
            return assetResourceFile(resource);
        }
        String type = graph != null && graph.isFunction() ? "function" : "flow";
        return assetResourceFile(newResource(type, id, id, defaultFolderForType(type)));
    }

    private Path findAssetResourceFile(String type, String id) {
        Path root = assetsDir.toPath();
        if (!Files.exists(root)) {
            return null;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(path -> Files.isRegularFile(path) && assetFileMatches(type, id, path.getFileName().toString()))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            Log.warn("Failed to search assets for " + type + ": " + id + " - " + e.getMessage());
            return null;
        }
    }

    private Path assetResourceFile(ResourceEntrySnapshot resource) {
        Path folder = safeAssetFolder(resource.path);
        return folder.resolve(assetResourceFileName(resource.type, resource.id));
    }

    private String assetResourceFileName(String type, String id) {
        return type + "__" + id + ".json";
    }

    private boolean graphHasCommandStartNode(FlowGraph graph) {
        if (graph == null || graph.getNodes() == null) {
            return false;
        }
        return graph.getNodes().values().stream()
                .anyMatch(node -> node != null && ("event.resync.command".equals(node.getType()) || "event:resync_command".equals(node.getType())));
    }

    private boolean assetFileMatches(String type, String id, String fileName) {
        if (!fileName.endsWith("__" + id + ".json")) {
            return false;
        }
        if (!"flow".equals(type)) {
            return fileName.equals(assetResourceFileName(type, id));
        }
        return fileName.equals(assetResourceFileName("flow", id))
                || fileName.equals(assetResourceFileName("function", id))
                || fileName.equals(assetResourceFileName("command", id));
    }

    private Path safeAssetFolder(String path) {
        Path root = assetsDir.toPath().toAbsolutePath().normalize();
        Path result = root;
        String normalized = normalizeAssetPath(path);
        if (!normalized.isBlank()) {
            for (String part : normalized.split("/")) {
                result = result.resolve(part);
            }
        }
        Path target = result.normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Unsafe assets folder: " + path);
        }
        return target;
    }

    private String normalizeAssetPath(String path) {
        String normalized = path != null ? path.replace('\\', '/').replaceAll("/+", "/").trim() : "";
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("Unsafe assets path: " + path);
        }
        return normalized;
    }

    private String lastAssetSegment(String path) {
        String normalized = normalizeAssetPath(path);
        int separator = normalized.lastIndexOf('/');
        return separator >= 0 ? normalized.substring(separator + 1) : normalized;
    }

    private void deleteResourceFiles(File legacyDirectory, String type, String id) throws IOException {
        if (legacyDirectory.exists()) {
            Path legacyFile = jsonFile(legacyDirectory, id, "delete " + type);
            if (legacyFile != null && Files.exists(legacyFile)) {
                StorageSafety.deleteIfExists(legacyFile);
            }
        }
        Path assetFile = findAssetResourceFile(type, id);
        if (assetFile != null) {
            deleteAssetFile(assetFile);
        }
    }

    private void deleteAssetFile(Path file) throws IOException {
        Path root = assetsDir.toPath().toAbsolutePath().normalize();
        Path target = file.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.getParent() == null || !target.getFileName().toString().endsWith(".json")) {
            throw new IOException("Unsafe assets delete target: " + file);
        }
        Files.deleteIfExists(target);
    }

    private void syncAssetsFromProjectMetadata(String json) {
        ProjectMetadataSnapshot metadata = parseProjectMetadata(json);
        if (metadata == null) {
            return;
        }
        try {
            Files.createDirectories(assetsDir.toPath());
            for (FolderEntrySnapshot folder : metadata.folders()) {
                Files.createDirectories(safeAssetFolder(folder.path));
            }
            moveResourceTypeToAssets(metadata, flowDir, "flow");
            moveResourceTypeToAssets(metadata, new File(configFile.getParentFile(), "custom-content"), "custom_content");
            moveResourceTypeToAssets(metadata, guiDir, "gui");
            moveResourceTypeToAssets(metadata, scoreboardDir, "scoreboard");
            moveResourceTypeToAssets(metadata, tabDir, "tab");
            moveResourceTypeToAssets(metadata, new File(configFile.getParentFile(), "worldgen-projects"), "worldgen");
        } catch (IOException | IllegalArgumentException e) {
            Log.warn("Failed to sync assets: " + e.getMessage());
        }
    }

    private void migrateLegacyAssets() {
        try {
            ProjectMetadataSnapshot metadata = loadMigrationMetadata();
            Set<String> commandFlowIds = loadCommandFlowIds();
            boolean changed = ensureDefaultFolders(metadata);
            changed |= reconcileExistingAssetResources(metadata, commandFlowIds);
            changed |= reclassifyCommandResources(metadata, commandFlowIds);
            changed |= migrateLegacyFlows(metadata, commandFlowIds);
            changed |= migrateLegacyResources(metadata, guiDir, "gui", "GUIs");
            changed |= migrateLegacyResources(metadata, scoreboardDir, "scoreboard", "Customization/Scoreboards");
            changed |= migrateLegacyResources(metadata, tabDir, "tab", "Customization/Tabs");
            changed |= migrateLegacyCustomContent(metadata);
            changed |= migrateLegacyResources(metadata, new File(configFile.getParentFile(), "worldgen-projects"), "worldgen", "WorldGen");
            if (changed || !Files.exists(assetsDir.toPath().resolve("project.json"))) {
                String json = gson.toJson(metadata);
                StorageSafety.writeUtf8Atomic(assetsDir.toPath().resolve("project.json"), json);
                projectMetadataCache.put("project", json);
            }
            syncAssetsFromProjectMetadata(gson.toJson(metadata));
            cleanupLegacyAssetDirectories();
        } catch (IOException | IllegalArgumentException e) {
            Log.warn("Failed to migrate legacy assets: " + e.getMessage());
        }
    }

    private boolean reconcileExistingAssetResources(ProjectMetadataSnapshot metadata, Set<String> commandFlowIds) throws IOException {
        boolean changed = false;
        Path root = assetsDir.toPath();
        if (!Files.exists(root)) {
            return false;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                AssetResourceName asset = parseAssetResourceName(file.getFileName().toString());
                if (asset == null) {
                    continue;
                }
                String id = safeId(asset.id(), "reconcile assets");
                if (id == null) {
                    continue;
                }
                String type = asset.type();
                if (commandFlowIds.contains(id) && ("flow".equals(type) || "function".equals(type))) {
                    type = "command";
                } else if ("flow".equals(type)) {
                    FlowGraph graph = readAssetFlow(file);
                    if (graph != null && graph.isFunction()) {
                        type = "function";
                    }
                }
                String folder = assetFolderPath(root, file);
                changed |= ensureFolderPath(metadata, folder);
                changed |= ensureResource(metadata, type, id, id, folder);
            }
        }
        return changed;
    }

    private FlowGraph readAssetFlow(Path file) {
        if (file == null || !Files.exists(file)) {
            return null;
        }
        try {
            return FlowSerializer.deserialize(StorageSafety.readUtf8(file));
        } catch (IOException e) {
            Log.warn("Failed to inspect graph asset during migration: " + file.getFileName() + " - " + e.getMessage());
            return null;
        }
    }

    private AssetResourceName parseAssetResourceName(String fileName) {
        if (fileName == null || !fileName.endsWith(".json")) {
            return null;
        }
        int separator = fileName.indexOf("__");
        if (separator <= 0) {
            return null;
        }
        String type = fileName.substring(0, separator);
        if (!List.of("flow", "function", "command", "custom_content", "gui", "scoreboard", "tab", "worldgen").contains(type)) {
            return null;
        }
        String id = fileName.substring(separator + 2, fileName.length() - 5);
        return id.isBlank() ? null : new AssetResourceName(type, id);
    }

    private String assetFolderPath(Path root, Path file) {
        Path parent = file.getParent();
        if (parent == null) {
            return "";
        }
        Path relative = root.toAbsolutePath().normalize().relativize(parent.toAbsolutePath().normalize());
        String path = relative.toString().replace('\\', '/');
        return ".".equals(path) ? "" : normalizeAssetPath(path);
    }

    private boolean ensureFolderPath(ProjectMetadataSnapshot metadata, String path) {
        String normalized = normalizeAssetPath(path);
        if (normalized.isBlank()) {
            return false;
        }
        boolean changed = false;
        String parent = "";
        int order = metadata.folders().size();
        for (String part : normalized.split("/")) {
            String current = parent.isBlank() ? part : parent + "/" + part;
            changed |= ensureFolder(metadata, current, parent, order++);
            parent = current;
        }
        return changed;
    }

    private ProjectMetadataSnapshot loadMigrationMetadata() {
        Path assetMetadata = assetsDir.toPath().resolve("project.json");
        ProjectMetadataSnapshot metadata = readMigrationMetadata(assetMetadata);
        if (metadata != null) {
            return metadata;
        }
        Path legacyMetadata = jsonFile(projectMetadataDir, "project", "load migration metadata");
        metadata = legacyMetadata != null ? readMigrationMetadata(legacyMetadata) : null;
        return metadata != null ? metadata : new ProjectMetadataSnapshot();
    }

    private ProjectMetadataSnapshot readMigrationMetadata(Path file) {
        if (file == null || !Files.exists(file)) {
            return null;
        }
        try {
            return parseProjectMetadata(StorageSafety.readUtf8(file));
        } catch (IOException e) {
            Log.warn("Failed to read migration metadata: " + e.getMessage());
            return null;
        }
    }

    private boolean ensureDefaultFolders(ProjectMetadataSnapshot metadata) {
        boolean changed = false;
        changed |= ensureFolder(metadata, "Blueprints", "", 0);
        changed |= ensureFolder(metadata, "Blueprints/Flows", "Blueprints", 0);
        changed |= ensureFolder(metadata, "Blueprints/Functions", "Blueprints", 1);
        changed |= ensureFolder(metadata, "Blueprints/Commands", "Blueprints", 2);
        changed |= ensureFolder(metadata, "Content", "", 1);
        changed |= ensureFolder(metadata, "Content/Items", "Content", 0);
        changed |= ensureFolder(metadata, "Content/Armor", "Content", 1);
        changed |= ensureFolder(metadata, "Content/Blocks", "Content", 2);
        changed |= ensureFolder(metadata, "GUIs", "", 2);
        changed |= ensureFolder(metadata, "Customization", "", 3);
        changed |= ensureFolder(metadata, "Customization/Scoreboards", "Customization", 0);
        changed |= ensureFolder(metadata, "Customization/Tabs", "Customization", 1);
        changed |= ensureFolder(metadata, "Worlds", "", 4);
        changed |= ensureFolder(metadata, "WorldGen", "", 5);
        changed |= ensureFolder(metadata, "Groups", "", 6);
        return changed;
    }

    private boolean ensureFolder(ProjectMetadataSnapshot metadata, String path, String parentPath, int sortOrder) {
        if (metadata.findFolder(path) != null) {
            return false;
        }
        FolderEntrySnapshot folder = new FolderEntrySnapshot();
        folder.path = normalizeAssetPath(path);
        folder.parentPath = normalizeAssetPath(parentPath);
        folder.name = lastAssetSegment(path);
        folder.sortOrder = sortOrder;
        metadata.mutableFolders().add(folder);
        return true;
    }

    private boolean reclassifyCommandResources(ProjectMetadataSnapshot metadata, Set<String> commandFlowIds) {
        boolean changed = false;
        for (String id : commandFlowIds) {
            ResourceEntrySnapshot existing = metadata.findGraphResource(id);
            String folder = existing != null && existing.path != null && !existing.path.isBlank() ? existing.path : "Blueprints/Commands";
            if ("Blueprints/Flows".equals(folder) || "Blueprints/Functions".equals(folder)) {
                folder = "Blueprints/Commands";
            }
            if (existing != null && !"command".equals(existing.type)) {
                changed = true;
                metadata.mutableResources().removeIf(resource -> resource != null && id.equals(resource.id) && ("flow".equals(resource.type) || "function".equals(resource.type)));
            } else if (existing != null && "command".equals(existing.type) && !folder.equals(existing.path)) {
                existing.path = folder;
                changed = true;
            }
            changed |= ensureResource(metadata, "command", id, id, folder);
        }
        return changed;
    }

    private boolean migrateLegacyFlows(ProjectMetadataSnapshot metadata, Set<String> commandFlowIds) {
        boolean changed = false;
        for (String id : listSafeIds(flowDir)) {
            FlowGraph graph = readLegacyFlow(id);
            String type = commandFlowIds.contains(id) ? "command" : graph != null && graph.isFunction() ? "function" : "flow";
            ResourceEntrySnapshot existing = metadata.findGraphResource(id);
            String folder = existing != null && existing.path != null && !existing.path.isBlank() ? existing.path : defaultFolderForType(type);
            changed |= ensureResource(metadata, type, id, id, folder);
        }
        return changed;
    }

    private FlowGraph readLegacyFlow(String id) {
        Path file = jsonFile(flowDir, id, "read legacy flow");
        if (file == null || !Files.exists(file)) {
            return null;
        }
        try {
            return FlowSerializer.deserialize(StorageSafety.readUtf8(file));
        } catch (IOException e) {
            Log.warn("Failed to read legacy flow during migration: " + id + " - " + e.getMessage());
            return null;
        }
    }

    private boolean migrateLegacyResources(ProjectMetadataSnapshot metadata, File directory, String type, String folder) {
        boolean changed = false;
        for (String id : listSafeIds(directory)) {
            changed |= ensureResource(metadata, type, id, id, folder);
        }
        return changed;
    }

    private boolean migrateLegacyCustomContent(ProjectMetadataSnapshot metadata) {
        boolean changed = false;
        File directory = new File(configFile.getParentFile(), "custom-content");
        for (String id : listSafeIds(directory)) {
            String folder = switch (legacyCustomContentType(id)) {
                case "armor" -> "Content/Armor";
                case "block" -> "Content/Blocks";
                default -> "Content/Items";
            };
            changed |= ensureResource(metadata, "custom_content", id, id, folder);
        }
        return changed;
    }

    private String legacyCustomContentType(String id) {
        Path file = jsonFile(new File(configFile.getParentFile(), "custom-content"), id, "read legacy custom content");
        if (file == null || !Files.exists(file)) {
            return "item";
        }
        try {
            CustomContentSnapshot content = gson.fromJson(StorageSafety.readUtf8(file), CustomContentSnapshot.class);
            return content != null && content.type != null ? content.type.toLowerCase() : "item";
        } catch (IOException e) {
            Log.warn("Failed to read legacy custom content during migration: " + id + " - " + e.getMessage());
            return "item";
        }
    }

    private boolean ensureResource(ProjectMetadataSnapshot metadata, String type, String id, String displayName, String folder) {
        if ("flow".equals(type) && metadata.findGraphResource(id) != null) {
            return false;
        }
        boolean changed = false;
        if (!"flow".equals(type)) {
            changed = metadata.mutableResources().removeIf(resource -> resource != null && "flow".equals(resource.type) && id.equals(resource.id));
        }
        ResourceEntrySnapshot resource = metadata.findExactResource(type, id);
        if (resource != null) {
            return changed;
        }
        resource = new ResourceEntrySnapshot();
        copyResource(newResource(type, id, displayName, folder), resource);
        resource.sortOrder = metadata.resources().size();
        metadata.mutableResources().add(resource);
        return true;
    }

    private Set<String> loadCommandFlowIds() {
        Set<String> ids = new HashSet<>();
        File triggerFile = new File(configFile.getParentFile(), "triggers.json");
        if (!triggerFile.exists()) {
            return ids;
        }
        try {
            JsonElement element = gson.fromJson(Files.readString(triggerFile.toPath(), StandardCharsets.UTF_8), JsonElement.class);
            if (element == null || !element.isJsonArray()) {
                return ids;
            }
            for (JsonElement bindingElement : element.getAsJsonArray()) {
                if (bindingElement == null || !bindingElement.isJsonObject()) {
                    continue;
                }
                JsonObject binding = bindingElement.getAsJsonObject();
                JsonElement type = binding.get("type");
                JsonElement flowId = binding.get("flowId");
                if (type != null && flowId != null && "COMMAND".equalsIgnoreCase(type.getAsString()) && !flowId.getAsString().isBlank()) {
                    ids.add(flowId.getAsString());
                }
            }
        } catch (IOException e) {
            Log.warn("Failed to read command triggers during migration: " + e.getMessage());
        }
        return ids;
    }

    private void moveResourceTypeToAssets(ProjectMetadataSnapshot metadata, File legacyDirectory, String type) throws IOException {
        for (ResourceEntrySnapshot resource : metadata.resources()) {
            if (!storageTypeMatchesResource(type, resource.type)) {
                continue;
            }
            String safeId = safeId(resource.id, "sync assets");
            if (safeId == null) {
                continue;
            }
            Path target = assetResourceFile(resource);
            Files.createDirectories(target.getParent());
            Path currentAsset = findAssetResourceFile(type, safeId);
            if (currentAsset != null && !currentAsset.equals(target)) {
                Files.move(currentAsset, target, StandardCopyOption.REPLACE_EXISTING);
                continue;
            }
            if (legacyDirectory.exists()) {
                Path legacy = jsonFile(legacyDirectory, safeId, "sync assets");
                if (legacy != null && Files.exists(legacy) && !Files.exists(target)) {
                    Files.move(legacy, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private ProjectMetadataSnapshot parseProjectMetadata(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            ProjectMetadataSnapshot metadata = gson.fromJson(json, ProjectMetadataSnapshot.class);
            return metadata != null ? metadata : null;
        } catch (Exception e) {
            Log.warn("Failed to parse project metadata: " + e.getMessage());
            return null;
        }
    }

    private Path projectMetadataFile(String id, String action) {
        return assetsDir.toPath().resolve(id + ".json");
    }

    private boolean storageTypeMatchesResource(String storageType, String resourceType) {
        if (storageType.equals(resourceType)) {
            return true;
        }
        return "flow".equals(storageType) && ("function".equals(resourceType) || "command".equals(resourceType));
    }

    private ResourceEntrySnapshot newResource(String type, String id, String displayName, String folder) {
        ResourceEntrySnapshot resource = new ResourceEntrySnapshot();
        resource.type = type;
        resource.id = id;
        resource.displayName = displayName == null || displayName.isBlank() ? id : displayName;
        resource.path = normalizeAssetPath(folder);
        return resource;
    }

    private void copyResource(ResourceEntrySnapshot source, ResourceEntrySnapshot target) {
        target.type = source.type;
        target.id = source.id;
        target.displayName = source.displayName;
        target.path = source.path;
    }

    private String defaultFolderForType(String type) {
        return switch (type) {
            case "function" -> "Blueprints/Functions";
            case "command" -> "Blueprints/Commands";
            case "custom_content" -> "Content/Items";
            case "gui" -> "GUIs";
            case "scoreboard" -> "Customization/Scoreboards";
            case "tab" -> "Customization/Tabs";
            case "worldgen" -> "WorldGen";
            default -> "Blueprints/Flows";
        };
    }

    private void cleanupLegacyAssetDirectories() {
        List<File> directories = List.of(
                flowDir,
                guiDir,
                scoreboardDir,
                tabDir,
                projectMetadataDir,
                new File(configFile.getParentFile(), "custom-content"),
                new File(configFile.getParentFile(), "worldgen-projects")
        );
        for (File directory : directories) {
            deleteKnownLegacyDirectory(directory);
        }
    }

    private void deleteKnownLegacyDirectory(File directory) {
        if (directory == null || !directory.exists()) {
            return;
        }
        Path dataRoot = configFile.getParentFile().toPath().toAbsolutePath().normalize();
        Path target = directory.toPath().toAbsolutePath().normalize();
        if (!target.startsWith(dataRoot) || target.equals(dataRoot) || target.equals(assetsDir.toPath().toAbsolutePath().normalize())) {
            Log.warn("Skipped unsafe legacy directory cleanup: " + directory.getPath());
            return;
        }
        try (Stream<Path> paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            Log.warn("Failed to delete legacy assets directory: " + directory.getName() + " - " + e.getMessage());
        }
    }

    private Path jsonFile(File directory, String id, String action) {
        try {
            return StorageSafety.jsonFile(directory.toPath(), id);
        } catch (IOException | IllegalArgumentException e) {
            Log.warn("Failed to resolve " + action + ": " + id + " - " + e.getMessage());
            return null;
        }
    }

    private String safeId(String id, String action) {
        try {
            return StorageSafety.validateId(id);
        } catch (IllegalArgumentException e) {
            Log.warn("Rejected unsafe id during " + action + ": " + id);
            return null;
        }
    }

    private List<String> listSafeIds(File directory) {
        List<String> ids = new ArrayList<>();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return ids;
        }
        for (File file : files) {
            String name = file.getName();
            String id = name.substring(0, name.length() - 5);
            if (safeId(id, "list") != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private List<String> listResourceIds(File legacyDirectory, String type) {
        Set<String> ids = new HashSet<>();
        Path root = assetsDir.toPath();
        if (Files.exists(root)) {
            try (Stream<Path> paths = Files.walk(root)) {
                paths
                        .filter(path -> Files.isRegularFile(path) && assetListFileMatches(type, path.getFileName().toString()))
                        .map(path -> path.getFileName().toString())
                        .map(this::assetIdFromFileName)
                        .filter(id -> safeId(id, "list") != null)
                        .forEach(ids::add);
            } catch (IOException e) {
                Log.warn("Failed to list assets for " + type + ": " + e.getMessage());
            }
        }
        return ids.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private boolean assetListFileMatches(String type, String fileName) {
        if (!fileName.endsWith(".json")) {
            return false;
        }
        if (!"flow".equals(type)) {
            return fileName.startsWith(type + "__");
        }
        return fileName.startsWith("flow__") || fileName.startsWith("function__") || fileName.startsWith("command__");
    }

    private String assetIdFromFileName(String fileName) {
        int separator = fileName.indexOf("__");
        if (separator < 0) {
            return "";
        }
        return fileName.substring(separator + 2, fileName.length() - 5);
    }

    private record AssetResourceName(String type, String id) {
    }

    private static class ProjectMetadataSnapshot {
        private String serverId = "project";
        private List<FolderEntrySnapshot> folders = new ArrayList<>();
        private List<ResourceEntrySnapshot> resources = new ArrayList<>();

        private List<FolderEntrySnapshot> folders() {
            return folders != null ? folders : List.of();
        }

        private List<ResourceEntrySnapshot> resources() {
            return resources != null ? resources : List.of();
        }

        private List<FolderEntrySnapshot> mutableFolders() {
            if (folders == null) {
                folders = new ArrayList<>();
            }
            return folders;
        }

        private List<ResourceEntrySnapshot> mutableResources() {
            if (resources == null) {
                resources = new ArrayList<>();
            }
            return resources;
        }

        private FolderEntrySnapshot findFolder(String path) {
            for (FolderEntrySnapshot folder : folders()) {
                if (folder != null && path.equals(folder.path)) {
                    return folder;
                }
            }
            return null;
        }

        private ResourceEntrySnapshot findResource(String type, String id) {
            for (ResourceEntrySnapshot resource : resources()) {
                if (resource != null && id.equals(resource.id) && (type.equals(resource.type) || "flow".equals(type) && ("function".equals(resource.type) || "command".equals(resource.type)))) {
                    return resource;
                }
            }
            return null;
        }

        private ResourceEntrySnapshot findExactResource(String type, String id) {
            for (ResourceEntrySnapshot resource : resources()) {
                if (resource != null && type.equals(resource.type) && id.equals(resource.id)) {
                    return resource;
                }
            }
            return null;
        }

        private ResourceEntrySnapshot findGraphResource(String id) {
            for (ResourceEntrySnapshot resource : resources()) {
                if (resource != null && id.equals(resource.id) && ("flow".equals(resource.type) || "function".equals(resource.type) || "command".equals(resource.type))) {
                    return resource;
                }
            }
            return null;
        }
    }

    private static class FolderEntrySnapshot {
        private String path = "";
        private String parentPath = "";
        private String name = "";
        private int sortOrder;
        private boolean collapsed;
    }

    private static class ResourceEntrySnapshot {
        private String type = "";
        private String id = "";
        private String displayName = "";
        private String path = "";
        private int sortOrder;
    }

    private static class CustomContentSnapshot {
        private String type = "";
    }

}
