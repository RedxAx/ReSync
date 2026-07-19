package restudio.resync.flow.handler.generic;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.FlowNode;
import restudio.flow.data.GuiDefinition;
import restudio.flow.data.GuiElement;
import restudio.flow.data.Visual;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRuntimeAccess;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.GuiManager;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class MenuHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public MenuHandler() {
        operations.put("menu_create", (context, node) -> {
            String menuId = requireText(context.getInputValue(node, "menu_id", String.class, ""), "Menu ID");
            String title = context.getInputValue(node, "title", String.class, "Menu");
            int rows = context.getInputValue(node, "rows", Integer.class, 1);
            if (title == null || title.isBlank()) throw new IllegalArgumentException("Menu title is required");
            if (rows < 1 || rows > 6) throw new IllegalArgumentException("Menu rows must be between 1 and 6");
            FlowStorage storage = requireStorage();
            if (storage.getGui(menuId) != null) throw new IllegalStateException("Menu already exists: " + menuId);
            GuiDefinition definition = new GuiDefinition(menuId, title, rows);
            storage.saveGui(definition);
            refresh(definition);
        });

        operations.put("menu_set_item", (context, node) -> mutate(context, node, definition -> {
            int slot = context.getInputValue(node, "slot", Integer.class, 0);
            requireSlot(definition, slot);
            replaceSlot(definition, slot, element(context, node, slot, "flow_to_execute"));
        }));

        operations.put("menu_add_item", (context, node) -> mutate(context, node, definition -> {
            int slot = firstFreeSlot(definition);
            if (slot < 0) throw new IllegalStateException("Menu has no empty slot");
            replaceSlot(definition, slot, element(context, node, slot, "flow_to_execute"));
        }));

        operations.put("menu_clear", (context, node) -> mutate(context, node, definition -> definition.getElements().clear()));

        operations.put("menu_open", (context, node) -> {
            GuiManager manager = requireManager();
            Player player = requirePlayer(context, node);
            String menuId = requireText(context.getInputValue(node, "menu_id", String.class, ""), "Menu ID");
            if (requireStorage().getGui(menuId) == null) throw new IllegalArgumentException("Unknown menu: " + menuId);
            manager.openGui(player, menuId);
        });

        operations.put("menu_update", (context, node) -> {
            GuiManager manager = requireManager();
            Player player = requirePlayer(context, node);
            String menuId = requireText(context.getInputValue(node, "menu_id", String.class, ""), "Menu ID");
            if (requireStorage().getGui(menuId) == null) throw new IllegalArgumentException("Unknown menu: " + menuId);
            manager.refreshPlayerGui(player, menuId);
        });

        operations.put("menu_close", (context, node) -> {
            requireManager().closeGui(requirePlayer(context, node));
        });

        operations.put("menu_set_click_sound", (context, node) -> mutate(context, node, definition -> {
            String sound = requireText(context.getInputValue(node, "sound", String.class, "UI_BUTTON_CLICK"), "Menu click sound");
            try {
                Sound.valueOf(sound.toUpperCase(Locale.ROOT).replace('.', '_'));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown menu click sound: " + sound, exception);
            }
            definition.setClickSound(sound);
        }));

        operations.put("menu_set_title", (context, node) -> mutate(context, node,
            definition -> definition.setTitle(requireText(context.getInputValue(node, "title", String.class, "Menu"), "Menu title"))));

        operations.put("menu_set_click_action", (context, node) -> mutate(context, node, definition -> {
            int slot = context.getInputValue(node, "slot", Integer.class, 0);
            requireSlot(definition, slot);
            GuiElement element = elementAt(definition, slot, true);
            element.setFlowId(requireText(context.getInputValue(node, "flow_id", String.class, ""), "Click Flow ID"));
        }));

        operations.put("menu_set_item_with_action", (context, node) -> mutate(context, node, definition -> {
            int slot = context.getInputValue(node, "slot", Integer.class, 0);
            requireSlot(definition, slot);
            replaceSlot(definition, slot, element(context, node, slot, "flow_id"));
        }));

        operations.put("menu_set_enchant", (context, node) -> mutateVisual(context, node,
            visual -> visual.setEnchanted(context.getInputValue(node, "enchanted", Boolean.class, false))));

        operations.put("menu_set_flags", (context, node) -> mutateVisual(context, node,
            visual -> visual.setItemFlags(stringList(context.getInputValue(node, "flags_list")))));

        operations.put("menu_set_custom_model", (context, node) -> mutateVisual(context, node, visual -> {
            int modelData = context.getInputValue(node, "model_data", Integer.class, 0);
            if (modelData < 0) throw new IllegalArgumentException("Custom model data cannot be negative");
            visual.setModelData(modelData);
        }));

        operations.put("menu_set_head_texture", (context, node) -> mutateVisual(context, node, visual -> {
            visual.setMaterial(Material.PLAYER_HEAD.name());
            visual.setHeadTexture(requireText(context.getInputValue(node, "player_name_or_uuid", String.class, ""), "Player name or UUID"));
        }));

        operations.put("menu_fill_pattern", (context, node) -> mutate(context, node, definition -> {
            int start = context.getInputValue(node, "start_slot", Integer.class, 0);
            int end = context.getInputValue(node, "end_slot", Integer.class, start);
            requireSlot(definition, start);
            requireSlot(definition, end);
            if (end < start) throw new IllegalArgumentException("Pattern end slot cannot be before its start slot");
            for (int slot = start; slot <= end; slot++) {
                replaceSlot(definition, slot, element(context, node, slot, null));
            }
        }));

        operations.put("menu_clear_slot", (context, node) -> mutate(context, node, definition -> {
            int slot = context.getInputValue(node, "slot", Integer.class, 0);
            requireSlot(definition, slot);
            if (elementAt(definition, slot, false) == null) throw new IllegalStateException("Menu slot is already empty: " + slot);
            removeSlot(definition, slot);
        }));

        operations.put("menu_get_item", (context, node) -> {
            GuiDefinition definition = requireGui(context.getInputValue(node, "menu_id", String.class, ""));
            int slot = context.getInputValue(node, "slot", Integer.class, 0);
            requireSlot(definition, slot);
            GuiElement element = elementAt(definition, slot, false);
            ItemStack item = element != null ? GuiManager.createItemStack(element.getVisual()) : null;
            context.setOutput(node, "item", item);
        });

        operations.put("menu_get_all_items", (context, node) -> {
            GuiDefinition definition = requireGui(context.getInputValue(node, "menu_id", String.class, ""));
            List<ItemStack> items = new ArrayList<>();
            definition.getElements().stream()
                .filter(element -> element != null && element.getSlots() != null && !element.getSlots().isEmpty())
                .sorted(Comparator.comparingInt(element -> element.getSlots().getFirst()))
                .forEach(element -> {
                    ItemStack item = GuiManager.createItemStack(element.getVisual());
                    if (item != null) items.add(item);
                });
            context.setOutput(node, "items_list", items);
        });

        operations.put("menu_duplicate", (context, node) -> {
            String sourceId = requireText(context.getInputValue(node, "source_menu_id", String.class, ""), "Source menu ID");
            String newId = requireText(context.getInputValue(node, "new_menu_id", String.class, ""), "New menu ID");
            FlowStorage storage = requireStorage();
            GuiDefinition source = storage.getGui(sourceId);
            if (source == null) throw new IllegalArgumentException("Unknown source menu: " + sourceId);
            if (storage.getGui(newId) != null) throw new IllegalStateException("Menu already exists: " + newId);
            GuiDefinition copy = source.copy();
            copy.setId(newId);
            storage.saveGui(copy);
            refresh(copy);
        });

        operations.put("menu_set_close_action", (context, node) -> mutate(context, node,
            definition -> definition.setCloseFlowId(context.getInputValue(node, "flow_id", String.class, ""))));

        operations.put("menu_set_open_action", (context, node) -> mutate(context, node,
            definition -> definition.setOpenFlowId(context.getInputValue(node, "flow_id", String.class, ""))));

        operations.put("menu_set_update_interval", (context, node) -> mutate(context, node, definition -> {
            definition.setUpdateIntervalTicks(Math.max(0, context.getInputValue(node, "interval_ticks", Integer.class, 20)));
            definition.setUpdateFlowId(context.getInputValue(node, "flow_id", String.class, ""));
        }));

        operations.put("menu_get_open_menu_id", (context, node) -> {
            String menuId = requireManager().getOpenGuiId(requirePlayer(context, node));
            context.setOutput(node, "menu_id", menuId != null ? menuId : "");
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("MenuHandler", this);
    }

    @Override
    public void execute(FlowContext context, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> handler = operation != null ? operations.get(operation) : null;
        if (handler == null) {
            throw new IllegalArgumentException("Unknown menu operation: " + operation);
        }
        handler.accept(context, node);
        context.triggerOutput("flow");
    }

    private void mutate(FlowContext context, FlowNode node, Consumer<GuiDefinition> mutation) {
        String menuId = context.getInputValue(node, "menu_id", String.class, "");
        FlowStorage storage = requireStorage();
        GuiDefinition definition = storage.getGui(requireText(menuId, "Menu ID"));
        if (definition == null) throw new IllegalArgumentException("Unknown menu: " + menuId);
        ensureElements(definition);
        mutation.accept(definition);
        storage.saveGui(definition);
        refresh(definition);
    }

    private void mutateVisual(FlowContext context, FlowNode node, Consumer<Visual> mutation) {
        mutate(context, node, definition -> {
            int slot = context.getInputValue(node, "slot", Integer.class, 0);
            requireSlot(definition, slot);
            GuiElement element = elementAt(definition, slot, true);
            if (element.getVisual() == null) {
                element.setVisual(new Visual(Material.AIR.name()));
            }
            mutation.accept(element.getVisual());
        });
    }

    private GuiElement element(FlowContext context, FlowNode node, int slot, String flowPin) {
        Visual visual = new Visual(materialId(context.getInputValue(node, "material")));
        visual.setName(context.getInputValue(node, "name", String.class, ""));
        visual.setLore(stringList(context.getInputValue(node, "lore")));
        GuiElement element = new GuiElement(List.of(slot), visual, null);
        if (flowPin != null) {
            element.setFlowId(context.getInputValue(node, flowPin, String.class, ""));
        }
        return element;
    }

    private GuiElement elementAt(GuiDefinition definition, int slot, boolean create) {
        for (GuiElement element : definition.getElements()) {
            if (element != null && element.getSlots() != null && element.getSlots().contains(slot)) {
                return element;
            }
        }
        if (!create) {
            return null;
        }
        GuiElement element = new GuiElement(List.of(slot), new Visual(Material.AIR.name()), null);
        definition.getElements().add(element);
        return element;
    }

    private void replaceSlot(GuiDefinition definition, int slot, GuiElement replacement) {
        removeSlot(definition, slot);
        definition.getElements().add(replacement);
    }

    private void removeSlot(GuiDefinition definition, int slot) {
        List<GuiElement> empty = new ArrayList<>();
        for (GuiElement element : definition.getElements()) {
            if (element == null || element.getSlots() == null) {
                empty.add(element);
                continue;
            }
            element.getSlots().removeIf(candidate -> candidate != null && candidate == slot);
            if (element.getSlots().isEmpty()) {
                empty.add(element);
            }
        }
        definition.getElements().removeAll(empty);
    }

    private int firstFreeSlot(GuiDefinition definition) {
        for (int slot = 0; slot < definition.getRows() * 9; slot++) {
            if (elementAt(definition, slot, false) == null) {
                return slot;
            }
        }
        return -1;
    }

    private boolean validSlot(GuiDefinition definition, int slot) {
        return slot >= 0 && slot < Math.clamp(definition.getRows(), 1, 6) * 9;
    }

    private String materialId(Object value) {
        if (value instanceof Material material) {
            return material.name();
        }
        Material material = value != null ? Material.matchMaterial(String.valueOf(value)) : null;
        if (material == null || material.isAir()) throw new IllegalArgumentException("Menu item material is invalid: " + value);
        return material.name();
    }

    private List<String> stringList(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream().filter(item -> item != null).map(String::valueOf).toList();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return new ArrayList<>();
        }
        return List.of(String.valueOf(value).split("\\R|,"));
    }

    private GuiDefinition requireGui(String id) {
        String menuId = requireText(id, "Menu ID");
        GuiDefinition definition = requireStorage().getGui(menuId);
        if (definition == null) throw new IllegalArgumentException("Unknown menu: " + menuId);
        ensureElements(definition);
        return definition;
    }

    private FlowStorage requireStorage() {
        FlowStorage storage = FlowRuntimeAccess.getStorage();
        if (storage == null) throw new IllegalStateException("Flow storage is unavailable");
        return storage;
    }

    private void refresh(GuiDefinition definition) {
        GuiManager.refreshOpenGuis(definition);
    }

    private void ensureElements(GuiDefinition definition) {
        if (definition.getElements() == null) {
            definition.setElements(new ArrayList<>());
        }
    }

    private GuiManager requireManager() {
        GuiManager manager = GuiManager.activeManager();
        if (manager == null) throw new IllegalStateException("GUI runtime is unavailable");
        return manager;
    }

    private Player requirePlayer(FlowContext context, FlowNode node) {
        Player player = context.getPlayerInput(node, "player");
        if (player == null) throw new IllegalArgumentException("Player is required");
        return player;
    }

    private int requireSlot(GuiDefinition definition, int slot) {
        if (!validSlot(definition, slot)) throw new IllegalArgumentException("Menu slot must be between 0 and " + (Math.clamp(definition.getRows(), 1, 6) * 9 - 1));
        return slot;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
