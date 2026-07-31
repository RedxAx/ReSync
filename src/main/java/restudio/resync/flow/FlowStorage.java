package restudio.resync.flow;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.plugin.java.JavaPlugin;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.GuiDefinition;
import restudio.flow.data.ScoreboardDefinition;
import restudio.flow.data.TabDefinition;
import restudio.resync.Log;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.resources.AssetFileFormat;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.storage.AssetIntegrityService;
import restudio.resync.storage.AssetTransactionManager;
import restudio.resync.storage.StorageSafety;
import restudio.resync.flow.validation.FlowGraphValidationException;
import restudio.resync.flow.validation.FlowGraphValidationResult;
import restudio.resync.flow.validation.FlowGraphValidator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.function.Consumer;

public class FlowStorage {
    private final File flowDir;
    private final File guiDir;
    private final File scoreboardDir;
    private final File tabDir;
    private final File projectMetadataDir;
    private final File assetsDir;
    private final File configFile;
    private final Gson gson = new Gson();
    private final AssetTransactionManager assetTransactions;
    private final AssetIntegrityService assetIntegrity;
    private static final Set<String> GRAPH_TYPES = Set.of("flow", "function", "command");
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
    private final Map<String, Path> assetFileIndex = new ConcurrentHashMap<>();
    private volatile boolean assetFileIndexReady;
    private volatile String defaultScoreboardId;
    private volatile boolean defaultScoreboardUsePapi = true;
    private volatile String defaultTabId;
    private volatile boolean defaultTabUsePapi = true;
    private volatile int tabRefreshIntervalTicks = 20;
    private FlowGraphValidator graphValidator;
    private Consumer<String> graphChangeListener;

    public FlowStorage(JavaPlugin plugin) {
        this(plugin.getDataFolder());
    }

    public FlowStorage(File dataFolder) {
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
        try {
            this.assetTransactions = new AssetTransactionManager(assetsDir.toPath(), gson);
            this.assetIntegrity = new AssetIntegrityService(assetsDir.toPath());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to initialize asset transactions", exception);
        }
        loadDefaultScoreboard();
        loadDefaultTab();
        loadTabRefreshConfig();
        cleanupBelowNameData();
        migrateLegacyAssets();
    }

    public FlowGraph getGraph(String id) {
        return getGraph("", id);
    }

    public FlowGraph getGraph(String type, String id) {
        String safeId = safeId(id, "load flow");
        if (safeId == null) {
            return null;
        }
        String requestedType = type != null && Set.of("flow", "function", "command").contains(type) ? type : "";
        Path file = requestedType.isBlank() ? resourceFile(flowDir, "flow", safeId, "load flow") : findAssetResourceFile(requestedType, safeId);
        if (file == null || !Files.exists(file)) {
            return null;
        }
        String actualType = AssetFileFormat.readResourceType(file);
        if (!requestedType.isBlank() && !requestedType.equals(actualType)) {
            return null;
        }
        String cacheKey = assetIndexKey(actualType.isBlank() ? "flow" : actualType, safeId);
        FlowGraph cached = graphCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        try {
            if (!AssetFileFormat.verify(file)) {
                Log.warn("Flow integrity check failed: " + safeId);
                return null;
            }
            String json = StorageSafety.readUtf8(file);
            FlowGraph graph = FlowSerializer.deserialize(json);
            applyResourceIdentity(graph, file);
            graphCache.put(cacheKey, graph);
            return graph;
        } catch (IOException | RuntimeException e) {
            Log.warn("Failed to load flow: " + safeId + " - " + e.getMessage());
        }
        return null;
    }

    public Path getAssetsPath() {
        return assetsDir.toPath();
    }

    public AssetIntegrityService.HealthReport getDurabilityHealth() {
        return assetIntegrity.scan(assetTransactions.getRecoveredTransactions());
    }

    public AssetTransactionManager.RestorePreview previewAssetRestore(String transactionId) throws IOException {
        return assetTransactions.previewRestore(transactionId);
    }

    public void restoreAssetSnapshot(String transactionId, String mutationId) throws IOException {
        assetTransactions.restore(transactionId, mutationId);
        graphCache.clear();
        projectMetadataCache.clear();
        invalidateAssetFileIndex();
    }

    public FlowGraph reloadGraph(String id) {
        return reloadGraph("", id);
    }

