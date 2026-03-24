package restudio.resync.flow;

import org.bukkit.plugin.java.JavaPlugin;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.GuiDefinition;
import restudio.flow.data.ScoreboardDefinition;
import restudio.flow.data.TabDefinition;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    private volatile String defaultScoreboardId;
    private volatile boolean defaultScoreboardUsePapi = true;
    private volatile String defaultTabId;
    private volatile boolean defaultTabUsePapi = true;
    private volatile int tabRefreshIntervalTicks = 20;

    public FlowStorage(JavaPlugin plugin) {
        this.flowDir = new File(plugin.getDataFolder(), "flows");
        this.guiDir = new File(plugin.getDataFolder(), "guis");
        this.scoreboardDir = new File(plugin.getDataFolder(), "scoreboards");
        this.tabDir = new File(plugin.getDataFolder(), "tabs");
        this.configFile = new File(plugin.getDataFolder(), "config.properties");
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
        loadDefaultScoreboard();
        loadDefaultTab();
        loadTabRefreshConfig();
        cleanupBelowNameData();
    }

    public FlowGraph getGraph(String id) {
        if (graphCache.containsKey(id)) return graphCache.get(id);
        
        File file = new File(flowDir, id + ".json");
        if (file.exists()) {
            try {
                String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                FlowGraph graph = FlowSerializer.deserialize(json);
                graphCache.put(id, graph);
                return graph;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    public void saveGraph(FlowGraph graph) {
        graphCache.put(graph.getId(), graph);
        File file = new File(flowDir, graph.getId() + ".json");
        try {
            String json = FlowSerializer.serialize(graph);
            Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void deleteGraph(String id) {
        if (id == null) {
            return;
        }
        try {
            graphCache.remove(id);
            File file = new File(flowDir, id + ".json");
            if (file.exists()) {
                Files.delete(file.toPath());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public GuiDefinition getGui(String id) {
        if (guiCache.containsKey(id)) return guiCache.get(id);
        
        File file = new File(guiDir, id + ".json");
        if (file.exists()) {
            try {
                String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                GuiDefinition gui = FlowSerializer.deserializeGui(json);
                if (gui != null && (gui.getId() == null || gui.getId().isBlank())) {
                    gui.setId(id);
                }
                guiCache.put(id, gui);
                return gui;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    public void saveGui(GuiDefinition gui) {
        guiCache.put(gui.getId(), gui);
        File file = new File(guiDir, gui.getId() + ".json");
        try {
            String json = FlowSerializer.serializeGui(gui);
            Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void deleteGui(String id) {
        if (id == null) {
            return;
        }
        try {
            guiCache.remove(id);
            File file = new File(guiDir, id + ".json");
            if (file.exists()) {
                Files.delete(file.toPath());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public ScoreboardDefinition getScoreboard(String id) {
        if (scoreboardCache.containsKey(id)) return scoreboardCache.get(id);

        File file = new File(scoreboardDir, id + ".json");
        if (file.exists()) {
            try {
                String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                ScoreboardDefinition scoreboard = FlowSerializer.deserializeScoreboard(json);
                if (scoreboard != null && (scoreboard.getId() == null || scoreboard.getId().isBlank())) {
                    scoreboard.setId(id);
                }
                scoreboardCache.put(id, scoreboard);
                return scoreboard;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    public void saveScoreboard(ScoreboardDefinition scoreboard) {
        scoreboardCache.put(scoreboard.getId(), scoreboard);
        File file = new File(scoreboardDir, scoreboard.getId() + ".json");
        try {
            String json = FlowSerializer.serializeScoreboard(scoreboard);
            Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void deleteScoreboard(String id) {
        if (id == null) {
            return;
        }
        try {
            scoreboardCache.remove(id);
            File file = new File(scoreboardDir, id + ".json");
            if (file.exists()) {
                Files.delete(file.toPath());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public TabDefinition getTab(String id) {
        if (tabCache.containsKey(id)) return tabCache.get(id);

        File file = new File(tabDir, id + ".json");
        if (file.exists()) {
            try {
                String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                TabDefinition tab = FlowSerializer.deserializeTab(json);
                if (tab != null && (tab.getId() == null || tab.getId().isBlank())) {
                    tab.setId(id);
                }
                tabCache.put(id, tab);
                return tab;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    public void saveTab(TabDefinition tab) {
        tabCache.put(tab.getId(), tab);
        File file = new File(tabDir, tab.getId() + ".json");
        try {
            String json = FlowSerializer.serializeTab(tab);
            Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void deleteTab(String id) {
        if (id == null) {
            return;
        }
        try {
            tabCache.remove(id);
            File file = new File(tabDir, id + ".json");
            if (file.exists()) {
                Files.delete(file.toPath());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Map<String, FlowGraph> getGraphCache() {
        return graphCache;
    }

    public List<String> listFlowIds() {
        List<String> flowIds = new ArrayList<>();
        File[] files = flowDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return flowIds;
        }
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".json")) {
                flowIds.add(name.substring(0, name.length() - 5));
            }
        }
        return flowIds;
    }

    public List<String> listGuiIds() {
        List<String> guiIds = new ArrayList<>();
        File[] files = guiDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return guiIds;
        }
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".json")) {
                guiIds.add(name.substring(0, name.length() - 5));
            }
        }
        return guiIds;
    }

    public Map<String, GuiDefinition> getGuiCache() {
        return guiCache;
    }

    public List<String> listScoreboardIds() {
        List<String> scoreboardIds = new ArrayList<>();
        File[] files = scoreboardDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return scoreboardIds;
        }
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".json")) {
                scoreboardIds.add(name.substring(0, name.length() - 5));
            }
        }
        return scoreboardIds;
    }

    public Map<String, ScoreboardDefinition> getScoreboardCache() {
        return scoreboardCache;
    }

    public List<String> listTabIds() {
        List<String> tabIds = new ArrayList<>();
        File[] files = tabDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return tabIds;
        }
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".json")) {
                tabIds.add(name.substring(0, name.length() - 5));
            }
        }
        return tabIds;
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

    public void clearCache() {
        graphCache.clear();
        guiCache.clear();
        scoreboardCache.clear();
        tabCache.clear();
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
            e.printStackTrace();
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
            e.printStackTrace();
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
            e.printStackTrace();
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
            e.printStackTrace();
        }
        return properties;
    }

    private void storeConfigProperties(Properties properties) {
        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            properties.store(fos, "ReSync v2 Configuration");
        } catch (IOException e) {
            e.printStackTrace();
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
            e.printStackTrace();
        }
    }
}
