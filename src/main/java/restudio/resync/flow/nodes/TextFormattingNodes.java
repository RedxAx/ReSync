package restudio.resync.flow.nodes;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.event.ClickEvent;

import java.util.Map;

public class TextFormattingNodes implements NodeCategory {
    
    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("format_color", (ctx, node) -> {
            Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
            String colorName = ctx.getInputValue(node, "color", String.class, "white");
            String nodeId = findNodeId(ctx, node);
            
            NamedTextColor color = parseColor(colorName);
            ctx.setNodeOutput(nodeId, "result", text.color(color));
        });
        
        registry.register("format_bold", (ctx, node) -> {
            Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", text.decoration(TextDecoration.BOLD, true));
        });
        
        registry.register("format_italic", (ctx, node) -> {
            Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", text.decoration(TextDecoration.ITALIC, true));
        });
        
        registry.register("format_underline", (ctx, node) -> {
            Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", text.decoration(TextDecoration.UNDERLINED, true));
        });
        
        registry.register("format_strikethrough", (ctx, node) -> {
            Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", text.decoration(TextDecoration.STRIKETHROUGH, true));
        });
        
        registry.register("format_obfuscated", (ctx, node) -> {
            Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", text.decoration(TextDecoration.OBFUSCATED, true));
        });
        
        registry.register("format_reset", (ctx, node) -> {
            Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", text
                .color(NamedTextColor.WHITE)
                .decoration(TextDecoration.BOLD, false)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.UNDERLINED, false)
                .decoration(TextDecoration.STRIKETHROUGH, false)
                .decoration(TextDecoration.OBFUSCATED, false));
        });
        
        registry.register("format_hover", (ctx, node) -> {
            Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
            Component hoverText = ctx.getInputValue(node, "hover_text", Component.class, Component.empty());
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", text.hoverEvent(HoverEvent.showText(hoverText)));
        });
        
        registry.register("format_click", (ctx, node) -> {
            Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
            String actionType = ctx.getInputValue(node, "action", String.class, "open_url");
            String value = ctx.getInputValue(node, "value", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            ClickEvent.Action action = parseClickAction(actionType);
            ctx.setNodeOutput(nodeId, "result", text.clickEvent(ClickEvent.clickEvent(action, value)));
        });
    }
    
    private static NamedTextColor parseColor(String colorName) {
        if (colorName == null || colorName.isEmpty()) {
            return NamedTextColor.WHITE;
        }
        
        try {
            NamedTextColor color = NamedTextColor.NAMES.value(colorName.toLowerCase());
            if (color != null) {
                return color;
            }
        } catch (IllegalArgumentException e) {
        }
        
        java.util.HashMap<String, NamedTextColor> legacyColors = new java.util.HashMap<>();
        legacyColors.put("0", NamedTextColor.BLACK);
        legacyColors.put("1", NamedTextColor.DARK_BLUE);
        legacyColors.put("2", NamedTextColor.DARK_GREEN);
        legacyColors.put("3", NamedTextColor.DARK_AQUA);
        legacyColors.put("4", NamedTextColor.DARK_RED);
        legacyColors.put("5", NamedTextColor.DARK_PURPLE);
        legacyColors.put("6", NamedTextColor.GOLD);
        legacyColors.put("7", NamedTextColor.GRAY);
        legacyColors.put("8", NamedTextColor.DARK_GRAY);
        legacyColors.put("9", NamedTextColor.BLUE);
        legacyColors.put("a", NamedTextColor.GREEN);
        legacyColors.put("b", NamedTextColor.AQUA);
        legacyColors.put("c", NamedTextColor.RED);
        legacyColors.put("d", NamedTextColor.LIGHT_PURPLE);
        legacyColors.put("e", NamedTextColor.YELLOW);
        legacyColors.put("f", NamedTextColor.WHITE);
        
        if (legacyColors.containsKey(colorName.toLowerCase())) {
            return legacyColors.get(colorName.toLowerCase());
        }
        
        return NamedTextColor.WHITE;
    }
    
    private static ClickEvent.Action parseClickAction(String actionType) {
        if (actionType == null || actionType.isEmpty()) {
            return ClickEvent.Action.OPEN_URL;
        }
        
        switch (actionType.toLowerCase()) {
            case "open_url":
                return ClickEvent.Action.OPEN_URL;
            case "run_command":
                return ClickEvent.Action.RUN_COMMAND;
            case "suggest_command":
                return ClickEvent.Action.SUGGEST_COMMAND;
            case "copy_to_clipboard":
                return ClickEvent.Action.COPY_TO_CLIPBOARD;
            case "change_page":
                return ClickEvent.Action.CHANGE_PAGE;
            default:
                return ClickEvent.Action.OPEN_URL;
        }
    }
    
    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }
}