    public FlowGraph reloadGraph(String type, String id) {
        String safeId = safeId(id, "reload flow");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid flow id");
        }
        invalidateAssetFileIndex();
        String requestedType = type != null && Set.of("flow", "function", "command").contains(type) ? type : "";
        Path file = requestedType.isBlank() ? resourceFile(flowDir, "flow", safeId, "reload flow") : findAssetResourceFile(requestedType, safeId);
        if (file == null || !Files.exists(file)) {
            if (requestedType.isBlank()) {
                evictGraphCache(safeId);
            } else {
                graphCache.remove(assetIndexKey(requestedType, safeId));
            }
            return null;
        }
        try {
            FlowGraph graph = FlowSerializer.deserialize(StorageSafety.readUtf8(file));
            applyResourceIdentity(graph, file);
            requireValidGraph(graph);
            if (requestedType.isBlank()) {
                evictGraphCache(safeId);
            }
            graphCache.put(assetIndexKey(graphResourceType(graph), safeId), graph);
            notifyGraphChanged(safeId);
            return graph;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to reload flow: " + safeId, exception);
        }
    }

    public void saveGraph(FlowGraph graph) {
        saveGraph(graph, "");
    }

    public void reclassifyGraph(FlowGraph graph, String targetType) {
        if (!Set.of("flow", "function", "command").contains(targetType)) {
            throw new IllegalArgumentException("Unsupported graph type: " + targetType);
        }
        saveGraph(graph, targetType);
    }

    public void restoreGraph(FlowGraph graph) {
        if (graph == null || graph.getId() == null) {
            return;
        }
        FlowGraph current = getGraph(graphResourceType(graph), graph.getId());
        graph.setResourceRevision(current != null ? current.getResourceRevision() : 0L);
        graph.setResourceMutationId("");
        saveGraph(graph);
    }

    private synchronized void saveGraph(FlowGraph graph, String forcedType) {
        if (graph == null) {
            throw new IllegalArgumentException("Flow is required");
        }
        requireValidGraph(graph);
        String safeId = safeId(graph.getId(), "save flow");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid flow id");
        }
        String requestedType = !forcedType.isBlank() ? forcedType : graph.getResourceType();
        if (!Set.of("flow", "function", "command").contains(requestedType)) {
            requestedType = graphResourceTypeForWrite(graph);
        }
        if (forcedType.isBlank() && findAssetResourceFile(requestedType, safeId) == null) {
            for (String graphType : Set.of("flow", "function", "command")) {
                if (!graphType.equals(requestedType) && findAssetResourceFile(graphType, safeId) != null) {
                    throw new IllegalArgumentException("Graph ID is already used by a " + graphType + ": " + safeId);
                }
            }
        }
        Path file = writableGraphResourceFile(graph, safeId);
        if (file == null) {
            throw new IllegalStateException("Failed to resolve flow file");
        }
        try {
            boolean existingFile = Files.exists(file);
            String existingType = existingFile ? AssetFileFormat.readResourceType(file) : "";
            String type = !forcedType.isBlank() || !Set.of("flow", "function", "command").contains(existingType) ? requestedType : existingType;
            long currentRevision = existingFile ? AssetFileFormat.readRevision(file) : 0L;
            String currentMutationId = existingFile ? AssetFileFormat.readMutationId(file) : "";
            FlowGraph current = existingFile ? FlowSerializer.deserialize(StorageSafety.readUtf8(file)) : null;
            if (current != null) {
                applyResourceIdentity(current, file);
            }
            if (sameGraphContent(graph, current)) {
                applyCurrentIdentity(graph, current);
                return;
            }
            if (existingFile && graph.getResourceRevision() > 0L && graph.getResourceRevision() != currentRevision) {
                throw new ResourceRevisionConflictException(safeId, graph.getResourceRevision(), currentRevision);
            }
            if (!existingFile && graph.getResourceRevision() > 0L) {
                graph.setResourceRevision(0L);
                graph.setResourceHash("");
                graph.setResourceMutationId("");
            }
            if (!type.equals(existingType) && !existingType.isBlank()) {
                String folder = defaultFolderForType(type);
                file = safeAssetFolder(folder).resolve(AssetFileFormat.idOnlyFileName(safeId));
            }
            file = collisionSafeWriteFile(type, safeId, file);
            long revision = currentRevision + 1L;
            String mutationId = graph.getResourceMutationId().isBlank() || graph.getResourceMutationId().equals(currentMutationId)
                ? UUID.randomUUID().toString()
                : graph.getResourceMutationId();
            graph.setFunction("function".equals(type));
            graph.setResourceType(type);
            graph.setResourceRevision(revision);
            graph.setResourceHash("");
            graph.setResourceMutationId(mutationId);
            String json = FlowSerializer.serialize(graph);
            String assetJson = AssetFileFormat.withResourceIdentity(json, type, revision, mutationId);
            Map<Path, String> writes = new LinkedHashMap<>();
            writes.put(file, assetJson);
            boolean reclassifying = !forcedType.isBlank();
            String metadataJson = metadataWithResourcePath(type, safeId, assetFolderPath(assetsDir.toPath(), file), reclassifying);
            if (metadataJson != null) {
                writes.put(assetsDir.toPath().resolve("project.json"), metadataJson);
            }
            assetTransactions.commit(writes, mutationId);
            clearGraphTombstone(type, safeId);
            graph.setResourceHash(AssetFileFormat.contentHash(assetJson));
            if (metadataJson != null) {
                projectMetadataCache.put("project", metadataJson);
            }
            deleteGraphAssetDuplicates(reclassifying ? Set.of("flow", "function", "command") : Set.of(type), safeId, file);
            invalidateAssetFileIndex();
            evictGraphCache(safeId);
            graphCache.put(assetIndexKey(type, safeId), graph);
            notifyGraphChanged(safeId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save flow: " + safeId, e);
        }
    }

    private boolean sameGraphContent(FlowGraph incoming, FlowGraph current) {
        if (incoming == null || current == null) {
            return false;
        }
        JsonObject incomingJson = gson.fromJson(FlowSerializer.serialize(incoming), JsonObject.class);
        JsonObject currentJson = gson.fromJson(FlowSerializer.serialize(current), JsonObject.class);
        for (String field : List.of("resourceRevision", "resourceHash", "resourceMutationId")) {
            incomingJson.remove(field);
            currentJson.remove(field);
        }
        return incomingJson.equals(currentJson);
    }

    private void applyCurrentIdentity(FlowGraph graph, FlowGraph current) {
        graph.setFunction(current.isFunction());
        graph.setResourceType(current.getResourceType());
        graph.setResourceRevision(current.getResourceRevision());
        graph.setResourceHash(current.getResourceHash());
        graph.setResourceMutationId(current.getResourceMutationId());
    }

    public void setGraphValidator(FlowGraphValidator graphValidator) {
        this.graphValidator = graphValidator;
    }

    public void setGraphChangeListener(Consumer<String> graphChangeListener) {
        this.graphChangeListener = graphChangeListener;
    }

    public FlowGraphValidationResult validateGraph(FlowGraph graph) {
        if (graphValidator == null) {
            return new FlowGraphValidationResult(List.of());
        }
        return graphValidator.validate(graph);
    }

    public void requireValidGraph(FlowGraph graph) {
        FlowGraphValidationResult result = validateGraph(graph);
        if (!result.valid()) {
            throw new FlowGraphValidationException(result);
        }
        if ("command".equals(graphResourceType(graph))) {
            long commandStarts = graph.getNodes() != null ? graph.getNodes().values().stream()
                .filter(node -> node != null && ("event.resync.command".equals(node.getType()) || "event:resync_command".equals(node.getType())))
                .count() : 0L;
            if (commandStarts != 1L) {
                throw new IllegalArgumentException("Command graphs require exactly one Command Start node");
            }
        }
    }

    public void deleteGraph(String id) {
        String safeId = safeId(id, "delete flow");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid flow id");
        }
        FlowGraph graph = getGraph(safeId);
        deleteGraph(graph != null ? graphResourceType(graph) : "flow", safeId);
    }

    public void deleteGraph(String type, String id) {
        String safeId = safeId(id, "delete flow");
        if (safeId == null || type == null || !Set.of("flow", "function", "command").contains(type)) {
            throw new IllegalArgumentException("Invalid flow identity");
        }
        FlowGraph graph = getGraph(type, safeId);
        if (graph != null && "function".equals(type)) {
            List<FlowFunctionReference> references = findFunctionReferences(safeId).stream()
                .filter(reference -> !safeId.equals(reference.callerGraphId()))
                .toList();
            if (!references.isEmpty()) {
                throw new FlowFunctionInUseException(safeId, references);
            }
        }
        deleteGraphFiles(type, safeId);
    }

    public void forceDeleteGraph(String id) {
        String safeId = safeId(id, "delete flow");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid flow id");
        }
        FlowGraph graph = getGraph(safeId);
        deleteGraphFiles(graph != null ? graphResourceType(graph) : "flow", safeId);
    }

    public List<FlowFunctionReference> findFunctionReferences(String functionId) {
        String safeFunctionId = safeId(functionId, "analyze function references");
        if (safeFunctionId == null) {
            return List.of();
        }
        String expectedType = CustomFunctionNodeDefinitions.NODE_PREFIX + safeFunctionId;
        List<FlowFunctionReference> references = new ArrayList<>();
        for (String graphId : listFlowIds()) {
            FlowGraph graph = getGraph(graphId);
            if (graph == null || graph.getNodes() == null) {
                continue;
            }
            for (Map.Entry<String, FlowNode> entry : graph.getNodes().entrySet()) {
                if (entry.getValue() != null && expectedType.equals(entry.getValue().getType())) {
                    references.add(new FlowFunctionReference(graphId, entry.getKey()));
                }
            }
        }
        references.sort(Comparator.comparing(FlowFunctionReference::callerGraphId).thenComparing(FlowFunctionReference::nodeId));
        return List.copyOf(references);
    }

    private void deleteGraphFiles(String type, String safeId) {
        try {
            Path stored = findAssetResourceFile(type, safeId);
            long revision = stored != null ? AssetFileFormat.readRevision(stored) : 0L;
            writeGraphTombstone(type, safeId, revision + 1L);
            deleteResourceFiles(flowDir, type, safeId);
            removeResourceMetadata(type, safeId);
            invalidateAssetFileIndex();
            graphCache.remove(assetIndexKey(type, safeId));
            notifyGraphChanged(safeId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete flow: " + safeId, e);
        }
    }

    private void notifyGraphChanged(String graphId) {
        if (graphChangeListener != null && graphId != null && !graphId.isBlank()) {
            graphChangeListener.accept(graphId);
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
            file = collisionSafeWriteFile("gui", safeId, file);
            String json = FlowSerializer.serializeGui(gui);
            StorageSafety.writeUtf8Atomic(file, AssetFileFormat.withResourceType(json, "gui"));
            deleteAssetDuplicates("gui", safeId, file);
            assetFileIndex.put(assetIndexKey("gui", safeId), file);
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
            invalidateAssetFileIndex();
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
            file = collisionSafeWriteFile("scoreboard", safeId, file);
            String json = FlowSerializer.serializeScoreboard(scoreboard);
            StorageSafety.writeUtf8Atomic(file, AssetFileFormat.withResourceType(json, "scoreboard"));
            deleteAssetDuplicates("scoreboard", safeId, file);
            assetFileIndex.put(assetIndexKey("scoreboard", safeId), file);
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
            invalidateAssetFileIndex();
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
            file = collisionSafeWriteFile("tab", safeId, file);
            String json = FlowSerializer.serializeTab(tab);
            StorageSafety.writeUtf8Atomic(file, AssetFileFormat.withResourceType(json, "tab"));
            deleteAssetDuplicates("tab", safeId, file);
            assetFileIndex.put(assetIndexKey("tab", safeId), file);
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
            invalidateAssetFileIndex();
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
        return projectMetadataCache.get(safeId);
    }

    public void saveProjectMetadata(String json) {
        String safeId = projectMetadataId("project");
        Path file = projectMetadataFile(safeId, "save project metadata");
        if (file == null) {
            throw new IllegalStateException("Failed to resolve project metadata file");
        }
        try {
            syncAssetsFromProjectMetadata(json);
            ProjectMetadataSnapshot metadata = parseProjectMetadata(json);
            String normalized = metadata != null && pruneMissingAssetResources(metadata) ? gson.toJson(metadata) : json;
            StorageSafety.writeUtf8Atomic(file, normalized);
            projectMetadataCache.put(safeId, normalized);
            invalidateAssetFileIndex();
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
        return listGraphIds("flow");
    }

    public List<String> listGraphIds(String resourceType) {
        if (!Set.of("flow", "function", "command").contains(resourceType)) {
            throw new IllegalArgumentException("Unknown graph resource type: " + resourceType);
        }
        Map<String, Path> index = assetFiles();
        return listResourceIds(flowDir, resourceType).stream().filter(id -> index.containsKey(assetIndexKey(resourceType, id))).toList();
    }

    public String getGraphResourceType(String id) {
        String safeId = safeId(id, "resolve graph resource type");
        if (safeId == null) {
            return "";
        }
        Map<String, Path> index = assetFiles();
        for (String type : List.of("flow", "function", "command")) {
            if (index.containsKey(assetIndexKey(type, safeId))) {
                return type;
            }
        }
        ProjectMetadataSnapshot metadata = parseProjectMetadata(getProjectMetadata("project"));
        ResourceEntrySnapshot resource = metadata != null ? metadata.findGraphResource(safeId) : null;
        if (resource != null && resource.type != null && Set.of("flow", "function", "command").contains(resource.type)) {
            return resource.type;
        }
        FlowGraph graph = getGraph(safeId);
        return graph != null ? graphResourceType(graph) : "";
    }

    public String graphResourceType(FlowGraph graph) {
        if (graph == null) {
            return "";
        }
        if (GRAPH_TYPES.contains(graph.getResourceType())) {
            return graph.getResourceType();
        }
        if (graphHasCommandStartNode(graph)) {
            return "command";
        }
        return graph.isFunction() ? "function" : "flow";
    }

    public boolean isExecutionAuthorized(FlowGraph graph) {
        if (graph == null) {
            return true;
        }
        String type = graphResourceType(graph);
        if (!GRAPH_TYPES.contains(type)) {
            return true;
        }
        String id = graph.getId();
        boolean storedIdentity = graph.getResourceRevision() > 0L || !graph.getResourceHash().isBlank()
            || !graph.getResourceMutationId().isBlank();
        if (id == null || id.isBlank()) {
            return !"command".equals(type) && !storedIdentity;
        }
        FlowGraph current = getGraph(type, id);
        if (current != null) {
            return current.isEnabled();
        }
        return !"command".equals(type) && !storedIdentity;
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

    public Path backupGraphForMigration(String id, String migrationId) {
        String safeId = safeId(id, "backup flow migration");
        String safeMigrationId = safeId(migrationId, "backup flow migration");
        if (safeId == null || safeMigrationId == null) {
            throw new IllegalArgumentException("Invalid migration backup identity");
        }
        Path source = resourceFile(flowDir, "flow", safeId, "backup flow migration");
        if (source == null || Files.notExists(source)) {
            throw new IllegalStateException("Flow source is unavailable for migration backup: " + safeId);
        }
        Path backup = assetsDir.toPath().resolve("migration-backups").resolve(safeMigrationId).resolve(safeId + ".json");
        try {
            StorageSafety.writeUtf8Atomic(backup, StorageSafety.readUtf8(source));
            return backup;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to back up flow migration: " + safeId, exception);
        }
    }

    public Path backupGraphForMigration(String type, String id, String migrationId) {
        String safeId = safeId(id, "backup graph migration");
        String safeMigrationId = safeId(migrationId, "backup graph migration");
        if (!Set.of("flow", "function", "command").contains(type) || safeId == null || safeMigrationId == null) {
            throw new IllegalArgumentException("Invalid graph migration backup identity");
        }
        Path source = findAssetResourceFile(type, safeId);
        if (source == null || Files.notExists(source)) {
            throw new IllegalStateException("Graph source is unavailable for migration backup: " + type + ':' + safeId);
        }
        Path backup = assetsDir.toPath().resolve("migration-backups").resolve(safeMigrationId).resolve(type).resolve(safeId + ".json");
        try {
            StorageSafety.writeUtf8Atomic(backup, StorageSafety.readUtf8(source));
            return backup;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to back up graph migration: " + type + ':' + safeId, exception);
        }
    }

    public String getTypedAutomationBackupGraphResourceType(String id) {
        String safeId = safeId(id, "read flow migration backup");
        Path backupRoot = assetsDir.toPath().resolve("migration-backups");
        if (safeId == null || Files.notExists(backupRoot)) {
            return "";
        }
        try (Stream<Path> migrations = Files.list(backupRoot)) {
            return migrations.filter(Files::isDirectory)
                .filter(path -> path.getFileName().toString().startsWith("typed-automation-"))
                .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                .map(path -> path.resolve(safeId + ".json"))
                .filter(Files::isRegularFile)
                .map(AssetFileFormat::readResourceType)
                .filter(type -> Set.of("flow", "function", "command").contains(type))
                .findFirst()
                .orElse("");
        } catch (IOException exception) {
            Log.warn("Failed to inspect flow migration backups for " + safeId + ": " + exception.getMessage());
            return "";
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
        invalidateAssetFileIndex();
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
            } catch (NumberFormatException exception) {
                Log.warn("Invalid tab refresh interval in config: " + configuredInterval);
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
            return writableAssetPath(assetFile, id);
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
        String type = graphResourceType(graph);
        Path assetFile = findAssetResourceFile(type, id);
        if (assetFile != null) {
            return writableAssetPath(assetFile, id);
        }
        String json = getProjectMetadata("project");
        ProjectMetadataSnapshot metadata = parseProjectMetadata(json);
        ResourceEntrySnapshot resource = metadata != null ? metadata.findExactResource(type, id) : null;
        if (resource != null) {
            return assetResourceFile(resource);
        }
        return assetResourceFile(newResource(type, id, id, defaultFolderForType(type)));
    }

    private Path findAssetResourceFile(String type, String id) {
        if (!Files.exists(assetsDir.toPath())) {
            return null;
        }
        Map<String, Path> index = assetFiles();
        if (!"flow".equals(type)) {
            return index.get(assetIndexKey(type, id));
        }
        Path flow = index.get(assetIndexKey("flow", id));
        if (flow != null) {
            return flow;
        }
        Path function = index.get(assetIndexKey("function", id));
        if (function != null) {
            return function;
        }
        return index.get(assetIndexKey("command", id));
    }

    private Map<String, Path> assetFiles() {
        if (!assetFileIndexReady) {
            synchronized (this) {
                if (!assetFileIndexReady) {
                    rebuildAssetFileIndex();
                    assetFileIndexReady = true;
                }
            }
        }
        return assetFileIndex;
    }

    private void invalidateAssetFileIndex() {
        assetFileIndexReady = false;
        assetFileIndex.clear();
    }

    private void rebuildAssetFileIndex() {
        assetFileIndex.clear();
        Path root = assetsDir.toPath();
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (isDurabilityInternalPath(root, path)) {
                    continue;
                }
                AssetResourceName asset = parseAssetResourceName(path);
                if (asset == null || safeId(asset.id(), "index asset") == null) {
                    continue;
                }
                if (graphTombstoneBlocks(asset.type(), asset.id(), path)) {
                    continue;
                }
                String key = assetIndexKey(asset.type(), asset.id());
                Path existing = assetFileIndex.get(key);
                if (AssetFileFormat.isIdOnlyFileName(path.getFileName().toString())) {
                    assetFileIndex.put(key, path);
                } else if (existing == null || !AssetFileFormat.isIdOnlyFileName(existing.getFileName().toString())) {
                    assetFileIndex.put(key, path);
                }
            }
            applyProjectAssetPrecedence();
        } catch (IOException e) {
            Log.warn("Failed to index assets: " + e.getMessage());
        }
    }

    private void applyProjectAssetPrecedence() {
        ProjectMetadataSnapshot metadata = parseProjectMetadata(getProjectMetadata("project"));
        if (metadata == null) {
            return;
        }
        for (ResourceEntrySnapshot resource : metadata.resources()) {
            if (!knownAssetType(resource.type) || resource.id == null || resource.id.isBlank()) {
                continue;
            }
            String id = safeId(resource.id, "index project asset");
            if (id == null) {
                continue;
            }
            try {
                Path canonical = safeAssetFolder(resource.path).resolve(AssetFileFormat.idOnlyFileName(id)).toAbsolutePath().normalize();
                if (Files.isRegularFile(canonical) && resource.type.equals(AssetFileFormat.readResourceType(canonical))
                    && !graphTombstoneBlocks(resource.type, id, canonical)) {
                    assetFileIndex.put(assetIndexKey(resource.type, id), canonical);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private String assetIndexKey(String type, String id) {
        return type + "\n" + id;
    }

    private void evictGraphCache(String id) {
        Set.of("flow", "function", "command").forEach(type -> graphCache.remove(assetIndexKey(type, id)));
    }

    private Path assetResourceFile(ResourceEntrySnapshot resource) {
        Path folder = safeAssetFolder(resource.path);
        return folder.resolve(assetResourceFileName(resource.type, resource.id));
    }

    private String assetResourceFileName(String type, String id) {
        return AssetFileFormat.idOnlyFileName(id);
    }

    private boolean graphHasCommandStartNode(FlowGraph graph) {
        if (graph == null || graph.getNodes() == null) {
            return false;
        }
        return graph.getNodes().values().stream()
                .anyMatch(node -> node != null && ("event.resync.command".equals(node.getType()) || "event:resync_command".equals(node.getType())));
    }

    private boolean assetFileMatches(String type, String id, AssetResourceName asset) {
        return asset != null && id.equals(asset.id()) && type.equals(asset.type());
    }

    private Path writableAssetPath(Path assetFile, String id) {
        if (assetFile == null || AssetFileFormat.isIdOnlyFileName(assetFile.getFileName().toString())) {
            return assetFile;
        }
        return assetFile.getParent().resolve(AssetFileFormat.idOnlyFileName(id));
    }

    private String graphResourceTypeForWrite(FlowGraph graph) {
        return graphResourceType(graph);
    }

    private void deleteAssetDuplicates(String type, String id, Path keep) throws IOException {
        deleteAssetDuplicates(Set.of(type), id, keep);
    }

    private void deleteAssetDuplicates(Set<String> types, String id, Path keep) throws IOException {
        Path root = assetsDir.toPath();
        if (!Files.exists(root)) {
            return;
        }
        Path normalizedKeep = keep.toAbsolutePath().normalize();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (isDurabilityInternalPath(root, path)) {
                    continue;
                }
                if (path.toAbsolutePath().normalize().equals(normalizedKeep)) {
                    continue;
                }
                AssetResourceName asset = parseAssetResourceName(path);
                if (types.stream().anyMatch(type -> assetFileMatches(type, id, asset))) {
                    quarantineDuplicate(root, path);
                }
            }
        }
    }

    private void deleteGraphAssetDuplicates(Set<String> types, String id, Path keep) throws IOException {
        deleteAssetDuplicates(types, id, keep);
    }

    private Path collisionSafeWriteFile(String type, String id, Path file) throws IOException {
        if (file == null || !Files.exists(file)) {
            return file;
        }
        String existingType = AssetFileFormat.readResourceType(file);
        if (existingType.isBlank() || type.equals(existingType)) {
            return file;
        }
        String folder = conflictFolderForType(type, id, 1);
        Path target = safeAssetFolder(folder).resolve(AssetFileFormat.idOnlyFileName(id));
        Files.createDirectories(target.getParent());
        return target;
    }

    private String metadataWithResourcePath(String type, String id, String folder, boolean reclassifying) {
        ProjectMetadataSnapshot metadata = parseProjectMetadata(getProjectMetadata("project"));
        if (metadata == null) {
            return null;
        }
        if (reclassifying) {
            metadata.mutableResources().removeIf(resource -> resource != null && id.equals(resource.id) && GRAPH_TYPES.contains(resource.type) && !type.equals(resource.type));
        }
        ensureFolderPath(metadata, folder);
        ensureAssetResource(metadata, type, id, id, folder);
        return gson.toJson(metadata);
    }

    private void removeResourceMetadata(String type, String id) {
        ProjectMetadataSnapshot metadata = parseProjectMetadata(getProjectMetadata("project"));
        if (metadata == null || !metadata.mutableResources().removeIf(resource -> resource != null && type.equals(resource.type) && id.equals(resource.id))) {
            return;
        }
        saveProjectMetadata(gson.toJson(metadata));
    }

    private void applyResourceIdentity(FlowGraph graph, Path file) {
        String type = AssetFileFormat.readResourceType(file);
        graph.setFunction("function".equals(type));
        graph.setResourceType(type);
        graph.setResourceRevision(AssetFileFormat.readRevision(file));
        graph.setResourceHash(AssetFileFormat.readContentHash(file));
        graph.setResourceMutationId(AssetFileFormat.readMutationId(file));
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
                Path legacyQuarantine = assetsDir.toPath().resolve(".quarantine").resolve("deletes").resolve(UUID.randomUUID().toString()).resolve("legacy").resolve(type).resolve(legacyFile.getFileName());
                Files.createDirectories(legacyQuarantine.getParent());
                Files.move(legacyFile, legacyQuarantine);
                StorageSafety.forceDirectory(legacyQuarantine.getParent());
            }
        }
        Path assetFile = findAssetResourceFile(type, id);
        if (assetFile != null) {
            deleteAssetFile(assetFile);
        }
        deleteMatchingAssetFiles(type, id);
    }

    private void deleteAssetFile(Path file) throws IOException {
        Path root = assetsDir.toPath().toAbsolutePath().normalize();
        Path target = file.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.getParent() == null || !target.getFileName().toString().endsWith(".json")) {
            throw new IOException("Unsafe assets delete target: " + file);
        }
        quarantineAsset(root, target, "deletes");
    }

    private void deleteMatchingAssetFiles(String type, String id) throws IOException {
        Path root = assetsDir.toPath();
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (isDurabilityInternalPath(root, path)) {
                    continue;
                }
                AssetResourceName asset = parseAssetResourceName(path);
                if (assetFileMatches(type, id, asset)) {
                    deleteAssetFile(path);
                }
            }
        }
    }

    private boolean isDurabilityInternalPath(Path root, Path path) {
        Path relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
        if (relative.getNameCount() == 0) {
            return false;
        }
        return isDurabilityInternalAssetPath(relative.toString());
    }

    private boolean isDurabilityInternalAssetPath(String path) {
        String normalized = normalizeAssetPath(path);
        int separator = normalized.indexOf('/');
        String first = separator >= 0 ? normalized.substring(0, separator) : normalized;
        return first.equals(".transactions") || first.equals(".snapshots") || first.equals(".quarantine") || first.equals(".durability") || first.equals(".tombstones") || first.equals(".migrations") || first.equals("migration-backups");
    }

    private void writeGraphTombstone(String type, String id, long revision) throws IOException {
        if (!GRAPH_TYPES.contains(type)) {
            return;
        }
        JsonObject tombstone = new JsonObject();
        tombstone.addProperty("type", type);
        tombstone.addProperty("id", id);
        tombstone.addProperty("revision", Math.max(1L, revision));
        tombstone.addProperty("mutationId", UUID.randomUUID().toString());
        tombstone.addProperty("deletedAt", System.currentTimeMillis());
        StorageSafety.writeUtf8Atomic(graphTombstoneFile(type, id), gson.toJson(tombstone));
    }

    private void clearGraphTombstone(String type, String id) throws IOException {
        Path tombstone = graphTombstoneFile(type, id);
        if (Files.exists(tombstone)) {
            Files.delete(tombstone);
            StorageSafety.forceDirectory(tombstone.getParent());
        }
    }

    private boolean graphTombstoneBlocks(String type, String id, Path resource) {
        if (!GRAPH_TYPES.contains(type)) {
            return false;
        }
        Path tombstone = graphTombstoneFile(type, id);
        if (!Files.exists(tombstone)) {
            return false;
        }
        try {
            JsonObject value = gson.fromJson(StorageSafety.readUtf8(tombstone), JsonObject.class);
            long deletedRevision = value != null && value.has("revision") ? value.get("revision").getAsLong() : Long.MAX_VALUE;
            return deletedRevision >= AssetFileFormat.readRevision(resource);
        } catch (IOException | RuntimeException exception) {
            Log.warn("Failed to read graph tombstone for " + type + ':' + id + ": " + exception.getMessage());
            return true;
        }
    }

    private Path graphTombstoneFile(String type, String id) {
        return assetsDir.toPath().resolve(".tombstones").resolve(type).resolve(id + ".json");
    }

    private void quarantineDuplicate(Path root, Path path) throws IOException {
        quarantineAsset(root, path, "duplicates");
    }

    private void quarantineAsset(Path root, Path path, String category) throws IOException {
        Path relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
        Path quarantineRoot = root.resolve(".quarantine").resolve(category).resolve(UUID.randomUUID().toString());
        Path target = quarantineRoot.resolve(relative).normalize();
        if (!target.startsWith(quarantineRoot)) {
            throw new IOException("Unsafe asset quarantine path: " + relative);
        }
        Files.createDirectories(target.getParent());
        try {
            Files.move(path, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            Files.move(path, target);
        }
        StorageSafety.forceDirectory(target.getParent());
    }

    private void syncAssetsFromProjectMetadata(String json) {
        ProjectMetadataSnapshot metadata = parseProjectMetadata(json);
        if (metadata == null) {
            return;
        }
        try {
            invalidateAssetFileIndex();
            Files.createDirectories(assetsDir.toPath());
            for (FolderEntrySnapshot folder : metadata.folders()) {
                Files.createDirectories(safeAssetFolder(folder.path));
            }
            boolean changed = false;
            changed |= moveResourceTypeToAssets(metadata, flowDir, "flow");
            changed |= moveResourceTypeToAssets(metadata, new File(configFile.getParentFile(), "custom-content"), "custom_content");
            changed |= moveResourceTypeToAssets(metadata, guiDir, "gui");
            changed |= moveResourceTypeToAssets(metadata, scoreboardDir, "scoreboard");
            changed |= moveResourceTypeToAssets(metadata, tabDir, "tab");
            changed |= moveResourceTypeToAssets(metadata, new File(configFile.getParentFile(), "worldgen-projects"), "worldgen");
            for (String type : ReSyncJsonResourceStorage.resourceTypesStatic()) {
                changed |= moveResourceTypeToAssets(metadata, legacyJsonDirectory(type), type);
            }
            if (changed) {
                String updatedJson = gson.toJson(metadata);
                StorageSafety.writeUtf8Atomic(assetsDir.toPath().resolve("project.json"), updatedJson);
                projectMetadataCache.put("project", updatedJson);
            }
            invalidateAssetFileIndex();
        } catch (IOException | IllegalArgumentException e) {
            invalidateAssetFileIndex();
            Log.warn("Failed to sync assets: " + e.getMessage());
        }
    }

    private void migrateLegacyAssets() {
        try {
            invalidateAssetFileIndex();
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
            if (pruneMissingAssetResources(metadata)) {
                String json = gson.toJson(metadata);
                StorageSafety.writeUtf8Atomic(assetsDir.toPath().resolve("project.json"), json);
                projectMetadataCache.put("project", json);
            }
            cleanupEmptyAssetDirectories(metadata);
            cleanupLegacyAssetDirectories();
            invalidateAssetFileIndex();
        } catch (IOException | IllegalArgumentException e) {
            invalidateAssetFileIndex();
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
                if (isDurabilityInternalPath(root, file)) {
                    continue;
                }
                AssetResourceName asset = parseAssetResourceName(file);
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
                if (isOrphanedAssetCopy(metadata, type, id, file)) {
                    quarantineDuplicate(root, file);
                    continue;
                }
                String folder = assetFolderPath(root, file);
                changed |= ensureFolderPath(metadata, folder);
                Path target = safeAssetFolder(folder).resolve(AssetFileFormat.idOnlyFileName(id));
                if (Files.exists(target) && !file.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
                    String targetType = AssetFileFormat.readResourceType(target);
                    if (type.equals(targetType)) {
                        if (AssetFileFormat.isIdOnlyFileName(file.getFileName().toString())) {
                            quarantineDuplicate(root, file);
                        }
                        changed = true;
                        continue;
                    }
                    folder = conflictFolderForType(type, id, 1);
                    changed |= ensureFolderPath(metadata, folder);
                    target = safeAssetFolder(folder).resolve(AssetFileFormat.idOnlyFileName(id));
                    if (Files.exists(target) && type.equals(AssetFileFormat.readResourceType(target))) {
                        changed |= ensureAssetResource(metadata, type, id, id, folder);
                        if (AssetFileFormat.isIdOnlyFileName(file.getFileName().toString())) {
                            quarantineDuplicate(root, file);
                        }
                        changed = true;
                        continue;
                    }
                }
                changed |= ensureAssetResource(metadata, type, id, id, folder);
                boolean moved = !file.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize());
                if (AssetFileFormat.needsRewrite(file, type) || moved) {
                    AssetFileFormat.copyTyped(file, target, type);
                }
            }
        }
        return changed;
    }

    private boolean isOrphanedAssetCopy(ProjectMetadataSnapshot metadata, String type, String id, Path file) {
        ResourceEntrySnapshot resource = metadata.findExactResource(type, id);
        if (resource == null) {
            return false;
        }
        try {
            Path canonical = safeAssetFolder(resource.path).resolve(AssetFileFormat.idOnlyFileName(id)).toAbsolutePath().normalize();
            return Files.isRegularFile(canonical) && type.equals(AssetFileFormat.readResourceType(canonical)) && !file.toAbsolutePath().normalize().equals(canonical);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private boolean pruneMissingAssetResources(ProjectMetadataSnapshot metadata) throws IOException {
        Set<String> existing = new HashSet<>();
        Path root = assetsDir.toPath();
        if (Files.exists(root)) {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    if (isDurabilityInternalPath(root, file)) {
                        continue;
                    }
                    AssetResourceName asset = parseAssetResourceName(file);
                    if (asset != null) {
                        existing.add(assetIndexKey(asset.type(), asset.id()));
                    }
                }
            }
        }
        boolean changed = metadata.mutableResources().removeIf(resource -> resource != null
            && (isDurabilityInternalAssetPath(resource.path) || knownAssetType(resource.type)
            && !existing.contains(assetIndexKey(resource.type, resource.id))));
        changed |= metadata.mutableFolders().removeIf(folder -> folder != null && isDurabilityInternalAssetPath(folder.path));
        return changed;
    }

    private FlowGraph readAssetFlow(Path file) {
        if (file == null || !Files.exists(file)) {
            return null;
        }
        try {
            return FlowSerializer.deserialize(StorageSafety.readUtf8(file));
        } catch (IOException | RuntimeException e) {
            Log.warn("Failed to inspect graph asset during migration: " + file.getFileName() + " - " + e.getMessage());
            return null;
        }
    }

    private AssetResourceName parseAssetResourceName(Path file) {
        String fileName = file != null && file.getFileName() != null ? file.getFileName().toString() : "";
        if (fileName == null || !fileName.endsWith(".json")) {
            return null;
        }
        int separator = fileName.indexOf("__");
        if (separator <= 0) {
            String type = normalizeAssetType(AssetFileFormat.readResourceType(file));
            if (!knownAssetType(type)) {
                return null;
            }
            String id = AssetFileFormat.idFromIdOnlyFileName(fileName);
            return id.isBlank() ? null : new AssetResourceName(type, id);
        }
        String type = normalizeAssetType(fileName.substring(0, separator));
        if (!knownAssetType(type)) {
            return null;
        }
        String id = fileName.substring(separator + 2, fileName.length() - 5);
        return id.isBlank() ? null : new AssetResourceName(type, id);
    }

    private String normalizeAssetType(String type) {
        return "chat_channel".equals(type) ? "chat" : type;
    }

    private boolean knownAssetType(String type) {
        return type != null
                && !"project_metadata".equals(type)
                && !"world".equals(type)
                && ReSyncResourceCatalog.byType(type) != null;
    }

    private String conflictFolderForType(String type, String id, int index) {
        String suffix = index <= 1 ? type : type + "_" + index;
        String folder = AssetFileFormat.typedConflictFolder(defaultFolderForType(type), suffix);
        Path target = safeAssetFolder(folder).resolve(AssetFileFormat.idOnlyFileName(id));
        if (Files.exists(target) && !type.equals(AssetFileFormat.readResourceType(target))) {
            return conflictFolderForType(type, id, index + 1);
        }
        return folder;
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
        changed |= ensureFolder(metadata, "Content/Advancements", "Content", 3);
        changed |= ensureFolder(metadata, "Content/Dialogs", "Content", 4);
        changed |= ensureFolder(metadata, "GUIs", "", 2);
        changed |= ensureFolder(metadata, "Customization", "", 3);
        changed |= ensureFolder(metadata, "Customization/Scoreboards", "Customization", 0);
        changed |= ensureFolder(metadata, "Customization/Tabs", "Customization", 1);
        changed |= ensureFolder(metadata, "Text", "", 4);
        changed |= ensureFolder(metadata, "Text/Lists", "Text", 0);
        changed |= ensureFolder(metadata, "Text/Maps", "Text", 1);
        changed |= ensureFolder(metadata, "Text/Animations", "Text", 2);
        changed |= ensureFolder(metadata, "Text/Templates", "Text", 3);
        changed |= ensureFolder(metadata, "Worlds", "", 5);
        changed |= ensureFolder(metadata, "WorldGen", "", 6);
        changed |= ensureFolder(metadata, "Groups", "", 7);
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
                case "projectile" -> "Content/Projectiles";
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
        ResourceEntrySnapshot resource = metadata.findExactResource(type, id);
        if (resource != null) {
            return false;
        }
        resource = new ResourceEntrySnapshot();
        copyResource(newResource(type, id, displayName, folder), resource);
        resource.sortOrder = metadata.resources().size();
        metadata.mutableResources().add(resource);
        return true;
    }

    private boolean ensureAssetResource(ProjectMetadataSnapshot metadata, String type, String id, String displayName, String folder) {
        boolean changed = GRAPH_TYPES.contains(type) && metadata.mutableResources().removeIf(resource -> resource != null && id.equals(resource.id)
            && GRAPH_TYPES.contains(resource.type) && !type.equals(resource.type) && !declaredAssetExists(resource));
        ResourceEntrySnapshot resource = metadata.findExactResource(type, id);
        if (resource == null) {
            resource = new ResourceEntrySnapshot();
            copyResource(newResource(type, id, displayName, folder), resource);
            resource.sortOrder = metadata.resources().size();
            metadata.mutableResources().add(resource);
            return true;
        }
        String normalizedFolder = normalizeAssetPath(folder);
        if (!normalizedFolder.equals(resource.path)) {
            resource.path = normalizedFolder;
            changed = true;
        }
        if (resource.displayName == null || resource.displayName.isBlank()) {
            resource.displayName = displayName == null || displayName.isBlank() ? id : displayName;
            changed = true;
        }
        return changed;
    }

    private boolean declaredAssetExists(ResourceEntrySnapshot resource) {
        if (resource == null || resource.id == null || resource.path == null) {
            return false;
        }
        try {
            Path file = safeAssetFolder(resource.path).resolve(AssetFileFormat.idOnlyFileName(resource.id));
            return Files.isRegularFile(file) && resource.type.equals(AssetFileFormat.readResourceType(file));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private Set<String> loadCommandFlowIds() {
        Set<String> ids = new HashSet<>();
        File triggerFile = new File(configFile.getParentFile(), "triggers.json");
        if (triggerFile.exists()) {
            try {
                JsonElement element = gson.fromJson(Files.readString(triggerFile.toPath(), StandardCharsets.UTF_8), JsonElement.class);
                if (element != null && element.isJsonArray()) {
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
                }
            } catch (IOException e) {
                Log.warn("Failed to read command triggers during migration: " + e.getMessage());
            }
        }
        ids.addAll(loadCommandFlowIdsFromStoredGraphs());
        return ids;
    }

    private Set<String> loadCommandFlowIdsFromStoredGraphs() {
        Set<String> ids = new HashSet<>();
        if (flowDir.exists()) {
            File[] files = flowDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (files != null) {
                for (File file : files) {
                    String id = file.getName().substring(0, file.getName().length() - 5);
                    if (safeId(id, "load command graph") != null && graphHasCommandStartNode(readAssetFlow(file.toPath()))) {
                        ids.add(id);
                    }
                }
            }
        }
        Path root = assetsDir.toPath();
        if (!Files.exists(root)) {
            return ids;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                AssetResourceName asset = parseAssetResourceName(path);
                if (asset == null || !("flow".equals(asset.type()) || "function".equals(asset.type()) || "command".equals(asset.type()))) {
                    continue;
                }
                if (safeId(asset.id(), "load command graph") != null && graphHasCommandStartNode(readAssetFlow(path))) {
                    ids.add(asset.id());
                }
            }
        } catch (IOException e) {
            Log.warn("Failed to scan command graphs during migration: " + e.getMessage());
        }
        return ids;
    }

    private boolean moveResourceTypeToAssets(ProjectMetadataSnapshot metadata, File legacyDirectory, String type) throws IOException {
        boolean changed = false;
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
                if (Files.exists(target)) {
                    String targetType = AssetFileFormat.readResourceType(target);
                    if (resource.type.equals(targetType)) {
                        continue;
                    }
                    String folder = conflictFolderForType(resource.type, safeId, 1);
                    resource.path = normalizeAssetPath(folder);
                    target = assetResourceFile(resource);
                    changed = true;
                }
                AssetFileFormat.copyTyped(currentAsset, target, resource.type);
                continue;
            } else if (currentAsset != null) {
                AssetFileFormat.copyTyped(currentAsset, currentAsset, resource.type);
                continue;
            }
            if (legacyDirectory.exists()) {
                Path legacy = jsonFile(legacyDirectory, safeId, "sync assets");
                if (legacy != null && Files.exists(legacy) && !Files.exists(target)) {
                    AssetFileFormat.copyTyped(legacy, target, resource.type);
                } else if (legacy != null && Files.exists(legacy)) {
                    String targetType = AssetFileFormat.readResourceType(target);
                    if (resource.type.equals(targetType)) {
                        continue;
                    }
                    String folder = conflictFolderForType(resource.type, safeId, 1);
                    resource.path = normalizeAssetPath(folder);
                    target = assetResourceFile(resource);
                    changed = true;
                    AssetFileFormat.copyTyped(legacy, target, resource.type);
                }
            }
        }
        return changed;
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
        return ReSyncResourceCatalog.defaultFolder(type);
    }

    private File legacyJsonDirectory(String type) {
        String folder = switch (type) {
            case ReSyncResourceCatalog.CHAT -> "chat";
            case ReSyncResourceCatalog.MOTD_PROFILE -> "motd-profiles";
            case ReSyncResourceCatalog.MESSAGE_RULE -> "message-rules";
            case ReSyncResourceCatalog.RECIPE_DEFINITION -> "recipes";
            case ReSyncResourceCatalog.TEXT_TEMPLATE -> "text-templates";
            case ReSyncResourceCatalog.ADVANCEMENT_TREE -> "advancement-trees";
            case ReSyncResourceCatalog.DIALOG -> "dialogs";
            default -> type;
        };
        return new File(configFile.getParentFile(), folder);
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

    private void cleanupEmptyAssetDirectories(ProjectMetadataSnapshot metadata) throws IOException {
        Path root = assetsDir.toPath().toAbsolutePath().normalize();
        Set<Path> declared = new HashSet<>();
        for (FolderEntrySnapshot folder : metadata.folders()) {
            if (folder != null) {
                declared.add(safeAssetFolder(folder.path).toAbsolutePath().normalize());
            }
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path directory : paths.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList()) {
                if (directory.equals(root) || declared.contains(directory) || isDurabilityInternalPath(root, directory)) {
                    continue;
                }
                try (Stream<Path> children = Files.list(directory)) {
                    if (children.findAny().isEmpty()) {
                        Files.deleteIfExists(directory);
                    }
                }
            }
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
        for (String key : assetFiles().keySet()) {
            AssetResourceName asset = assetFromIndexKey(key);
            if (assetListFileMatches(type, asset) && safeId(asset.id(), "list") != null) {
                ids.add(asset.id());
            }
        }
        if (legacyDirectory.exists()) {
            File[] files = legacyDirectory.listFiles((dir, name) -> name.endsWith(".json"));
            if (files != null) {
                for (File file : files) {
                    String name = file.getName();
                    String id = name.substring(0, name.length() - 5);
                    if (safeId(id, "list") != null) {
                        ids.add(id);
                    }
                }
            }
        }
        return ids.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private AssetResourceName assetFromIndexKey(String key) {
        if (key == null) {
            return null;
        }
        int separator = key.indexOf('\n');
        if (separator <= 0 || separator >= key.length() - 1) {
            return null;
        }
        return new AssetResourceName(key.substring(0, separator), key.substring(separator + 1));
    }

    private boolean assetListFileMatches(String type, AssetResourceName asset) {
        return asset != null && type.equals(asset.type());
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
