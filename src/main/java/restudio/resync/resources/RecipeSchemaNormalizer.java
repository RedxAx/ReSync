package restudio.resync.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import restudio.resync.resource.RecipeSchema;

import java.util.List;

public final class RecipeSchemaNormalizer {
    private static final List<String> SYMBOLS = List.of("A", "B", "C", "D", "E", "F", "G", "H", "I");

    private RecipeSchemaNormalizer() {
    }

    public static boolean normalize(JsonObject recipe) {
        if (recipe == null) {
            return false;
        }
        return switch (RecipeSchema.kind(text(recipe.get("type")))) {
            case SHAPED -> normalizeShaped(recipe);
            case LIST -> normalizeList(recipe);
            case SMITHING -> {
                boolean changed = recipe.remove("shape") != null;
                changed |= recipe.remove("keys") != null;
                changed |= recipe.remove("ingredients") != null;
                yield changed;
            }
            case UNKNOWN -> false;
        };
    }

    private static boolean normalizeList(JsonObject recipe) {
        JsonArray source = recipe.has("ingredients") && recipe.get("ingredients").isJsonArray()
            ? recipe.getAsJsonArray("ingredients")
            : ingredientsFromShape(recipe);
        JsonArray normalized = compact(source);
        boolean changed = !normalized.equals(source) || !recipe.has("ingredients");
        recipe.add("ingredients", normalized);
        changed |= recipe.remove("shape") != null;
        changed |= recipe.remove("keys") != null;
        return changed;
    }

    private static boolean normalizeShaped(JsonObject recipe) {
        boolean hasShape = recipe.has("shape") && recipe.get("shape").isJsonArray() && !recipe.getAsJsonArray("shape").isEmpty();
        boolean hasKeys = recipe.has("keys") && recipe.get("keys").isJsonObject() && !recipe.getAsJsonObject("keys").isEmpty();
        boolean changed = false;
        if (!hasShape || !hasKeys) {
            JsonArray ingredients = recipe.has("ingredients") && recipe.get("ingredients").isJsonArray() ? compact(recipe.getAsJsonArray("ingredients")) : new JsonArray();
            JsonArray shape = new JsonArray();
            JsonObject keys = new JsonObject();
            for (int row = 0; row < 3 && row * 3 < ingredients.size(); row++) {
                StringBuilder line = new StringBuilder();
                for (int column = 0; column < 3; column++) {
                    int index = row * 3 + column;
                    if (index < ingredients.size()) {
                        String symbol = SYMBOLS.get(index);
                        line.append(symbol);
                        keys.add(symbol, ingredients.get(index).deepCopy());
                    } else {
                        line.append(' ');
                    }
                }
                shape.add(line.toString());
            }
            recipe.add("shape", shape);
            recipe.add("keys", keys);
            changed = true;
        }
        changed |= recipe.remove("ingredients") != null;
        return changed;
    }

    private static JsonArray ingredientsFromShape(JsonObject recipe) {
        JsonArray ingredients = new JsonArray();
        if (!recipe.has("shape") || !recipe.get("shape").isJsonArray() || !recipe.has("keys") || !recipe.get("keys").isJsonObject()) {
            return ingredients;
        }
        JsonObject keys = recipe.getAsJsonObject("keys");
        for (JsonElement rowElement : recipe.getAsJsonArray("shape")) {
            String row = text(rowElement);
            for (int index = 0; index < row.length(); index++) {
                JsonElement ingredient = keys.get(String.valueOf(row.charAt(index)));
                if (!empty(ingredient)) {
                    ingredients.add(ingredient.deepCopy());
                }
            }
        }
        return ingredients;
    }

    private static JsonArray compact(JsonArray source) {
        JsonArray compacted = new JsonArray();
        if (source == null) {
            return compacted;
        }
        for (JsonElement ingredient : source) {
            if (!empty(ingredient)) {
                compacted.add(ingredient.deepCopy());
            }
        }
        return compacted;
    }

    private static boolean empty(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return true;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return value.getAsString().isBlank();
        }
        return value.isJsonObject() && value.getAsJsonObject().isEmpty();
    }

    private static String text(JsonElement value) {
        return value != null && !value.isJsonNull() && value.isJsonPrimitive() ? value.getAsString() : "";
    }
}
