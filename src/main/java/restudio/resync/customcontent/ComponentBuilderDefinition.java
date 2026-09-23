package restudio.resync.customcontent;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record ComponentBuilderDefinition(String id, Scope scope, Map<String, Object> components) {
    private static final Gson GSON = new Gson();
    private static final Set<String> SCOPES = Set.of("dynamic", "category", "tag", "item");

    public ComponentBuilderDefinition {
        id = id == null ? "" : id.trim();
        scope = scope == null ? new Scope("dynamic", "") : scope;
        components = components == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(components));
        if (id.isBlank()) {
            throw new IllegalArgumentException("Component Builder ID is required");
        }
        if (!SCOPES.contains(scope.kind())) {
            throw new IllegalArgumentException("Unknown Component Builder scope: " + scope.kind());
        }
        if (!"dynamic".equals(scope.kind()) && scope.value().isBlank()) {
            throw new IllegalArgumentException("Component Builder scope value is required");
        }
        for (String component : components.keySet()) {
            if (component == null || !component.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("Component Builder contains an invalid component ID: " + component);
            }
        }
    }

    public static ComponentBuilderDefinition from(JsonObject value) {
        if (value == null) {
            throw new IllegalArgumentException("Component Builder is required");
        }
        String id = text(value, "id");
        JsonObject scopeValue = value.has("scope") && value.get("scope").isJsonObject()
            ? value.getAsJsonObject("scope") : new JsonObject();
        Scope scope = new Scope(text(scopeValue, "kind"), text(scopeValue, "value"));
        Map<String, Object> components = new LinkedHashMap<>();
        if (value.has("components") && value.get("components").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject("components").entrySet()) {
                components.put(entry.getKey(), GSON.fromJson(entry.getValue(), Object.class));
            }
        }
        return new ComponentBuilderDefinition(id, scope, components);
    }

    public boolean supports(ItemStack item, CustomContentService content) {
        if (item == null) {
            return false;
        }
        return switch (scope.kind()) {
            case "dynamic" -> true;
            case "item" -> content != null ? content.matchesItemReference(item, scope.value())
                : matchesMaterial(item, scope.value());
            case "tag" -> matchesTag(item, scope.value());
            case "category" -> matchesCategory(item, scope.value(), content);
            default -> false;
        };
    }

    public String validationMaterial() {
        if (!"item".equals(scope.kind())) {
            return "";
        }
        Material material = Material.matchMaterial(scope.value());
        return material != null && material.isItem() && !material.isAir() ? material.name() : "";
    }

    private boolean matchesCategory(ItemStack item, String category, CustomContentService content) {
        String normalized = category.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        String customType = content != null ? content.itemContentType(item) : "";
        if (!customType.isBlank() && normalized.equals(customType)) {
            return true;
        }
        String material = item.getType().name();
        return switch (normalized) {
            case "item", "items", "all" -> item.getType().isItem() && !item.getType().isAir();
            case "block", "blocks" -> item.getType().isBlock();
            case "armor" -> contains(material, "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "ELYTRA", "SHIELD");
            case "weapon", "weapons" -> contains(material, "SWORD", "AXE", "TRIDENT", "MACE", "BOW", "CROSSBOW");
            case "tool", "tools" -> contains(material, "PICKAXE", "AXE", "SHOVEL", "HOE", "SHEARS", "BRUSH", "FISHING_ROD");
            case "projectile", "projectiles" -> contains(material, "ARROW", "FIREWORK_ROCKET", "SNOWBALL", "EGG", "TRIDENT", "ENDER_PEARL");
            case "food" -> item.getType().isEdible();
            default -> false;
        };
    }

    private static boolean matchesMaterial(ItemStack item, String reference) {
        Material material = Material.matchMaterial(reference);
        return material != null && item.getType() == material;
    }

    private static boolean matchesTag(ItemStack item, String value) {
        String normalized = value.startsWith("#") ? value.substring(1) : value;
        NamespacedKey key = NamespacedKey.fromString(normalized.contains(":") ? normalized : "minecraft:" + normalized);
        if (key == null) {
            return false;
        }
        Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
        return tag != null && tag.isTagged(item.getType());
    }

    private static boolean contains(String value, String... parts) {
        for (String part : parts) {
            if (value.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static String text(JsonObject value, String key) {
        if (value == null || !value.has(key) || value.get(key).isJsonNull()) {
            return "";
        }
        try {
            return value.get(key).getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    public record Scope(String kind, String value) {
        public Scope {
            kind = kind == null || kind.isBlank() ? "dynamic" : kind.trim().toLowerCase(Locale.ROOT);
            value = value == null ? "" : value.trim();
        }
    }
}
