package restudio.resync.customcontent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.plugin.java.JavaPlugin;
import restudio.flow.data.CustomAbilityBinding;
import restudio.flow.data.CustomArmorDefinition;
import restudio.flow.data.CustomBlockDefinition;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.CustomItemDefinition;
import restudio.flow.data.FlowDataObject;
import restudio.flow.data.FlowDataObjectAdapter;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowDataTypeAdapter;
import restudio.resync.Log;
import restudio.resync.storage.StorageSafety;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class CustomContentStorage {
    private final File contentDir;
    private final Path contentPath;
    private final File assetsDir;
    private final Map<String, CustomContentDefinition> cache = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(FlowDataType.class, new FlowDataTypeAdapter())
            .registerTypeAdapter(FlowDataObject.class, new FlowDataObjectAdapter())
            .create();
    private final CustomContentValidator validator = new CustomContentValidator();

    public CustomContentStorage(JavaPlugin plugin) {
        this(plugin.getDataFolder());
    }

    CustomContentStorage(File dataFolder) {
        this.contentDir = new File(dataFolder, "custom-content");
        this.contentPath = contentDir.toPath();
        this.assetsDir = new File(dataFolder, "assets");
        if (!assetsDir.exists()) {
            assetsDir.mkdirs();
        }
        migrateLegacyAssets();
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
        Path file = findAssetFile(safeId);
        if (file != null) {
            return loadFromFile(file, safeId);
        }
        return null;
    }

    public void save(CustomContentDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("Invalid custom content definition");
        }
        String safeId = safeId(definition.getId(), "save");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid custom content id");
        }
        List<String> errors = validator.validate(definition);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        try {
            Path target = findAssetFile(safeId);
            StorageSafety.writeUtf8Atomic(target != null ? target : defaultAssetFile(definition), gson.toJson(definition));
            cache.put(safeId, definition);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save custom content: " + safeId, e);
        }
    }

    public void delete(String id) {
        String safeId = safeId(id, "delete");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid custom content id");
        }
        try {
            StorageSafety.deleteIfExists(StorageSafety.jsonFile(contentPath, safeId));
            Path assetFile = findAssetFile(safeId);
            if (assetFile != null) {
                deleteAssetFile(assetFile);
            }
            cache.remove(safeId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete custom content: " + safeId, e);
        }
    }

    public List<String> listIds() {
        Set<String> ids = new HashSet<>(cache.keySet());
        Path root = assetsDir.toPath();
        if (root.toFile().exists()) {
            try (Stream<Path> paths = java.nio.file.Files.walk(root)) {
                paths
                        .filter(path -> java.nio.file.Files.isRegularFile(path) && path.getFileName().toString().startsWith("custom_content__") && path.getFileName().toString().endsWith(".json"))
                        .map(path -> path.getFileName().toString())
                        .map(name -> name.substring("custom_content__".length(), name.length() - 5))
                        .filter(id -> safeId(id, "list") != null)
                        .forEach(ids::add);
            } catch (IOException e) {
                Log.warn("Failed to list custom content assets: " + e.getMessage());
            }
        }
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

    private CustomContentDefinition loadFromFile(Path file, String safeId) {
        try {
            CustomContentDefinition definition = gson.fromJson(StorageSafety.readUtf8(file), CustomContentDefinition.class);
            if (definition != null && safeId(definition.getId(), "load") != null) {
                cache.put(definition.getId(), definition);
            }
            return definition;
        } catch (IOException e) {
            Log.warn("Failed to load custom content: " + safeId + " - " + e.getMessage());
            return null;
        }
    }

    private Path findAssetFile(String id) {
        String fileName = "custom_content__" + id + ".json";
        Path root = assetsDir.toPath();
        if (!root.toFile().exists()) {
            return null;
        }
        try (Stream<Path> paths = java.nio.file.Files.walk(root)) {
            return paths
                    .filter(path -> java.nio.file.Files.isRegularFile(path) && path.getFileName().toString().equals(fileName))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            Log.warn("Failed to search custom content assets: " + id + " - " + e.getMessage());
            return null;
        }
    }

    private Path defaultAssetFile(CustomContentDefinition definition) throws IOException {
        String folder = switch (definition.getType() != null ? definition.getType().toLowerCase() : "item") {
            case "armor" -> "Content/Armor";
            case "block" -> "Content/Blocks";
            default -> "Content/Items";
        };
        Path target = assetsDir.toPath().resolve(folder).resolve("custom_content__" + definition.getId() + ".json");
        Files.createDirectories(target.getParent());
        return target;
    }

    private void migrateLegacyAssets() {
        File[] files = contentDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                String id = file.getName().substring(0, file.getName().length() - 5);
                if (safeId(id, "migrate") == null) {
                    continue;
                }
                migrateLegacyFile(file.toPath(), id);
            }
        }
        deleteLegacyDirectory();
    }

    private void migrateLegacyFile(Path file, String id) {
        try {
            CustomContentDefinition definition = gson.fromJson(StorageSafety.readUtf8(file), CustomContentDefinition.class);
            String folder = switch (definition != null && definition.getType() != null ? definition.getType().toLowerCase() : "item") {
                case "armor" -> "Content/Armor";
                case "block" -> "Content/Blocks";
                default -> "Content/Items";
            };
            Path target = assetsDir.toPath().resolve(folder).resolve("custom_content__" + id + ".json");
            Files.createDirectories(target.getParent());
            if (!Files.exists(target)) {
                Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            Log.warn("Failed to migrate custom content asset: " + id + " - " + e.getMessage());
        }
    }

    private void deleteLegacyDirectory() {
        if (!contentDir.exists()) {
            return;
        }
        try (Stream<Path> paths = Files.walk(contentDir.toPath())) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            Log.warn("Failed to delete legacy custom content directory: " + e.getMessage());
        }
    }

    private void deleteAssetFile(Path file) throws IOException {
        Path root = assetsDir.toPath().toAbsolutePath().normalize();
        Path target = file.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.getParent() == null || !target.getFileName().toString().endsWith(".json")) {
            throw new IOException("Unsafe custom content assets delete target: " + file);
        }
        Files.deleteIfExists(target);
    }
}
