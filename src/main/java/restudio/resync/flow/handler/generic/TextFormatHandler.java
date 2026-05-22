package restudio.resync.flow.handler.generic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import restudio.resync.flow.util.TextFormatter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class TextFormatHandler implements NodeHandler {
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

    private final ConcurrentHashMap<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public TextFormatHandler() {
        operations.put("format_color", (ctx, node) -> {
            Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
            String colorName = ctx.getInputValue(node, "color", String.class, "white");
            ctx.setOutput(node, "result", text.color(parseColor(colorName)));
        });

        operations.put("format_bold", (ctx, node) -> {
            Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
            ctx.setOutput(node, "result", text.decoration(TextDecoration.BOLD, true));
        });

        operations.put("format_italic", (ctx, node) -> {
            Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
            ctx.setOutput(node, "result", text.decoration(TextDecoration.ITALIC, true));
        });

        operations.put("format_underline", (ctx, node) -> {
            Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
            ctx.setOutput(node, "result", text.decoration(TextDecoration.UNDERLINED, true));
        });

        operations.put("format_strikethrough", (ctx, node) -> {
            Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
            ctx.setOutput(node, "result", text.decoration(TextDecoration.STRIKETHROUGH, true));
        });

        operations.put("format_reset", (ctx, node) -> {
            Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
            ctx.setOutput(node, "result", text
                    .color(NamedTextColor.WHITE)
                    .decoration(TextDecoration.BOLD, false)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.UNDERLINED, false)
                    .decoration(TextDecoration.STRIKETHROUGH, false)
                    .decoration(TextDecoration.OBFUSCATED, false));
        });

        operations.put("format_gradient", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String colors = ctx.getInputValue(node, "colors", String.class, "red:blue");
            ctx.setOutput(node, "result", TextFormatter.formatLegacy(TextFormatter.parse("<gradient:" + colors + ">" + text + "</gradient>")));
        });

        operations.put("format_mini_message", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            ctx.setOutput(node, "result", TextFormatter.formatLegacy(TextFormatter.parse(text)));
        });

        operations.put("obfuscated", (ctx, node) -> {
            Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
            ctx.setOutput(node, "result", text.decoration(TextDecoration.OBFUSCATED, true));
        });

        operations.put("hover", (ctx, node) -> {
            Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
            String hoverText = ctx.getInputValue(node, "hover_text", String.class, "");
            ctx.setOutput(node, "result", text.hoverEvent(HoverEvent.showText(TextFormatter.parse(hoverText))));
        });

        operations.put("click", (ctx, node) -> {
            Component text = ctx.getInputValue(node, "text", Component.class, Component.empty());
            String action = ctx.getInputValue(node, "action", String.class, "open_url");
            String value = ctx.getInputValue(node, "value", String.class, "");
            ctx.setOutput(node, "result", text.clickEvent(ClickEvent.clickEvent(parseClickAction(action), value)));
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("TextFormatHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op != null) {
            op.accept(ctx, node);
        }
        ctx.triggerOutput("flow");
    }

    private static ClickEvent.Action parseClickAction(String action) {
        String lower = action.toLowerCase();
        if (lower.equals("run_command")) {
            return ClickEvent.Action.RUN_COMMAND;
        }
        if (lower.equals("suggest_command")) {
            return ClickEvent.Action.SUGGEST_COMMAND;
        }
        if (lower.equals("copy_to_clipboard")) {
            return ClickEvent.Action.COPY_TO_CLIPBOARD;
        }
        if (lower.equals("change_page")) {
            return ClickEvent.Action.CHANGE_PAGE;
        }
        return ClickEvent.Action.OPEN_URL;
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
}
