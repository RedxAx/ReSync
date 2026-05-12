package restudio.resync.flow;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public class FlowStorage {
    private final File flowDir;
    private final File guiDir;
    private final File scoreboardDir;
    private final File tabDir;
    private final File projectMetadataDir;
    private final File configFile;
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
        this.configFile = new File(dataFolder, "config.properties");
        if (!flowDir.exists()) {
            flowDir.mkdirs();
        }
        if (!guiDir.exists()) {
            guiDir.mkdirs();
        }
        if (!scoreboardDir.exists()) {
            scoreboardDir.mkdirs();
        }
        if (!tabDir.exists()) {
            tabDir.mkdirs();
        }
        if (!projectMetadataDir.exists()) {
            projectMetadataDir.mkdirs();
        }
        loadDefaultScoreboard();
        loadDefaultTab();
        loadTabRefreshConfig();
        cleanupBelowNameData();
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
        
        Path file = jsonFile(flowDir, safeId, "load flow");
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
        Path file = jsonFile(flowDir, safeId, "save flow");
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
            Path file = jsonFile(flowDir, safeId, "delete flow");
            if (file != null) {
                StorageSafety.deleteIfExists(file);
            }
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
        
        Path file = jsonFile(guiDir, safeId, "load GUI");
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
        Path file = jsonFile(guiDir, safeId, "save GUI");
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
            Path file = jsonFile(guiDir, safeId, "delete GUI");
            if (file != null) {
                StorageSafety.deleteIfExists(file);
            }
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

        Path file = jsonFile(scoreboardDir, safeId, "load scoreboard");
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
        Path file = jsonFile(scoreboardDir, safeId, "save scoreboard");
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
            Path file = jsonFile(scoreboardDir, safeId, "delete scoreboard");
            if (file != null) {
                StorageSafety.deleteIfExists(file);
            }
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

        Path file = jsonFile(tabDir, safeId, "load tab");
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
        Path file = jsonFile(tabDir, safeId, "save tab");
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
            Path file = jsonFile(tabDir, safeId, "delete tab");
            if (file != null) {
                StorageSafety.deleteIfExists(file);
            }
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
        Path file = jsonFile(projectMetadataDir, safeId, "load project metadata");
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
        Path file = jsonFile(projectMetadataDir, safeId, "save project metadata");
        if (file == null) {
            throw new IllegalStateException("Failed to resolve project metadata file");
        }
        try {
            StorageSafety.writeUtf8Atomic(file, json);
            projectMetadataCache.put(safeId, json);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save project metadata: " + safeId, e);
        }
    }

    public void deleteProjectMetadata(String id) {
        String safeId = projectMetadataId(id);
        try {
            Path file = jsonFile(projectMetadataDir, safeId, "delete project metadata");
            if (file != null) {
                StorageSafety.deleteIfExists(file);
            }
            projectMetadataCache.remove(safeId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete project metadata: " + safeId, e);
        }
    }

    public List<String> listProjectMetadataIds() {
        return listSafeIds(projectMetadataDir);
    }

    public List<String> listFlowIds() {
        return listSafeIds(flowDir);
    }

    public boolean hasStoredGraphVersion(String id) {
        String safeId = safeId(id, "inspect flow version");
        if (safeId == null) {
            return false;
        }
        Path file = jsonFile(flowDir, safeId, "inspect flow version");
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
        return listSafeIds(guiDir);
    }

    public Map<String, GuiDefinition> getGuiCache() {
        return guiCache;
    }

    public List<String> listScoreboardIds() {
        return listSafeIds(scoreboardDir);
    }

    public Map<String, ScoreboardDefinition> getScoreboardCache() {
        return scoreboardCache;
    }

    public List<String> listTabIds() {
        return listSafeIds(tabDir);
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
}
