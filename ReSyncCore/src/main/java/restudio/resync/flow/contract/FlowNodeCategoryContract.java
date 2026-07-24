package restudio.resync.flow.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FlowNodeCategoryContract {
    private static final Map<String, Category> CATEGORIES = createCategories();

    private FlowNodeCategoryContract() {
    }

    public static Category category(String id) {
        Category category = CATEGORIES.get(id);
        if (category == null) throw new IllegalArgumentException("Unknown Flow Node Category: " + id);
        return category;
    }

    public static List<Category> categories() {
        return List.copyOf(CATEGORIES.values());
    }

    private static Map<String, Category> createCategories() {
        Map<String, Category> categories = new LinkedHashMap<>();
        add(categories, "event", "Event", 0xFFFF5555, 100);
        add(categories, "action", "Action", 0xFF5555FF, 200);
        add(categories, "player", "Player", 0xFF55AAFF, 250);
        add(categories, "logic", "Logic", 0xFFFF55FF, 300);
        add(categories, "network", "Network", 0xFF4F8CFF, 350);
        add(categories, "chat", "Chat", 0xFF55FFAA, 375);
        add(categories, "data", "Data", 0xFF55FFFF, 400);
        add(categories, "variable", "Variable", 0xFFFFFF55, 500);
        add(categories, "flow", "Flow", 0xFF7AA2F7, 550);
        add(categories, "function", "Function", 0xFFFFAA55, 600);
        add(categories, "command", "Commands", 0xFF9C7BEF, 650);
        add(categories, "entity", "Entity", 0xFF8B4513, 700);
        add(categories, "block", "Block", 0xFF228B22, 800);
        add(categories, "world", "World", 0xFF228B22, 900);
        add(categories, "inventory", "Inventory", 0xFF00CED1, 1000);
        add(categories, "item", "Item", 0xFF32CD32, 1100);
        add(categories, "scoreboard", "Scoreboard", 0xFFDAA520, 1200);
        add(categories, "trade", "Trade", 0xFFB8860B, 1225);
        add(categories, "npc", "NPC", 0xFFCD853F, 1250);
        add(categories, "loot", "Loot", 0xFFFFA500, 1275);
        add(categories, "menu", "Menus", 0xFF4FA3FF, 1280);
        add(categories, "tab_list", "Tab Lists", 0xFF61B5FF, 1285);
        add(categories, "dialog", "Dialogs", 0xFFC274FF, 1290);
        add(categories, "custom_content", "Custom Content", 0xFF78D64B, 1295);
        add(categories, "recipe", "Recipes", 0xFF71C76F, 1300);
        add(categories, "economy", "Economy", 0xFFFFFF00, 1300);
        add(categories, "advancement", "Advancements", 0xFFFFD34E, 1305);
        add(categories, "text", "Text", 0xFFE5A5FF, 1310);
        add(categories, "permission", "Permission", 0xFFBA55D3, 1400);
        add(categories, "ability", "Ability", 0xFF00BFA5, 1500);
        add(categories, "visual", "Visual", 0xFFFF1493, 1600);
        add(categories, "database", "Database", 0xFF4B0082, 1700);
        add(categories, "http", "HTTP", 0xFFFF6347, 1800);
        add(categories, "discord", "Discord", 0xFF7289DA, 1900);
        add(categories, "utility", "Utility", 0xFFA9A9A9, 2000);
        add(categories, "world_gen", "World Gen", 0xFF228B22, 2100);
        return Collections.unmodifiableMap(categories);
    }

    private static void add(Map<String, Category> categories, String id, String displayName, int color, int priority) {
        categories.put(id, new Category(id, displayName, color, priority));
    }

    public record Category(String id, String displayName, int color, int priority) {
    }
}
