package restudio.resync.flow.nodes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.HashMap;
import java.util.Map;

public class TextFormattingNodes {

    private static final Map<String, NamedTextColor> LEGACY_COLORS = new HashMap<>();

    static {
        LEGACY_COLORS.put("0", NamedTextColor.BLACK);
        LEGACY_COLORS.put("1", NamedTextColor.DARK_BLUE);
        LEGACY_COLORS.put("2", NamedTextColor.DARK_GREEN);
        LEGACY_COLORS.put("3", NamedTextColor.DARK_AQUA);
        LEGACY_COLORS.put("4", NamedTextColor.DARK_RED);
        LEGACY_COLORS.put("5", NamedTextColor.DARK_PURPLE);
        LEGACY_COLORS.put("6", NamedTextColor.GOLD);
        LEGACY_COLORS.put("7", NamedTextColor.GRAY);
        LEGACY_COLORS.put("8", NamedTextColor.DARK_GRAY);
        LEGACY_COLORS.put("9", NamedTextColor.BLUE);
        LEGACY_COLORS.put("a", NamedTextColor.GREEN);
        LEGACY_COLORS.put("b", NamedTextColor.AQUA);
        LEGACY_COLORS.put("c", NamedTextColor.RED);
        LEGACY_COLORS.put("d", NamedTextColor.LIGHT_PURPLE);
        LEGACY_COLORS.put("e", NamedTextColor.YELLOW);
        LEGACY_COLORS.put("f", NamedTextColor.WHITE);
    }

    @DefineNode(id = "format_color", displayName = "Color", category = NodeDefinition.NodeCategory.VISUAL,
            inputs = {@FlowPin(name = "text", dataType = FlowType.STRING), @FlowPin(name = "color", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.STRING)})
    public void color(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
        String colorName = ctx.getInputValue(node, "color", String.class, "white");
        ctx.setOutput(node, "result", text.color(parseColor(colorName)));
    }

    @DefineNode(id = "format_bold", displayName = "Bold", category = NodeDefinition.NodeCategory.VISUAL,
            inputs = {@FlowPin(name = "text", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.STRING)})
    public void bold(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
        ctx.setOutput(node, "result", text.decoration(TextDecoration.BOLD, true));
    }

    @DefineNode(id = "format_italic", displayName = "Italic", category = NodeDefinition.NodeCategory.VISUAL,
            inputs = {@FlowPin(name = "text", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.STRING)})
    public void italic(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
        ctx.setOutput(node, "result", text.decoration(TextDecoration.ITALIC, true));
    }

    @DefineNode(id = "format_underline", displayName = "Underline", category = NodeDefinition.NodeCategory.VISUAL,
            inputs = {@FlowPin(name = "text", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.STRING)})
    public void underline(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
        ctx.setOutput(node, "result", text.decoration(TextDecoration.UNDERLINED, true));
    }

    @DefineNode(id = "format_strikethrough", displayName = "Strikethrough", category = NodeDefinition.NodeCategory.VISUAL,
            inputs = {@FlowPin(name = "text", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.STRING)})
    public void strikethrough(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
        ctx.setOutput(node, "result", text.decoration(TextDecoration.STRIKETHROUGH, true));
    }

    @DefineNode(id = "format_obfuscated", displayName = "Obfuscated", category = NodeDefinition.NodeCategory.VISUAL,
            inputs = {@FlowPin(name = "text", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.STRING)})
    public void obfuscated(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
        ctx.setOutput(node, "result", text.decoration(TextDecoration.OBFUSCATED, true));
    }

    @DefineNode(id = "format_reset", displayName = "Reset", category = NodeDefinition.NodeCategory.VISUAL,
            inputs = {@FlowPin(name = "text", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.STRING)})
    public void reset(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
        ctx.setOutput(node, "result", text
                .color(NamedTextColor.WHITE)
                .decoration(TextDecoration.BOLD, false)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.UNDERLINED, false)
                .decoration(TextDecoration.STRIKETHROUGH, false)
                .decoration(TextDecoration.OBFUSCATED, false));
    }

    @DefineNode(id = "format_hover", displayName = "Hover", category = NodeDefinition.NodeCategory.VISUAL,
            inputs = {@FlowPin(name = "text", dataType = FlowType.STRING), @FlowPin(name = "hover_text", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.STRING)})
    public void hover(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
        Component hoverText = ctx.getInputValue(node, "hover_text", Component.class, Component.empty());
        ctx.setOutput(node, "result", text.hoverEvent(HoverEvent.showText(hoverText)));
    }

    @DefineNode(id = "format_click", displayName = "Click", category = NodeDefinition.NodeCategory.VISUAL,
            inputs = {@FlowPin(name = "text", dataType = FlowType.STRING), @FlowPin(name = "action", dataType = FlowType.STRING), @FlowPin(name = "value", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.STRING)})
    public void click(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
        String actionType = ctx.getInputValue(node, "action", String.class, "open_url");
        String value = ctx.getInputValue(node, "value", String.class, "");
        ClickEvent.Action action = parseClickAction(actionType);
        ctx.setOutput(node, "result", text.clickEvent(ClickEvent.clickEvent(action, value)));
    }

    private static NamedTextColor parseColor(String colorName) {
        if (colorName == null || colorName.isEmpty()) {
            return NamedTextColor.WHITE;
        }
        try {
            NamedTextColor color = NamedTextColor.NAMES.value(colorName.toLowerCase());
            if (color != null) return color;
        } catch (IllegalArgumentException ignored) {
        }
        NamedTextColor legacy = LEGACY_COLORS.get(colorName.toLowerCase());
        return legacy != null ? legacy : NamedTextColor.WHITE;
    }

    private static ClickEvent.Action parseClickAction(String actionType) {
        if (actionType == null || actionType.isEmpty()) return ClickEvent.Action.OPEN_URL;
        return switch (actionType.toLowerCase()) {
            case "open_url" -> ClickEvent.Action.OPEN_URL;
            case "run_command" -> ClickEvent.Action.RUN_COMMAND;
            case "suggest_command" -> ClickEvent.Action.SUGGEST_COMMAND;
            case "copy_to_clipboard" -> ClickEvent.Action.COPY_TO_CLIPBOARD;
            case "change_page" -> ClickEvent.Action.CHANGE_PAGE;
            default -> ClickEvent.Action.OPEN_URL;
        };
    }
}
