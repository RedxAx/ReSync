package restudio.resync.flow;

import org.bukkit.plugin.java.JavaPlugin;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.GuiDefinition;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class FlowStorage {
    private final File flowDir;
    private final File guiDir;
    private final Map<UUID, FlowGraph> graphCache = new ConcurrentHashMap<>();
    private final Map<String, GuiDefinition> guiCache = new ConcurrentHashMap<>();

    public FlowStorage(JavaPlugin plugin) {
        this.flowDir = new File(plugin.getDataFolder(), "flows");
        this.guiDir = new File(plugin.getDataFolder(), "guis");
        if (!flowDir.exists()) {
            flowDir.mkdirs();
        }
        if (!guiDir.exists()) {
            guiDir.mkdirs();
        }
    }

    public FlowGraph getGraph(String id) {
        try {
            UUID uuid = UUID.fromString(id);
            if (graphCache.containsKey(uuid)) return graphCache.get(uuid);
            
            File file = new File(flowDir, uuid.toString() + ".json");
            if (file.exists()) {
                try {
                    String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                    FlowGraph graph = FlowSerializer.deserialize(json);
                    graphCache.put(uuid, graph);
                    return graph;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } catch (IllegalArgumentException e) {
        }
        return null;
    }

    public void saveGraph(FlowGraph graph) {
        graphCache.put(graph.getId(), graph);
        File file = new File(flowDir, graph.getId().toString() + ".json");
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
            UUID uuid = UUID.fromString(id);
            graphCache.remove(uuid);
            File file = new File(flowDir, uuid.toString() + ".json");
            if (file.exists()) {
                Files.delete(file.toPath());
            }
        } catch (IllegalArgumentException | IOException e) {
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

    public Map<UUID, FlowGraph> getGraphCache() {
        return graphCache;
    }

    public java.util.List<String> listFlowIds() {
        java.util.List<String> flowIds = new java.util.ArrayList<>();
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

    public Map<String, GuiDefinition> getGuiCache() {
        return guiCache;
    }

    public void clearCache() {
        graphCache.clear();
        guiCache.clear();
    }
}
