package restudio.resync.customization;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.bukkit.plugin.java.JavaPlugin;
import restudio.resync.resources.JsonAssetStore;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ReSyncJsonResourceStorage {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, JsonAssetStore<JsonObject>> stores = new LinkedHashMap<>();
    private final Map<String, CachedIconData> iconDataCache = new ConcurrentHashMap<>();
    private final List<ResourceListener> listeners = new CopyOnWriteArrayList<>();
    private final List<ResourceMutationInterceptor> interceptors = new CopyOnWriteArrayList<>();
    private final JavaPlugin plugin;

    private record CachedIconData(long modified, long size, String data, String hash) {
    }

    public ReSyncJsonResourceStorage(JavaPlugin plugin) {
        this.plugin = plugin;
        File dataFolder = plugin.getDataFolder();
        for (String type : resourceTypes()) {
            ReSyncManagedResource resource = ReSyncResourceCatalog.byType(type);
            stores.put(type, new JsonAssetStore<>(
                dataFolder.toPath().resolve("assets"),
                dataFolder.toPath().resolve(legacyFolder(type)),
                type,
                resource.defaultFolder(),
                json -> gson.fromJson(json, JsonObject.class),
                gson::toJson,
                this::id,
                value -> folder(value, resource.defaultFolder())
            ));
        }
    }

    public JsonObject get(String type, String id) {
        JsonAssetStore<JsonObject> store = stores.get(type);
        JsonObject value = store != null ? store.get(id) : null;
        normalizeAssetId(value, id);
        if (value != null && ReSyncResourceCatalog.MOTD_PROFILE.equals(type)) {
            return motdProfileForClient(value);
        }
        return value;
    }

    public List<String> listIds(String type) {
        JsonAssetStore<JsonObject> store = stores.get(type);
        return store != null ? store.listIds() : List.of();
    }

    public void save(String type, JsonObject value) {
        JsonAssetStore<JsonObject> store = stores.get(type);
        if (store == null) {
            throw new IllegalArgumentException("Unknown resource type: " + type);
        }
        normalizeAssetId(value, id(value));
        if (ReSyncResourceCatalog.MOTD_PROFILE.equals(type)) {
            prepareMotdIcon(value);
        }
        try {
            for (ResourceMutationInterceptor interceptor : interceptors) {
                interceptor.beforeSave(type, value);
            }
            store.save(value);
        } catch (RuntimeException failure) {
            notifySaveFailure(type, value, failure);
            throw failure;
        }
        notifyListeners(type, id(value), value, false);
    }

    public void delete(String type, String id) {
        JsonAssetStore<JsonObject> store = stores.get(type);
        if (store == null) {
            throw new IllegalArgumentException("Unknown resource type: " + type);
        }
        try {
            for (ResourceMutationInterceptor interceptor : interceptors) {
                interceptor.beforeDelete(type, id);
            }
            store.delete(id);
        } catch (RuntimeException failure) {
            notifyDeleteFailure(type, id, failure);
            throw failure;
        }
        notifyListeners(type, id, null, true);
    }

    public void addListener(ResourceListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void addInterceptor(ResourceMutationInterceptor interceptor) {
        if (interceptor != null) {
            interceptors.add(interceptor);
        }
    }

    public void migrateLegacyAssets() {
        for (JsonAssetStore<JsonObject> store : stores.values()) {
            store.migrateLegacyAssets();
        }
    }

    public List<String> resourceTypes() {
        return resourceTypesStatic();
    }

    public static List<String> resourceTypesStatic() {
        return ReSyncResourceCatalog.jsonStorageTypes();
    }

    private String id(JsonObject value) {
        if (value == null || !value.has("id") || value.get("id").isJsonNull()) {
            return "";
        }
        return value.get("id").getAsString();
    }

    private void normalizeAssetId(JsonObject value, String id) {
        if (value == null || id == null || id.isBlank()) {
            return;
        }
        value.addProperty("id", id);
    }

    private String folder(JsonObject value, String defaultFolder) {
        if (value == null || !value.has("folder") || value.get("folder").isJsonNull()) {
            return defaultFolder;
        }
        String folder = value.get("folder").getAsString();
        return folder == null || folder.isBlank() ? defaultFolder : folder;
    }

    private String legacyFolder(String type) {
        return switch (type) {
            case ReSyncResourceCatalog.CHAT -> "chat";
            case ReSyncResourceCatalog.MOTD_PROFILE -> "motd-profiles";
            case ReSyncResourceCatalog.MESSAGE_RULE -> "message-rules";
            case ReSyncResourceCatalog.RECIPE_DEFINITION -> "recipes";
            case ReSyncResourceCatalog.TEXT_TEMPLATE -> "text-templates";
            case ReSyncResourceCatalog.ADVANCEMENT_TREE -> "advancement-trees";
            case ReSyncResourceCatalog.DIALOG -> "dialogs";
            case ReSyncResourceCatalog.VILLAGE_PROFILE -> "village-profiles";
            case ReSyncResourceCatalog.NPC_DEFINITION -> "npcs";
            case ReSyncResourceCatalog.LOOT_TABLE -> "loot-tables";
            default -> type;
        };
    }

    private void notifyListeners(String type, String id, JsonObject value, boolean deleted) {
        for (ResourceListener listener : listeners) {
            listener.resourceChanged(type, id, value, deleted);
        }
    }

    private void notifySaveFailure(String type, JsonObject value, RuntimeException failure) {
        for (ResourceMutationInterceptor interceptor : interceptors) {
            try {
                interceptor.afterSaveFailure(type, value, failure);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }

    private void notifyDeleteFailure(String type, String id, RuntimeException failure) {
        for (ResourceMutationInterceptor interceptor : interceptors) {
            try {
                interceptor.afterDeleteFailure(type, id, failure);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }

    private JsonObject motdProfileForClient(JsonObject value) {
        JsonObject copy = value.deepCopy();
        if (!text(copy, "iconData").isBlank()) {
            return copy;
        }
        Path icon = resolveIconPath(text(copy, "icon"));
        if (icon == null || !Files.isRegularFile(icon)) {
            return copy;
        }
        try {
            CachedIconData data = cachedIconData(icon);
            if (data == null) {
                return copy;
            }
            copy.addProperty("iconData", data.data());
            if (text(copy, "iconHash").isBlank()) {
                copy.addProperty("iconHash", data.hash());
            }
        } catch (IOException ignored) {
        }
        return copy;
    }

    private CachedIconData cachedIconData(Path icon) throws IOException {
        String key = icon.toAbsolutePath().normalize().toString();
        long modified = Files.getLastModifiedTime(icon).toMillis();
        long size = Files.size(icon);
        CachedIconData cached = iconDataCache.get(key);
        if (cached != null && cached.modified() == modified && cached.size() == size) {
            return cached;
        }
        byte[] bytes = Files.readAllBytes(icon);
        BufferedImage image = validPngIcon(bytes);
        if (image == null) {
            iconDataCache.remove(key);
            return null;
        }
        CachedIconData fresh = new CachedIconData(modified, size, Base64.getEncoder().encodeToString(bytes), sha256(bytes));
        iconDataCache.put(key, fresh);
        return fresh;
    }

    private void prepareMotdIcon(JsonObject value) {
        String iconData = text(value, "iconData");
        if (iconData.isBlank()) {
            return;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(stripImageDataPrefix(iconData));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("MOTD icon must be valid PNG data");
        }
        BufferedImage image = validPngIcon(bytes);
        if (image == null) {
            throw new IllegalArgumentException("MOTD icon must be 64x64 PNG");
        }
        String hash = text(value, "iconHash");
        if (hash.isBlank()) {
            hash = sha256(bytes);
            value.addProperty("iconHash", hash);
        }
        String relative = "motd-icons/" + hash + ".png";
        Path path = resolveIconPath(relative);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, bytes);
            value.addProperty("icon", relative);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to store MOTD icon");
        }
    }

    private BufferedImage validPngIcon(byte[] bytes) {
        if (!hasPngSignature(bytes)) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() != 64 || image.getHeight() != 64) {
                return null;
            }
            return image;
        } catch (IOException e) {
            return null;
        }
    }

    private boolean hasPngSignature(byte[] bytes) {
        return bytes != null && bytes.length >= 8
            && bytes[0] == (byte) 0x89
            && bytes[1] == 0x50
            && bytes[2] == 0x4E
            && bytes[3] == 0x47
            && bytes[4] == 0x0D
            && bytes[5] == 0x0A
            && bytes[6] == 0x1A
            && bytes[7] == 0x0A;
    }

    private Path resolveIconPath(String icon) {
        if (icon == null || icon.isBlank()) {
            return null;
        }
        Path path = Path.of(icon);
        if (!path.isAbsolute()) {
            path = plugin.getDataFolder().toPath().resolve(icon);
        }
        return path.normalize();
    }

    private String stripImageDataPrefix(String data) {
        int comma = data.indexOf(',');
        return data.startsWith("data:image/") && comma >= 0 ? data.substring(comma + 1) : data;
    }

    private String text(JsonObject value, String key) {
        if (value == null || !value.has(key) || value.get(key).isJsonNull()) {
            return "";
        }
        return value.get(key).getAsString();
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @FunctionalInterface
    public interface ResourceListener {
        void resourceChanged(String type, String id, JsonObject value, boolean deleted);
    }

    public interface ResourceMutationInterceptor {
        default void beforeSave(String type, JsonObject value) {
        }

        default void beforeDelete(String type, String id) {
        }

        default void afterSaveFailure(String type, JsonObject value, RuntimeException failure) {
        }

        default void afterDeleteFailure(String type, String id, RuntimeException failure) {
        }
    }
}
