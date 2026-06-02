package restudio.resync.advancement;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

public class AdvancementFingerprintReconciler {
    private static final String DELETED = "deleted";
    private static final Type STRING_MAP = new TypeToken<Map<String, String>>() {
    }.getType();
    private final Gson gson = new Gson();
    private final NamespacedKey metadataKey;
    private final AdvancementService service;

    public AdvancementFingerprintReconciler(JavaPlugin plugin, AdvancementService service) {
        metadataKey = new NamespacedKey(plugin, "advancement_criteria");
        this.service = service;
    }

    public void reconcile(Player player, Map<String, JsonObject> trees) {
        revokeChanged(player, trees);
        commit(player, trees);
    }

    public void revokeChanged(Player player, Map<String, JsonObject> trees) {
        Map<String, String> previous = metadata(player);
        Map<String, String> current = fingerprints(trees);
        for (Map.Entry<String, String> entry : previous.entrySet()) {
            if (!entry.getValue().equals(current.get(entry.getKey()))) {
                String[] parts = entry.getKey().split("/", 3);
                if (parts.length == 3) {
                    service.revoke(player, parts[0], parts[1], parts[2]);
                }
            }
        }
    }

    public void commit(Player player, Map<String, JsonObject> trees) {
        Map<String, String> current = fingerprints(trees);
        Map<String, String> committed = new LinkedHashMap<>(metadata(player));
        committed.replaceAll((key, value) -> current.containsKey(key) ? current.get(key) : DELETED);
        committed.putAll(current);
        player.getPersistentDataContainer().set(metadataKey, PersistentDataType.STRING, gson.toJson(committed));
    }

    private Map<String, String> metadata(Player player) {
        String json = player.getPersistentDataContainer().get(metadataKey, PersistentDataType.STRING);
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, String> values = gson.fromJson(json, STRING_MAP);
        return values != null ? values : Map.of();
    }

    private Map<String, String> fingerprints(Map<String, JsonObject> trees) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> treeEntry : trees.entrySet()) {
            if (!treeEntry.getValue().has("nodes") || !treeEntry.getValue().get("nodes").isJsonObject()) {
                continue;
            }
            for (Map.Entry<String, JsonElement> nodeEntry : treeEntry.getValue().getAsJsonObject("nodes").entrySet()) {
                JsonObject node = nodeEntry.getValue().getAsJsonObject();
                if (!node.has("criteria") || !node.get("criteria").isJsonObject()) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> criterionEntry : node.getAsJsonObject("criteria").entrySet()) {
                    result.put(treeEntry.getKey() + "/" + nodeEntry.getKey() + "/" + criterionEntry.getKey(), sha256(gson.toJson(criterionEntry.getValue())));
                }
            }
        }
        return result;
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                result.append(String.format("%02x", current));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
