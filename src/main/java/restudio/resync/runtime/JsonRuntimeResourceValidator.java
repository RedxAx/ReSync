package restudio.resync.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;
import restudio.resync.advancement.AdvancementTreeValidator;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.flow.util.TextFormatter;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.storage.StorageSafety;

import java.util.Locale;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class JsonRuntimeResourceValidator implements ReSyncJsonResourceStorage.ResourceMutationInterceptor {
    private static final Set<String> RECIPE_TYPES = Set.of("shaped", "shapeless", "furnace", "smelting", "blasting", "blast", "smoking", "smoker",
        "campfire", "campfire_cooking", "stonecutting", "stonecutter", "smithing", "smithing_transform", "smithing_trim", "trim");
    private final CustomContentService customContentService;
    private final AdvancementTreeValidator advancementTreeValidator = new AdvancementTreeValidator();

    public JsonRuntimeResourceValidator(CustomContentService customContentService) {
        this.customContentService = customContentService;
    }

    public void validate(String type, JsonObject value) {
        if (value == null) {
            throw new IllegalArgumentException("Resource value is required");
        }
        validateId(rawText(value, "id"));
        switch (type != null ? type : "") {
            case ReSyncResourceCatalog.TRADE_PROFILE -> validateTradeProfile(value);
            case ReSyncResourceCatalog.LOOT_TABLE -> validateLootTable(value);
            case ReSyncResourceCatalog.NPC_DEFINITION -> validateNpcDefinition(value);
            case ReSyncResourceCatalog.RECIPE_DEFINITION -> validateRecipeDefinition(value);
            case ReSyncResourceCatalog.DIALOG -> validateDialogDefinition(value);
            case ReSyncResourceCatalog.ADVANCEMENT_TREE -> advancementTreeValidator.validate(Map.of(text(value, "id"), value));
            case ReSyncResourceCatalog.TEXT_TEMPLATE -> validateText(value);
            default -> validateCommonStructure(value);
        }
    }

    private void validateText(JsonObject value) {
        String kind = text(value, "kind").toLowerCase(Locale.ROOT);
        if (kind.isBlank() || "animation".equals(kind)) {
            validateCommonStructure(value);
            return;
        }
        if ("list".equals(kind)) {
            JsonArray values = requiredArray(value, "values", "Text list values");
            for (int index = 0; index < values.size(); index++) {
                if (!values.get(index).isJsonPrimitive() || !values.get(index).getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("Text list value " + index + " must be text");
                }
            }
            return;
        }
        if ("map".equals(kind)) {
            JsonArray entries = requiredArray(value, "entries", "Text map entries");
            Set<String> keys = new HashSet<>();
            for (int index = 0; index < entries.size(); index++) {
                JsonObject entry = requiredObject(entries.get(index), "Text map entry " + index);
                String key = text(entry, "key").trim();
                if (key.isBlank()) {
                    throw new IllegalArgumentException("Text map entry " + index + " requires a key");
                }
                if (!keys.add(key.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Text map contains the key more than once: " + key);
                }
                if (!entry.has("value") || !entry.get("value").isJsonPrimitive() || !entry.getAsJsonPrimitive("value").isString()) {
                    throw new IllegalArgumentException("Text map value for " + key + " must be text");
                }
            }
            return;
        }
        throw new IllegalArgumentException("Unknown Text type: " + kind);
    }

    @Override
    public void beforeSave(String type, JsonObject value) {
        if (ReSyncResourceCatalog.NPC_DEFINITION.equals(type)) {
            migrateNpcDefinition(value);
        }
        validate(type, value);
    }

    private void migrateNpcDefinition(JsonObject definition) {
        if (definition == null) {
            return;
        }
        if (definition.has("hooks") && definition.get("hooks").isJsonObject()) {
            JsonObject hooks = definition.getAsJsonObject("hooks");
            Map<String, String> legacyHooks = Map.of(
                "spawnFlow", "spawnAction",
                "interactFlow", "interactAction",
                "rightClickFlow", "rightClickAction",
                "leftClickFlow", "leftClickAction",
                "damageFlow", "damageAction",
                "deathFlow", "deathAction",
                "despawnFlow", "despawnAction"
            );
            legacyHooks.forEach((legacy, current) -> {
                if (!hooks.has(current) && hooks.has(legacy)) {
                    hooks.add(current, hooks.get(legacy).deepCopy());
                }
                hooks.remove(legacy);
            });
        }
        JsonObject skin = definition.has("skin") && definition.get("skin").isJsonObject() ? definition.getAsJsonObject("skin") : new JsonObject();
        Map<String, String> legacySkinFields = Map.of(
            "skinUsername", "username",
            "skinUuid", "uuid",
            "skinTexture", "texture",
            "skinSignature", "signature"
        );
        legacySkinFields.forEach((legacy, current) -> {
            if (!skin.has(current) && definition.has(legacy)) {
                skin.add(current, definition.get(legacy).deepCopy());
            }
            definition.remove(legacy);
        });
        if (!skin.isEmpty()) {
            definition.add("skin", skin);
        }
    }

    private void validateId(String id) {
        String validated = StorageSafety.validateId(id);
        if (!validated.equals(id)) {
            throw new IllegalArgumentException("Resource ID must not contain surrounding whitespace");
        }
    }

    private void validateTradeProfile(JsonObject profile) {
        validateTradeRegistry(profile, "profession", true);
        validateTradeRegistry(profile, "villagerType", false);
        int level = integer(profile, "level", 1);
        if (level < 1 || level > 5) {
            throw new IllegalArgumentException("Trade profile level must be between 1 and 5");
        }
        int defaultMaxUses = integer(profile, "maxUses", 12);
        if (defaultMaxUses <= 0) {
            throw new IllegalArgumentException("Trade profile maxUses must be positive");
        }
        if (profile.has("hooks") && !profile.get("hooks").isJsonObject()) {
            throw new IllegalArgumentException("Trade profile hooks must be an object");
        }
        if (!profile.has("offers")) {
            return;
        }
        if (!profile.get("offers").isJsonArray()) {
            throw new IllegalArgumentException("Trade profile offers must be an array");
        }
        JsonArray offers = profile.getAsJsonArray("offers");
        for (int index = 0; index < offers.size(); index++) {
            JsonElement element = offers.get(index);
            if (element == null || !element.isJsonObject()) {
                throw new IllegalArgumentException("Trade offer " + index + " must be an object");
            }
            validateTradeOffer(element.getAsJsonObject(), index, defaultMaxUses);
        }
    }

    private void validateTradeOffer(JsonObject offer, int index, int defaultMaxUses) {
        validateItemReference(offer, "cost", index, true);
        validateItemReference(offer, "result", index, true);
        validateItemReference(offer, "cost2", index, false);
        validatePositive(offer, "costAmount", 1, index);
        validatePositive(offer, "resultAmount", 1, index);
        if (!text(offer, "cost2").isBlank()) {
            validatePositive(offer, "cost2Amount", 1, index);
        }
        validatePositive(offer, "maxUses", defaultMaxUses, index);
        double weight = decimal(offer, "weight", 1.0);
        if (!Double.isFinite(weight) || weight < 0.0) {
            throw new IllegalArgumentException("Trade offer " + index + " weight must be a finite non-negative number");
        }
        double priceMultiplier = decimal(offer, "priceMultiplier", 0.05);
        if (!Double.isFinite(priceMultiplier) || priceMultiplier < 0.0) {
            throw new IllegalArgumentException("Trade offer " + index + " priceMultiplier must be a finite non-negative number");
        }
        if (integer(offer, "experience", 0) < 0) {
            throw new IllegalArgumentException("Trade offer " + index + " experience must be non-negative");
        }
    }

    private void validateLootTable(JsonObject table) {
        validateObjectField(table, "hooks");
        validateObjectField(table, "trigger");
        JsonArray pools = requiredArray(table, "pools", "Loot table pools");
        for (int poolIndex = 0; poolIndex < pools.size(); poolIndex++) {
            JsonObject pool = requiredObject(pools.get(poolIndex), "Loot pool " + poolIndex);
            if (integer(pool, "rolls", 1) < 0) {
                throw new IllegalArgumentException("Loot pool " + poolIndex + " rolls must be non-negative");
            }
            JsonArray entries = requiredArray(pool, "entries", "Loot pool " + poolIndex + " entries");
            for (int entryIndex = 0; entryIndex < entries.size(); entryIndex++) {
                JsonObject entry = requiredObject(entries.get(entryIndex), "Loot entry " + poolIndex + ':' + entryIndex);
                String label = "Loot entry " + poolIndex + ':' + entryIndex;
                validateAvailableItem(text(entry, "item"), label + " item", true);
                int minimum = integer(entry, "minAmount", integer(entry, "amount", 1));
                int maximum = integer(entry, "maxAmount", minimum);
                if (minimum <= 0 || maximum < minimum) {
                    throw new IllegalArgumentException(label + " amount range is invalid");
                }
                if (integer(entry, "weight", 1) < 0) {
                    throw new IllegalArgumentException(label + " weight must be non-negative");
                }
                double chance = decimal(entry, "chance", 100.0);
                if (!Double.isFinite(chance) || chance < 0.0 || chance > 100.0) {
                    throw new IllegalArgumentException(label + " chance must be between 0 and 100");
                }
                validateObjectField(entry, "components");
            }
        }
    }

    private void validateNpcDefinition(JsonObject definition) {
        String displayName = text(definition, "displayName");
        if (!displayName.isBlank()) {
            try {
                TextFormatter.parse(displayName);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("NPC displayName is invalid", exception);
            }
        }
        String entityType = text(definition, "entityType");
        if (entityType.isBlank()) {
            throw new IllegalArgumentException("NPC entityType is required");
        }
        String normalized = localId(entityType).replace('-', '_').toUpperCase(Locale.ROOT);
        EntityType type;
        try {
            type = EntityType.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown NPC entityType: " + entityType, exception);
        }
        if (type != EntityType.PLAYER && !type.isSpawnable()) {
            throw new IllegalArgumentException("NPC entityType cannot be spawned: " + entityType);
        }
        if (type == EntityType.PLAYER && !bool(definition, "invulnerable", true)) {
            throw new IllegalArgumentException("Player NPCs must be invulnerable because they are packet-backed");
        }
        if (type == EntityType.PLAYER && bool(definition, "ai", false)) {
            throw new IllegalArgumentException("Player NPCs do not support entity AI");
        }
        validateObjectField(definition, "hooks");
        validateObjectField(definition, "equipment");
        validateObjectField(definition, "location");
        validateObjectField(definition, "skin");
        validateObjectField(definition, "links");
        double followRange = decimal(definition, "followRange", 12.0);
        if (!Double.isFinite(followRange) || followRange <= 0.0) {
            throw new IllegalArgumentException("NPC followRange must be a finite positive number");
        }
        if (definition.has("equipment") && definition.get("equipment").isJsonObject()) {
            JsonObject equipment = definition.getAsJsonObject("equipment");
            for (String slot : Set.of("mainHand", "offHand", "helmet", "chestplate", "leggings", "boots")) {
                validateAvailableItem(text(equipment, slot), "NPC equipment " + slot, false);
            }
        }
        if (definition.has("location") && definition.get("location").isJsonObject()) {
            JsonObject location = definition.getAsJsonObject("location");
            for (String coordinate : Set.of("x", "y", "z", "yaw", "pitch")) {
                double coordinateValue = decimal(location, coordinate, 0.0);
                if (!Double.isFinite(coordinateValue)) {
                    throw new IllegalArgumentException("NPC location " + coordinate + " must be finite");
                }
            }
        }
        String spawnMode = text(definition, "spawnMode").replace('-', '_').toLowerCase(Locale.ROOT);
        if (!Set.of("", "none", "manual", "startup", "automatic", "auto", "server_start").contains(spawnMode)) {
            throw new IllegalArgumentException("Unknown NPC spawnMode: " + spawnMode);
        }
        if (Set.of("startup", "automatic", "auto", "server_start").contains(spawnMode)) {
            JsonObject location = definition.has("location") && definition.get("location").isJsonObject() ? definition.getAsJsonObject("location") : null;
            if (location == null || text(location, "world").isBlank()) {
                throw new IllegalArgumentException("Startup NPC location.world is required");
            }
        }
        if (definition.has("hooks") && definition.get("hooks").isJsonObject()) {
            for (Map.Entry<String, JsonElement> hook : definition.getAsJsonObject("hooks").entrySet()) {
                validateNpcHook(hook.getKey(), hook.getValue());
            }
        }
        if (type == EntityType.PLAYER) {
            JsonObject skin = definition.has("skin") && definition.get("skin").isJsonObject() ? definition.getAsJsonObject("skin") : new JsonObject();
            String username = !text(skin, "username").isBlank() ? text(skin, "username") : text(definition, "skinUsername");
            if (!username.isBlank() && !username.matches("[A-Za-z0-9_]{1,16}")) {
                throw new IllegalArgumentException("Player NPC skin username is invalid");
            }
            String uuid = !text(skin, "uuid").isBlank() ? text(skin, "uuid") : text(definition, "skinUuid");
            if (!uuid.isBlank() && !uuid.replace("-", "").matches("[0-9a-fA-F]{32}")) {
                throw new IllegalArgumentException("Player NPC skin UUID is invalid");
            }
            String texture = !text(skin, "texture").isBlank() ? text(skin, "texture") : text(definition, "skinTexture");
            String signature = !text(skin, "signature").isBlank() ? text(skin, "signature") : text(definition, "skinSignature");
            if (texture.isBlank() && !signature.isBlank()) {
                throw new IllegalArgumentException("Player NPC skin signature requires a texture value");
            }
            int skinSources = (username.isBlank() ? 0 : 1) + (uuid.isBlank() ? 0 : 1) + (texture.isBlank() ? 0 : 1);
            if (skinSources > 1) {
                throw new IllegalArgumentException("Player NPC skin must use exactly one source: username, UUID, or texture");
            }
        }
        if (!npcLink(definition, "dialog").isBlank() && !npcLink(definition, "tradeProfile").isBlank()) {
            throw new IllegalArgumentException("NPC interaction cannot open both a dialog and a trade profile");
        }
    }

    private String npcLink(JsonObject definition, String key) {
        if (definition.has("links") && definition.get("links").isJsonObject()) {
            String linked = text(definition.getAsJsonObject("links"), key);
            if (!linked.isBlank() && !"none".equalsIgnoreCase(linked)) {
                return linked;
            }
        }
        String direct = text(definition, key);
        return "none".equalsIgnoreCase(direct) ? "" : direct;
    }

    private void validateNpcHook(String key, JsonElement value) {
        if (value == null || value.isJsonNull() || value.isJsonObject()) {
            return;
        }
        if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                validateNpcHook(key, element);
            }
            return;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("NPC hook must be a Flow ID, function action, or action list: " + key);
        }
    }

    private void validateRecipeDefinition(JsonObject recipe) {
        String type = text(recipe, "type").toLowerCase(Locale.ROOT);
        if (type.isBlank() || !RECIPE_TYPES.contains(type)) {
            throw new IllegalArgumentException("Unsupported recipe type: " + type);
        }
        JsonObject output = requiredObject(recipe.get("output"), "Recipe output");
        validateRecipeChoice(output, "Recipe output");
        if (integer(output, "amount", 1) <= 0) {
            throw new IllegalArgumentException("Recipe output amount must be positive");
        }
        if ("shaped".equals(type)) {
            validateShapedRecipe(recipe);
            return;
        }
        if (Set.of("smithing", "smithing_transform", "smithing_trim", "trim").contains(type)) {
            for (String key : Set.of("template", "base", "addition")) {
                if (!recipe.has(key)) {
                    throw new IllegalArgumentException("Smithing recipe requires " + key);
                }
                validateRecipeChoice(recipe.get(key), "Smithing " + key);
            }
            return;
        }
        JsonArray ingredients = requiredArray(recipe, "ingredients", "Recipe ingredients");
        if (ingredients.isEmpty()) {
            throw new IllegalArgumentException("Recipe requires at least one ingredient");
        }
        for (int index = 0; index < ingredients.size(); index++) {
            validateRecipeChoice(ingredients.get(index), "Recipe ingredient " + index);
        }
    }

    private void validateShapedRecipe(JsonObject recipe) {
        JsonArray shape = requiredArray(recipe, "shape", "Shaped recipe shape");
        JsonObject keys = recipe.has("keys") && recipe.get("keys").isJsonObject() ? recipe.getAsJsonObject("keys") : null;
        if (shape.isEmpty() || shape.size() > 3 || keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("Shaped recipe requires one to three rows and key mappings");
        }
        int width = -1;
        for (JsonElement rowElement : shape) {
            if (!rowElement.isJsonPrimitive() || !rowElement.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("Shaped recipe rows must be strings");
            }
            String row = rowElement.getAsString();
            if (row.isEmpty() || row.length() > 3 || width >= 0 && row.length() != width) {
                throw new IllegalArgumentException("Shaped recipe rows must share a width between one and three");
            }
            width = row.length();
            for (int index = 0; index < row.length(); index++) {
                char symbol = row.charAt(index);
                if (symbol != ' ' && !keys.has(String.valueOf(symbol))) {
                    throw new IllegalArgumentException("Shaped recipe symbol has no ingredient: " + symbol);
                }
            }
        }
        for (Map.Entry<String, JsonElement> entry : keys.entrySet()) {
            if (entry.getKey().length() != 1 || entry.getKey().charAt(0) == ' ') {
                throw new IllegalArgumentException("Shaped recipe keys must be one non-space character");
            }
            validateRecipeChoice(entry.getValue(), "Shaped recipe key " + entry.getKey());
        }
    }

    private void validateRecipeChoice(JsonElement element, String label) {
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException(label + " is required");
        }
        if (element.isJsonPrimitive()) {
            validateAvailableItem(element.getAsString(), label, true);
            return;
        }
        if (element.isJsonArray()) {
            if (element.getAsJsonArray().isEmpty()) {
                throw new IllegalArgumentException(label + " choices cannot be empty");
            }
            for (JsonElement choice : element.getAsJsonArray()) {
                validateRecipeChoice(choice, label);
            }
            return;
        }
        JsonObject choice = requiredObject(element, label);
        String reference = firstText(choice, "reference", "ref", "item", "material");
        String tag = text(choice, "tag");
        String contentId = firstText(choice, "contentId", "content_id", "externalId", "external_id");
        if (reference.isBlank() && tag.isBlank() && contentId.isBlank() && (!choice.has("materials") || !choice.get("materials").isJsonArray())) {
            throw new IllegalArgumentException(label + " must identify an item, tag, or material choices");
        }
        if (!reference.isBlank() && tag.isBlank()) {
            validateAvailableItem(reference, label, true);
        }
        if (integer(choice, "amount", 1) <= 0) {
            throw new IllegalArgumentException(label + " amount must be positive");
        }
    }

    private void validateDialogDefinition(JsonObject dialog) {
        String type = text(dialog, "type");
        if (type.isBlank()) {
            throw new IllegalArgumentException("Dialog type is required");
        }
        for (String field : Set.of("body", "inputs", "actions")) {
            if (dialog.has(field) && !dialog.get(field).isJsonArray()) {
                throw new IllegalArgumentException("Dialog " + field + " must be an array");
            }
            if (dialog.has(field)) {
                int index = 0;
                for (JsonElement element : dialog.getAsJsonArray(field)) {
                    requiredObject(element, "Dialog " + field + " entry " + index++);
                }
            }
        }
        int columns = integer(dialog, "columns", 1);
        if (columns <= 0) {
            throw new IllegalArgumentException("Dialog columns must be positive");
        }
    }

    private void validateCommonStructure(JsonObject value) {
        validateObjectField(value, "hooks");
    }

    private void validateItemReference(JsonObject offer, String key, int index, boolean required) {
        String reference = text(offer, key);
        validateAvailableItem(reference, "Trade offer " + index + ' ' + key, required);
    }

    private void validateAvailableItem(String reference, String label, boolean required) {
        if (reference == null || reference.isBlank()) {
            if (required) {
                throw new IllegalArgumentException(label + " is required");
            }
            return;
        }
        boolean available = customContentService != null
            ? customContentService.createReferencedItem(reference, 1) != null
            : RuntimeMaterialResolver.itemMaterial(reference) != null;
        if (!available) {
            throw new IllegalArgumentException(label + " is unavailable: " + reference);
        }
    }

    private void validateObjectField(JsonObject value, String key) {
        if (value.has(key) && !value.get(key).isJsonObject()) {
            throw new IllegalArgumentException(key + " must be an object");
        }
    }

    private JsonArray requiredArray(JsonObject value, String key, String label) {
        if (value == null || !value.has(key) || !value.get(key).isJsonArray()) {
            throw new IllegalArgumentException(label + " must be an array");
        }
        return value.getAsJsonArray(key);
    }

    private JsonObject requiredObject(JsonElement value, String label) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private String firstText(JsonObject value, String... keys) {
        for (String key : keys) {
            String result = text(value, key);
            if (!result.isBlank()) {
                return result;
            }
        }
        return "";
    }

    private String localId(String value) {
        int namespace = value.indexOf(':');
        return namespace >= 0 ? value.substring(namespace + 1) : value;
    }

    private void validatePositive(JsonObject offer, String key, int fallback, int index) {
        if (integer(offer, key, fallback) <= 0) {
            throw new IllegalArgumentException("Trade offer " + index + ' ' + key + " must be positive");
        }
    }

    private void validateTradeRegistry(JsonObject value, String key, boolean profession) {
        String raw = text(value, key);
        if (raw.isBlank()) {
            return;
        }
        String normalized = raw;
        int namespace = normalized.indexOf(':');
        if (namespace >= 0) {
            normalized = normalized.substring(namespace + 1);
        }
        if (Bukkit.getServer() == null) {
            try {
                if (profession) {
                    Villager.Profession.valueOf(normalized.replace('-', '_').toUpperCase(Locale.ROOT));
                } else {
                    Villager.Type.valueOf(normalized.replace('-', '_').toUpperCase(Locale.ROOT));
                }
                return;
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown villager " + key + ": " + raw, exception);
            }
        }
        Registry<?> registry = profession ? Registry.VILLAGER_PROFESSION : Registry.VILLAGER_TYPE;
        NamespacedKey namespacedKey = NamespacedKey.minecraft(normalized.replace('_', '-').toLowerCase(Locale.ROOT));
        if (registry.get(namespacedKey) == null) {
            throw new IllegalArgumentException("Unknown villager " + key + ": " + raw);
        }
    }

    private String text(JsonObject value, String key) {
        return rawText(value, key).trim();
    }

    private String rawText(JsonObject value, String key) {
        if (!value.has(key) || value.get(key).isJsonNull() || !value.get(key).isJsonPrimitive()) {
            return "";
        }
        try {
            return value.get(key).getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private int integer(JsonObject value, String key, int fallback) {
        if (!value.has(key) || value.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return value.get(key).getAsInt();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Field " + key + " must be an integer", exception);
        }
    }

    private double decimal(JsonObject value, String key, double fallback) {
        if (!value.has(key) || value.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return value.get(key).getAsDouble();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Field " + key + " must be a number", exception);
        }
    }

    private boolean bool(JsonObject value, String key, boolean fallback) {
        if (value == null || key == null || !value.has(key) || value.get(key).isJsonNull()) {
            return fallback;
        }
        JsonElement element = value.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Field " + key + " must be a boolean");
        }
        return element.getAsBoolean();
    }
}
