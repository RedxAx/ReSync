package restudio.resync.customcontent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.plugin.java.JavaPlugin;
import restudio.flow.data.CustomAbilityBinding;
import restudio.flow.data.CustomArmorDefinition;
import restudio.flow.data.CustomBlockDefinition;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.CustomContentGraphAdapter;
import restudio.flow.data.CustomItemDefinition;
import restudio.flow.data.FlowDataObject;
import restudio.flow.data.FlowDataObjectAdapter;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowDataTypeAdapter;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.Log;
import restudio.resync.resources.JsonAssetStore;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.storage.StorageSafety;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CustomContentStorage {
    private final JavaPlugin plugin;
    private final File contentDir;
    private final Path contentPath;
    private final File assetsDir;
    private final JsonAssetStore<CustomContentDefinition> assetStore;
    private final Map<String, CustomContentDefinition> cache = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(FlowDataType.class, new FlowDataTypeAdapter())
            .registerTypeAdapter(FlowDataObject.class, new FlowDataObjectAdapter())
            .create();
    private final CustomContentValidator validator = new CustomContentValidator();
    private final ItemAttributeSchemaService attributeSchemaService;

    public CustomContentStorage(JavaPlugin plugin) {
        this(plugin, new ItemAttributeSchemaService());
    }

    public CustomContentStorage(JavaPlugin plugin, ItemAttributeSchemaService attributeSchemaService) {
        this(plugin, plugin.getDataFolder(), attributeSchemaService);
    }

    CustomContentStorage(File dataFolder) {
        this(null, dataFolder, new ItemAttributeSchemaService());
    }

    CustomContentStorage(File dataFolder, ItemAttributeSchemaService attributeSchemaService) {
        this(null, dataFolder, attributeSchemaService);
    }

    private CustomContentStorage(JavaPlugin plugin, File dataFolder, ItemAttributeSchemaService attributeSchemaService) {
        this.plugin = plugin;
        this.attributeSchemaService = attributeSchemaService != null ? attributeSchemaService : new ItemAttributeSchemaService();
        this.contentDir = new File(dataFolder, "custom-content");
        this.contentPath = contentDir.toPath();
        this.assetsDir = new File(dataFolder, "assets");
        this.assetStore = new JsonAssetStore<>(
            assetsDir.toPath(),
            contentPath,
            ReSyncResourceCatalog.CUSTOM_CONTENT,
            ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.CUSTOM_CONTENT),
            json -> gson.fromJson(json, CustomContentDefinition.class),
            gson::toJson,
            CustomContentDefinition::getId,
            this::defaultFolder
        );
        if (!assetsDir.exists()) {
            assetsDir.mkdirs();
        }
        migrateLegacyAssets();
        repairMalformedFlowAliases();
    }

    JavaPlugin getPlugin() {
        return plugin;
    }

    public void preloadAll() {
        for (String id : listIds()) {
            get(id);
        }
    }

    public CustomContentDefinition get(String id) {
        String safeId = safeId(id, "load");
        if (safeId == null) {
            return null;
        }
        CustomContentDefinition cached = cache.get(safeId);
        if (cached != null) {
            return cached;
        }
        CustomContentDefinition definition = assetStore.get(safeId);
        if (definition != null) {
            cache.put(safeId, definition);
        }
        return definition;
    }

    public void save(CustomContentDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("Invalid custom content definition");
        }
        definition.setComponents(attributeSchemaService.customComponentsForMaterial(definition.getMaterial(), definition.getComponents()));
        String safeId = safeId(definition.getId(), "save");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid custom content id");
        }
        List<String> errors = validator.validate(definition);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        List<Map<String, Object>> componentErrors = attributeSchemaService.validate(definition.getMaterial(), definition.getComponents());
        if (!componentErrors.isEmpty()) {
            throw new ItemAttributeValidationException(componentErrors);
        }
        try {
            assetStore.save(definition);
            cache.put(safeId, definition);
            removeMalformedFlowAliases(definition);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save custom content: " + safeId, e);
        }
    }

    public void delete(String id) {
        String safeId = safeId(id, "delete");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid custom content id");
        }
        try {
            assetStore.delete(safeId);
            cache.remove(safeId);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete custom content: " + safeId, e);
        }
    }

    public List<String> listIds() {
        Set<String> ids = new HashSet<>(cache.keySet());
        ids.addAll(assetStore.listIds());
        return ids.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public List<CustomContentDefinition> getAll() {
        List<CustomContentDefinition> definitions = new ArrayList<>();
        for (String id : listIds()) {
            CustomContentDefinition definition = get(id);
            if (definition != null) {
                definitions.add(definition);
            }
        }
        definitions.sort(Comparator.comparing(CustomContentDefinition::getId, String.CASE_INSENSITIVE_ORDER));
        return definitions;
    }

    public List<CustomContentDefinition> getByFlow(String flowId) {
        if (flowId == null) {
            return List.of();
        }
        return getAll().stream().filter(definition -> flowId.equals(definition.getFlowId())).toList();
    }

    public CustomContentDefinition repairMalformedFlowIdentity(CustomContentDefinition definition) {
        if (definition == null || definition.getGraph() == null || definition.getId() == null || definition.getFlowId() == null
            || !definition.getId().equalsIgnoreCase(definition.getFlowId())) {
            return definition;
        }
        List<CustomContentDefinition> canonical = getByFlow(definition.getFlowId()).stream()
            .filter(existing -> existing.getId() != null && !existing.getId().equalsIgnoreCase(definition.getFlowId()))
            .toList();
        if (canonical.size() != 1) {
            return definition;
        }
        CustomContentDefinition original = canonical.getFirst();
        FlowGraph repairedGraph = repairGraph(definition, original);
        FlowNode start = CustomContentGraphAdapter.findStartNode(repairedGraph);
        if (start == null || start.getInputValues() == null) {
            return definition;
        }
        start.setType(CustomContentGraphAdapter.nodeType(original.getType()));
        Map<String, Object> inputs = start.getInputValues();
        inputs.put("content_id", original.getId());
        inputs.put("name", original.getDisplayName());
        inputs.put("provider", original.getProvider());
        inputs.put("external_id", original.getExternalId());
        inputs.put("material", original.getMaterial());
        inputs.put("custom_model_data", original.getCustomModelData() != null ? original.getCustomModelData() : "");
        inputs.put("components", new LinkedHashMap<>(original.getComponents() != null ? original.getComponents() : Map.of()));
        inputs.put("lore", String.join("\n", original.getLore() != null ? original.getLore() : List.of()));
        inputs.put("tags", String.join("\n", original.getTags() != null ? original.getTags() : List.of()));
        inputs.remove("armor_slot");
        if ("armor".equalsIgnoreCase(original.getType())) {
            CustomContentGraphAdapter.setContentConfiguration(repairedGraph, "armor_slot", original.getArmorSlot());
        } else {
            CustomContentGraphAdapter.removeContentConfiguration(repairedGraph, "armor_slot");
        }
        CustomContentDefinition repaired = CustomContentGraphAdapter.toDefinition(repairedGraph);
        if (repaired == null) {
            return definition;
        }
        repaired.setVersion(original.getVersion());
        Log.warn("Repaired malformed custom content identity " + definition.getId() + " to " + original.getId());
        return repaired;
    }

    private FlowGraph repairGraph(CustomContentDefinition malformed, CustomContentDefinition original) {
        FlowGraph malformedGraph = malformed.getGraph();
        FlowGraph originalGraph = original.getGraph();
        if (originalGraph == null || original.getType() == null || malformed.getType() == null || original.getType().equalsIgnoreCase(malformed.getType())) {
            return malformedGraph;
        }
        FlowNode malformedStart = CustomContentGraphAdapter.findStartNode(malformedGraph);
        String malformedStartId = malformedGraph.findNodeId(malformedStart);
        Set<String> transferred = new HashSet<>();
        for (Map.Entry<String, FlowNode> entry : malformedGraph.getNodes().entrySet()) {
            if (!entry.getKey().equals(malformedStartId) && !originalGraph.getNodes().containsKey(entry.getKey())) {
                originalGraph.getNodes().put(entry.getKey(), entry.getValue());
                transferred.add(entry.getKey());
            }
        }
        for (FlowConnection connection : malformedGraph.getConnections()) {
            if (transferred.contains(connection.getSourceNodeId()) && transferred.contains(connection.getTargetNodeId())
                && !originalGraph.getConnections().contains(connection)) {
                originalGraph.getConnections().add(connection);
            }
        }
        return originalGraph;
    }

    public List<CustomContentDefinition> getByType(String type) {
        if (type == null) {
            return List.of();
        }
        return getAll().stream().filter(definition -> type.equalsIgnoreCase(definition.getType())).toList();
    }

    public void ensureDefaultsForFlow(String flowId) {
        if (flowId == null || flowId.isBlank()) {
            return;
        }
        String itemId = flowId + ".default_item";
        String blockId = flowId + ".default_block";
        String armorId = flowId + ".default_armor";
        if (get(itemId) == null) {
            CustomItemDefinition item = new CustomItemDefinition();
            item.setId(itemId);
            item.setFlowId(flowId);
            item.setDisplayName("Default Item");
            item.setMaterial("STICK");
            item.getAbilities().add(new CustomAbilityBinding(itemId + ".use", "item.use", flowId));
            save(item);
        }
        if (get(blockId) == null) {
            CustomBlockDefinition block = new CustomBlockDefinition();
            block.setId(blockId);
            block.setFlowId(flowId);
            block.setDisplayName("Default Block");
            block.setMaterial("STONE");
            block.getAbilities().add(new CustomAbilityBinding(blockId + ".interact", "block.interact", flowId));
            save(block);
        }
        if (get(armorId) == null) {
            CustomArmorDefinition armor = new CustomArmorDefinition();
            armor.setId(armorId);
            armor.setFlowId(flowId);
            armor.setDisplayName("Default Armor");
            armor.setMaterial("IRON_CHESTPLATE");
            armor.setArmorSlot("chest");
            armor.getAbilities().add(new CustomAbilityBinding(armorId + ".tick", "armor.tick", flowId));
            save(armor);
        }
    }

    private String safeId(String id, String action) {
        try {
            return StorageSafety.validateId(id);
        } catch (IllegalArgumentException e) {
            Log.warn("Rejected unsafe custom content id during " + action + ": " + id);
            return null;
        }
    }

    private String defaultFolder(CustomContentDefinition definition) {
        return switch (definition != null && definition.getType() != null ? definition.getType().toLowerCase() : "item") {
            case "armor" -> "Content/Armor";
            case "block" -> "Content/Blocks";
            default -> "Content/Items";
        };
    }

    private void removeMalformedFlowAliases(CustomContentDefinition definition) {
        String id = definition != null ? definition.getId() : null;
        String flowId = definition != null ? definition.getFlowId() : null;
        if (id == null || flowId == null || id.equalsIgnoreCase(flowId)) {
            return;
        }
        for (CustomContentDefinition candidate : getByFlow(flowId)) {
            if (candidate.getId() != null && candidate.getId().equalsIgnoreCase(flowId)) {
                assetStore.delete(candidate.getId());
                cache.remove(candidate.getId());
                Log.warn("Removed malformed custom content alias " + candidate.getId() + " for " + id);
            }
        }
    }

    private void repairMalformedFlowAliases() {
        List<CustomContentDefinition> malformed = getAll().stream()
            .filter(definition -> definition.getId() != null && definition.getFlowId() != null && definition.getId().equalsIgnoreCase(definition.getFlowId()))
            .toList();
        for (CustomContentDefinition definition : malformed) {
            CustomContentDefinition repaired = repairMalformedFlowIdentity(definition);
            if (repaired == definition) {
                continue;
            }
            try {
                save(repaired);
            } catch (RuntimeException exception) {
                Log.warn("Failed to repair malformed custom content alias " + definition.getId() + ": " + exception.getMessage());
            }
        }
    }

    private void migrateLegacyAssets() {
        assetStore.migrateLegacyAssets();
    }
}
