package restudio.resync.resource;

import java.util.Locale;
import java.util.Set;

public final class RecipeSchema {
    private static final Set<String> LIST_TYPES = Set.of("shapeless", "furnace", "blasting", "smoking", "campfire", "campfire_cooking", "stonecutting", "stonecutter");
    private static final Set<String> SMITHING_TYPES = Set.of("smithing", "smithing_transform", "smithing_trim", "trim");

    private RecipeSchema() {
    }

    public static Kind kind(String type) {
        String normalized = type != null ? type.trim().toLowerCase(Locale.ROOT) : "";
        if ("shaped".equals(normalized)) {
            return Kind.SHAPED;
        }
        if (LIST_TYPES.contains(normalized)) {
            return Kind.LIST;
        }
        if (SMITHING_TYPES.contains(normalized)) {
            return Kind.SMITHING;
        }
        return Kind.UNKNOWN;
    }

    public enum Kind {
        SHAPED,
        LIST,
        SMITHING,
        UNKNOWN
    }
}
