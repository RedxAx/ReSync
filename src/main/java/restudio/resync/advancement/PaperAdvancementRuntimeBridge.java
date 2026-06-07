package restudio.resync.advancement;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import restudio.resync.Log;
import restudio.resync.customcontent.CustomContentService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PaperAdvancementRuntimeBridge implements AdvancementRuntimeBridge {
    private static final String ROOT_CRITERION = "__resync_root";
    private static final Gson GSON = new Gson();

    private final CustomContentService customContent;
    private final boolean supported;
    private Set<NamespacedKey> loadedKeys = Set.of();
    private Map<NamespacedKey, JsonObject> loadedDefinitions = Map.of();

    public PaperAdvancementRuntimeBridge(CustomContentService customContent) {
        this.customContent = customContent;
        this.supported = PaperUnsafe.loadAdvancementSupported();
    }

    @Override
    public boolean supported() {
        return supported;
    }

    @Override
    public String unsupportedReason() {
        return supported() ? "" : "Custom advancements require Paper with loadAdvancement support";
    }

    @Override
    public synchronized void replace(Map<String, JsonObject> trees) {
        if (!supported()) {
            throw new IllegalStateException(unsupportedReason());
        }
        Set<NamespacedKey> previousKeys = new LinkedHashSet<>(loadedKeys);
        Map<NamespacedKey, String> previousJson = toAdvancementJson(loadedDefinitions);
        Map<NamespacedKey, JsonObject> nextDefinitions = nativeDefinitions(trees);
        Map<NamespacedKey, String> nextJson = toAdvancementJson(nextDefinitions);
        try {
            applyAdvancements(nextJson);
            loadedKeys = new LinkedHashSet<>(nextJson.keySet());
            loadedDefinitions = nextDefinitions;
        } catch (RuntimeException failure) {
            applyAdvancements(previousJson);
            loadedKeys = previousKeys;
            loadedDefinitions = nativeDefinitionsFromKeys(previousKeys, previousJson);
            throw failure;
        }
    }

    @Override
    public void sync(Player player) {
        if (player == null) {
            return;
        }
        for (NamespacedKey key : loadedKeys) {
            Advancement advancement = Bukkit.getAdvancement(key);
            JsonObject definition = loadedDefinitions.get(key);
            if (advancement == null || !isRoot(definition)) {
                continue;
            }
            AdvancementProgress progress = player.getAdvancementProgress(advancement);
            if (!progress.getAwardedCriteria().contains(ROOT_CRITERION)) {
                progress.awardCriteria(ROOT_CRITERION);
            }
        }
    }

    private void applyAdvancements(Map<NamespacedKey, String> definitions) {
        if (!loadedKeys.isEmpty()) {
            for (NamespacedKey key : loadedKeys) {
                PaperUnsafe.removeAdvancement(key);
            }
            Bukkit.reloadData();
        }
        if (!definitions.isEmpty()) {
            PaperUnsafe.loadAdvancements(definitions, false);
            verifyLoaded(definitions.keySet());
        }
    }

    private void verifyLoaded(Set<NamespacedKey> keys) {
        List<NamespacedKey> missing = new ArrayList<>();
        for (NamespacedKey key : keys) {
            if (Bukkit.getAdvancement(key) == null) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Server did not register advancements: " + missing);
        }
    }

    private Map<NamespacedKey, String> toAdvancementJson(Map<NamespacedKey, JsonObject> definitions) {
        Map<NamespacedKey, String> json = new LinkedHashMap<>();
        for (Map.Entry<NamespacedKey, JsonObject> entry : definitions.entrySet()) {
            json.put(entry.getKey(), GSON.toJson(entry.getValue()));
        }
        return json;
    }

    private Map<NamespacedKey, JsonObject> nativeDefinitionsFromKeys(Set<NamespacedKey> keys, Map<NamespacedKey, String> json) {
        Map<NamespacedKey, JsonObject> definitions = new LinkedHashMap<>();
        for (NamespacedKey key : keys) {
            String raw = json.get(key);
            if (raw != null) {
                definitions.put(key, GSON.fromJson(raw, JsonObject.class));
            }
        }
        return definitions;
    }

    private Map<NamespacedKey, JsonObject> nativeDefinitions(Map<String, JsonObject> trees) {
        Map<NamespacedKey, JsonObject> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> treeEntry : trees.entrySet()) {
            if (!bool(treeEntry.getValue(), "enabled", true)) {
                continue;
            }
            JsonObject nodes = treeEntry.getValue().getAsJsonObject("nodes");
            for (String nodeId : orderedNodeIds(treeEntry.getKey(), nodes)) {
                JsonObject node = nodes.getAsJsonObject(nodeId);
                if (bool(node, "enabled", true)) {
                    result.put(key(treeEntry.getKey(), nodeId), nativeDefinition(treeEntry.getKey(), node));
                }
            }
        }
        return result;
    }

    private List<String> orderedNodeIds(String treeId, JsonObject nodes) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> entry : nodes.entrySet()) {
            if (entry.getValue().isJsonObject() && bool(entry.getValue().getAsJsonObject(), "enabled", true)) {
                appendNodeWithParents(treeId, nodes, entry.getKey(), ordered, new LinkedHashSet<>());
            }
        }
        return new ArrayList<>(ordered);
    }

    private void appendNodeWithParents(String treeId, JsonObject nodes, String nodeId, LinkedHashSet<String> ordered, Set<String> visiting) {
        if (nodeId == null || nodeId.isBlank() || ordered.contains(nodeId) || !nodes.has(nodeId) || !nodes.get(nodeId).isJsonObject() || !visiting.add(nodeId)) {
            return;
        }
        String parent = localParent(treeId, text(nodes.getAsJsonObject(nodeId), "parent"));
        if (!parent.isBlank()) {
            appendNodeWithParents(treeId, nodes, parent, ordered, visiting);
        }
        ordered.add(nodeId);
        visiting.remove(nodeId);
    }

    private String localParent(String treeId, String parent) {
        if (parent.isBlank() || parent.contains(":")) {
            return "";
        }
        if (!parent.contains("/")) {
            return parent;
        }
        String[] parts = parent.split("/", 2);
        return parts.length == 2 && parts[0].equals(treeId) ? parts[1] : "";
    }

    private JsonObject nativeDefinition(String treeId, JsonObject node) {
        JsonObject result = new JsonObject();
        String parent = text(node, "parent");
        if (!parent.isBlank()) {
            result.addProperty("parent", nativeParent(treeId, parent));
        }
        JsonObject sourceDisplay = node.getAsJsonObject("display");
        JsonObject display = new JsonObject();
        if (sourceDisplay != null) {
            if (sourceDisplay.has("title") && !sourceDisplay.get("title").isJsonNull()) {
                display.add("title", AdvancementDisplayJson.textComponent(sourceDisplay.get("title")));
            }
            if (sourceDisplay.has("description") && !sourceDisplay.get("description").isJsonNull()) {
                display.add("description", AdvancementDisplayJson.textComponent(sourceDisplay.get("description")));
            }
        }
        copy(sourceDisplay, display, "frame", "frame");
        String background = text(sourceDisplay, "background");
        if (!background.isBlank()) {
            display.addProperty("background", AdvancementDisplayJson.background(background));
        }
        copy(sourceDisplay, display, "showToast", "show_toast");
        copy(sourceDisplay, display, "announceToChat", "announce_to_chat");
        copy(sourceDisplay, display, "hidden", "hidden");
        String icon = text(sourceDisplay, "icon");
        display.add("icon", nativeIcon(icon));
        result.add("display", display);
        JsonObject criteria = new JsonObject();
        JsonObject impossible = new JsonObject();
        impossible.addProperty("trigger", "minecraft:impossible");
        if (parent.isBlank()) {
            criteria.add(ROOT_CRITERION, impossible);
        }
        JsonObject configured = node.has("criteria") && node.get("criteria").isJsonObject() ? node.getAsJsonObject("criteria") : new JsonObject();
        for (String criterionId : configured.keySet()) {
            criteria.add(criterionId, impossible.deepCopy());
        }
        if (criteria.isEmpty()) {
            criteria.add("impossible", impossible.deepCopy());
        }
        result.add("criteria", criteria);
        JsonArray requirements = new JsonArray();
        if (node.has("requirements") && node.get("requirements").isJsonArray() && !node.getAsJsonArray("requirements").isEmpty()) {
            for (JsonElement requirement : node.getAsJsonArray("requirements")) {
                requirements.add(requirement.deepCopy());
            }
        } else {
            for (String criterionId : criteria.keySet()) {
                JsonArray group = new JsonArray();
                group.add(criterionId);
                requirements.add(group);
            }
        }
        result.add("requirements", requirements);
        if (node.has("rewards") && node.get("rewards").isJsonObject()) {
            result.add("rewards", node.getAsJsonObject("rewards").deepCopy());
        }
        return result;
    }

    private NamespacedKey key(String treeId, String nodeId) {
        return new NamespacedKey("resync", treeId + "/" + nodeId);
    }

    private JsonObject nativeIcon(String reference) {
        ItemStack item = customContent != null ? customContent.createReferencedItem(reference, 1) : null;
        if (item != null && !item.getType().isAir()) {
            return AdvancementIconJson.fromResolved(item);
        }
        if (reference != null && (reference.startsWith("content:") || reference.startsWith("provider:"))) {
            Log.warn("Unresolved advancement icon " + reference + ", using stone fallback");
            return AdvancementIconJson.fromReference("minecraft:stone");
        }
        return AdvancementIconJson.fromReference(reference);
    }

    private String nativeParent(String treeId, String parent) {
        if (parent.contains(":")) {
            return parent;
        }
        return "resync:" + (parent.contains("/") ? parent : treeId + "/" + parent);
    }

    private boolean isRoot(JsonObject definition) {
        return definition != null && !definition.has("parent");
    }

    private String text(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private boolean bool(JsonObject object, String key, boolean fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
    }

    private void copy(JsonObject source, JsonObject target, String sourceKey, String targetKey) {
        if (source != null && source.has(sourceKey) && !source.get(sourceKey).isJsonNull()) {
            target.add(targetKey, source.get(sourceKey).deepCopy());
        }
    }
}
