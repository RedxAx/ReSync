package restudio.resync.flow.handler.generic;

import org.bukkit.Color;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;

public class ColorHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public ColorHandler() {
        operations.put("color_from_rgb", (ctx, node) -> {
            Integer red = ctx.getInputValue(node, "red", Integer.class, 0);
            Integer green = ctx.getInputValue(node, "green", Integer.class, 0);
            Integer blue = ctx.getInputValue(node, "blue", Integer.class, 0);
            Color color = Color.fromRGB(Math.max(0, Math.min(255, red)), Math.max(0, Math.min(255, green)), Math.max(0, Math.min(255, blue)));
            ctx.setOutput(node, "color", color);
        });

        operations.put("color_from_hex", (ctx, node) -> {
            String hexString = ctx.getInputValue(node, "hex_string", String.class, "#FFFFFF");
            String hex = hexString.strip().replace("#", "");
            if (hex.length() == 3) {
                hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
            }
            if (hex.length() != 6) {
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
            double ratio = Math.clamp(ctx.getInputValue(node, "ratio", Double.class, 0.5), 0.0, 1.0);
            int mixedRed = (int) (color1.getRed() + (color2.getRed() - color1.getRed()) * ratio);
            int mixedGreen = (int) (color1.getGreen() + (color2.getGreen() - color1.getGreen()) * ratio);
            int mixedBlue = (int) (color1.getBlue() + (color2.getBlue() - color1.getBlue()) * ratio);
            ctx.setOutput(node, "mixed_color", Color.fromRGB(mixedRed, mixedGreen, mixedBlue));
        });

        operations.put("mix", (ctx, node) -> {
            Color color1 = ctx.getInputValue(node, "color1", Color.class, Color.WHITE);
            Color color2 = ctx.getInputValue(node, "color2", Color.class, Color.BLACK);
            double ratio = Math.clamp(ctx.getInputValue(node, "ratio", Double.class, 0.5), 0.0, 1.0);
            int mixedRed = (int) (color1.getRed() + (color2.getRed() - color1.getRed()) * ratio);
            int mixedGreen = (int) (color1.getGreen() + (color2.getGreen() - color1.getGreen()) * ratio);
            int mixedBlue = (int) (color1.getBlue() + (color2.getBlue() - color1.getBlue()) * ratio);
            ctx.setOutput(node, "mixed_color", Color.fromRGB(mixedRed, mixedGreen, mixedBlue));
        });

        operations.put("color_invert", (ctx, node) -> {
            Color color = ctx.getInputValue(node, "color", Color.class, Color.WHITE);
            ctx.setOutput(node, "inverted_color", Color.fromRGB(255 - color.getRed(), 255 - color.getGreen(), 255 - color.getBlue()));
        });

        operations.put("color_brighten", (ctx, node) -> {
            Color color = ctx.getInputValue(node, "color", Color.class, Color.WHITE);
            double amount = Math.clamp(ctx.getInputValue(node, "amount", Double.class, 0.2), 0.0, 1.0);
            int newRed = (int) Math.min(255, color.getRed() + color.getRed() * amount);
            int newGreen = (int) Math.min(255, color.getGreen() + color.getGreen() * amount);
            int newBlue = (int) Math.min(255, color.getBlue() + color.getBlue() * amount);
            ctx.setOutput(node, "brightened_color", Color.fromRGB(newRed, newGreen, newBlue));
        });

        operations.put("color_darken", (ctx, node) -> {
            Color color = ctx.getInputValue(node, "color", Color.class, Color.WHITE);
            double amount = Math.clamp(ctx.getInputValue(node, "amount", Double.class, 0.2), 0.0, 1.0);
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
        ctx.triggerOutput("flow");
    }
}
