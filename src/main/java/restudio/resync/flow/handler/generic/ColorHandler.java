package restudio.resync.flow.handler.generic;

import org.bukkit.Color;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;

public class ColorHandler implements NodeHandler {
    private static final Set<String> DATA_ONLY_OPERATIONS = Set.of(
        "color_from_rgb",
        "color_from_hex",
        "color_to_hex",
        "color_to_rgb",
        "color_blend",
        "color_invert",
        "color_brighten",
        "color_darken",
        "color_random",
        "color_distance");
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public ColorHandler() {
        operations.put("color_from_rgb", (ctx, node) -> {
            Number red = ctx.getInputValue(node, "red", Number.class, 0);
            Number green = ctx.getInputValue(node, "green", Number.class, 0);
            Number blue = ctx.getInputValue(node, "blue", Number.class, 0);
            Color color = Color.fromRGB(channel(red), channel(green), channel(blue));
            ctx.setOutput(node, "color", color);
        });

        operations.put("color_from_hex", (ctx, node) -> {
            String hexString = ctx.getInputValue(node, "hex_string", String.class, "#FFFFFF");
            String hex = hexString.strip();
            if (hex.startsWith("#")) {
                hex = hex.substring(1);
            }
            if (hex.length() == 3) {
                hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
            }
            if (hex.length() != 6 || !hex.matches("[0-9a-fA-F]{6}")) {
                throw new IllegalArgumentException("Invalid RGB color: " + hexString);
            }
            Color color = Color.fromRGB(Integer.parseInt(hex, 16));
            ctx.setOutput(node, "color", color);
        });

        operations.put("color_to_hex", (ctx, node) -> {
            Color color = ctx.getInputValue(node, "color", Color.class, Color.WHITE);
            String hex = String.format("#%06X", color.asRGB());
            ctx.setOutput(node, "hex_string", hex);
        });

        operations.put("color_to_rgb", (ctx, node) -> {
            Color color = ctx.getInputValue(node, "color", Color.class, Color.WHITE);
            int rgb = color.asRGB();
            int red = (rgb >> 16) & 0xFF;
            int green = (rgb >> 8) & 0xFF;
            int blue = rgb & 0xFF;
            ctx.setOutput(node, "red", red);
            ctx.setOutput(node, "green", green);
            ctx.setOutput(node, "blue", blue);
        });

        operations.put("color_blend", (ctx, node) -> {
            Color color1 = ctx.getInputValue(node, "color1", Color.class, Color.WHITE);
            Color color2 = ctx.getInputValue(node, "color2", Color.class, Color.BLACK);
            double ratio = normalized(ctx.getInputValue(node, "ratio", Number.class, 0.5), 0.5, "Blend ratio");
            ctx.setOutput(node, "mixed_color", blend(color1, color2, ratio));
        });

        operations.put("mix", (ctx, node) -> {
            Color color1 = ctx.getInputValue(node, "color1", Color.class, Color.WHITE);
            Color color2 = ctx.getInputValue(node, "color2", Color.class, Color.BLACK);
            double ratio = normalized(ctx.getInputValue(node, "ratio", Number.class, 0.5), 0.5, "Mix ratio");
            ctx.setOutput(node, "mixed_color", blend(color1, color2, ratio));
        });

        operations.put("color_invert", (ctx, node) -> {
            Color color = ctx.getInputValue(node, "color", Color.class, Color.WHITE);
            ctx.setOutput(node, "inverted_color", Color.fromRGB(255 - color.getRed(), 255 - color.getGreen(), 255 - color.getBlue()));
        });

        operations.put("color_brighten", (ctx, node) -> {
            Color color = ctx.getInputValue(node, "color", Color.class, Color.WHITE);
            double amount = normalized(ctx.getInputValue(node, "amount", Number.class, 0.2), 0.2, "Brighten amount");
            int newRed = (int) Math.min(255, color.getRed() + color.getRed() * amount);
            int newGreen = (int) Math.min(255, color.getGreen() + color.getGreen() * amount);
            int newBlue = (int) Math.min(255, color.getBlue() + color.getBlue() * amount);
            ctx.setOutput(node, "brightened_color", Color.fromRGB(newRed, newGreen, newBlue));
        });

        operations.put("color_darken", (ctx, node) -> {
            Color color = ctx.getInputValue(node, "color", Color.class, Color.WHITE);
            double amount = normalized(ctx.getInputValue(node, "amount", Number.class, 0.2), 0.2, "Darken amount");
            int newRed = (int) Math.max(0, color.getRed() - color.getRed() * amount);
            int newGreen = (int) Math.max(0, color.getGreen() - color.getGreen() * amount);
            int newBlue = (int) Math.max(0, color.getBlue() - color.getBlue() * amount);
            ctx.setOutput(node, "darkened_color", Color.fromRGB(newRed, newGreen, newBlue));
        });

        operations.put("color_random", (ctx, node) -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            ctx.setOutput(node, "color", Color.fromRGB(random.nextInt(256), random.nextInt(256), random.nextInt(256)));
        });

        operations.put("color_distance", (ctx, node) -> {
            Color color1 = ctx.getInputValue(node, "color1", Color.class, Color.WHITE);
            Color color2 = ctx.getInputValue(node, "color2", Color.class, Color.BLACK);
            double distance = Math.sqrt(Math.pow(color1.getRed() - color2.getRed(), 2) + Math.pow(color1.getGreen() - color2.getGreen(), 2) + Math.pow(color1.getBlue() - color2.getBlue(), 2));
            ctx.setOutput(node, "distance", distance);
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("ColorHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op == null) {
            throw new IllegalArgumentException("Unknown color operation: " + operation);
        }
        op.accept(ctx, node);
        if (!DATA_ONLY_OPERATIONS.contains(operation)) {
            ctx.triggerOutput("flow");
        }
    }

    private static int channel(Number value) {
        return Math.clamp(value != null ? value.intValue() : 0, 0, 255);
    }

    private static double normalized(Number value, double fallback, String label) {
        double normalized = value != null ? value.doubleValue() : fallback;
        if (!Double.isFinite(normalized)) {
            throw new IllegalArgumentException(label + " must be finite");
        }
        return Math.clamp(normalized, 0.0, 1.0);
    }

    private static Color blend(Color color1, Color color2, double ratio) {
        int mixedRed = (int) (color1.getRed() + (color2.getRed() - color1.getRed()) * ratio);
        int mixedGreen = (int) (color1.getGreen() + (color2.getGreen() - color1.getGreen()) * ratio);
        int mixedBlue = (int) (color1.getBlue() + (color2.getBlue() - color1.getBlue()) * ratio);
        return Color.fromRGB(channel(mixedRed), channel(mixedGreen), channel(mixedBlue));
    }
}
