package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class UtilityNodes implements NodeCategory {

    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("time_current_ticks", (ctx, node) -> {
            String worldName = ctx.getInputValue(node, "world", String.class, null);
            long ticks = worldName != null ? Bukkit.getWorld(worldName) != null ? Bukkit.getWorld(worldName).getFullTime() : 0 : Bukkit.getWorlds().isEmpty() ? 0 : Bukkit.getWorlds().get(0).getFullTime();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "ticks", ticks);
            ctx.triggerOutput("flow");
        });

        registry.register("time_current_real_ms", (ctx, node) -> {
            long timeMs = System.currentTimeMillis();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "time_ms", timeMs);
            ctx.triggerOutput("flow");
        });

        registry.register("time_current_real_seconds", (ctx, node) -> {
            long timeSeconds = System.currentTimeMillis() / 1000;
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "time_seconds", timeSeconds);
            ctx.triggerOutput("flow");
        });

        registry.register("time_format", (ctx, node) -> {
            Long timestampMs = ctx.getInputValue(node, "timestamp_ms", Long.class, System.currentTimeMillis());
            String pattern = ctx.getInputValue(node, "format_pattern", String.class, "yyyy-MM-dd HH:mm:ss");
            Instant instant = Instant.ofEpochMilli(timestampMs);
            LocalDateTime dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
            String formatted = dateTime.format(DateTimeFormatter.ofPattern(pattern));
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "formatted_string", formatted);
            ctx.triggerOutput("flow");
        });

        registry.register("time_parse", (ctx, node) -> {
            String dateString = ctx.getInputValue(node, "date_string", String.class, "");
            String pattern = ctx.getInputValue(node, "format_pattern", String.class, "yyyy-MM-dd HH:mm:ss");
            long timestampMs = 0;
            try {
                LocalDateTime dateTime = LocalDateTime.parse(dateString, DateTimeFormatter.ofPattern(pattern));
                timestampMs = dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (Exception ignored) {
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "timestamp_ms", timestampMs);
            ctx.triggerOutput("flow");
        });

        registry.register("time_add", (ctx, node) -> {
            Long timestampMs = ctx.getInputValue(node, "timestamp_ms", Long.class, System.currentTimeMillis());
            Long amount = ctx.getInputValue(node, "amount", Long.class, 0L);
            String unit = ctx.getInputValue(node, "unit", String.class, "milliseconds");
            ChronoUnit chronoUnit = switch (unit) {
                case "seconds" -> ChronoUnit.SECONDS;
                case "minutes" -> ChronoUnit.MINUTES;
                case "hours" -> ChronoUnit.HOURS;
                case "days" -> ChronoUnit.DAYS;
                default -> ChronoUnit.MILLIS;
            };
            Instant instant = Instant.ofEpochMilli(timestampMs).plus(amount, chronoUnit);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "new_timestamp", instant.toEpochMilli());
            ctx.triggerOutput("flow");
        });

        registry.register("time_diff", (ctx, node) -> {
            Long timestamp1Ms = ctx.getInputValue(node, "timestamp1_ms", Long.class, 0L);
            Long timestamp2Ms = ctx.getInputValue(node, "timestamp2_ms", Long.class, 0L);
            String unit = ctx.getInputValue(node, "unit", String.class, "milliseconds");
            Instant instant1 = Instant.ofEpochMilli(timestamp1Ms);
            Instant instant2 = Instant.ofEpochMilli(timestamp2Ms);
            ChronoUnit chronoUnit = switch (unit) {
                case "seconds" -> ChronoUnit.SECONDS;
                case "minutes" -> ChronoUnit.MINUTES;
                case "hours" -> ChronoUnit.HOURS;
                case "days" -> ChronoUnit.DAYS;
                default -> ChronoUnit.MILLIS;
            };
            long diff = chronoUnit.between(instant1, instant2);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "diff_value", diff);
            ctx.triggerOutput("flow");
        });

        registry.register("time_between", (ctx, node) -> {
            Long startMs = ctx.getInputValue(node, "start_ms", Long.class, 0L);
            Long endMs = ctx.getInputValue(node, "end_ms", Long.class, 0L);
            Instant start = Instant.ofEpochMilli(startMs);
            Instant end = Instant.ofEpochMilli(endMs);
            Duration duration = Duration.between(start, end);
            Map<String, Object> components = new HashMap<>();
            components.put("days", duration.toDays());
            components.put("hours", duration.toHoursPart());
            components.put("minutes", duration.toMinutesPart());
            components.put("seconds", duration.toSecondsPart());
            components.put("milliseconds", duration.toMillisPart());
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "days", duration.toDays());
            ctx.setNodeOutput(nodeId, "hours", duration.toHoursPart());
            ctx.setNodeOutput(nodeId, "minutes", duration.toMinutesPart());
            ctx.setNodeOutput(nodeId, "seconds", duration.toSecondsPart());
            ctx.setNodeOutput(nodeId, "milliseconds", duration.toMillisPart());
            ctx.triggerOutput("flow");
        });

        registry.register("time_is_before", (ctx, node) -> {
            Long timestamp1Ms = ctx.getInputValue(node, "timestamp1_ms", Long.class, 0L);
            Long timestamp2Ms = ctx.getInputValue(node, "timestamp2_ms", Long.class, 0L);
            boolean isBefore = timestamp1Ms < timestamp2Ms;
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "is_before", isBefore);
            ctx.triggerOutput("flow");
        });

        registry.register("time_is_after", (ctx, node) -> {
            Long timestamp1Ms = ctx.getInputValue(node, "timestamp1_ms", Long.class, 0L);
            Long timestamp2Ms = ctx.getInputValue(node, "timestamp2_ms", Long.class, 0L);
            boolean isAfter = timestamp1Ms > timestamp2Ms;
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "is_after", isAfter);
            ctx.triggerOutput("flow");
        });

        registry.register("time_convert_ticks_to_ms", (ctx, node) -> {
            Long ticks = ctx.getInputValue(node, "ticks", Long.class, 0L);
            long milliseconds = ticks * 50;
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "milliseconds", milliseconds);
            ctx.triggerOutput("flow");
        });

        registry.register("time_convert_ms_to_ticks", (ctx, node) -> {
            Long milliseconds = ctx.getInputValue(node, "milliseconds", Long.class, 0L);
            long ticks = milliseconds / 50;
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "ticks", ticks);
            ctx.triggerOutput("flow");
        });

        registry.register("uuid_random", (ctx, node) -> {
            String uuidString = UUID.randomUUID().toString();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "uuid_string", uuidString);
            ctx.triggerOutput("flow");
        });

        registry.register("uuid_from_string", (ctx, node) -> {
            String uuidString = ctx.getInputValue(node, "uuid_string", String.class, "");
            UUID uuidObject = null;
            try {
                uuidObject = UUID.fromString(uuidString);
            } catch (Exception ignored) {
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "uuid_object", uuidObject);
            ctx.triggerOutput("flow");
        });

        registry.register("uuid_to_string", (ctx, node) -> {
            UUID uuidObject = ctx.getInputValue(node, "uuid_object", UUID.class, null);
            String uuidString = uuidObject != null ? uuidObject.toString() : "";
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "uuid_string", uuidString);
            ctx.triggerOutput("flow");
        });

        registry.register("color_from_rgb", (ctx, node) -> {
            Integer red = ctx.getInputValue(node, "red", Integer.class, 0);
            Integer green = ctx.getInputValue(node, "green", Integer.class, 0);
            Integer blue = ctx.getInputValue(node, "blue", Integer.class, 0);
            Color color = Color.fromRGB(Math.max(0, Math.min(255, red)), Math.max(0, Math.min(255, green)), Math.max(0, Math.min(255, blue)));
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "color", color);
            ctx.triggerOutput("flow");
        });

        registry.register("color_from_hex", (ctx, node) -> {
            String hexString = ctx.getInputValue(node, "hex_string", String.class, "#FFFFFF");
            Color color = Color.fromRGB(Integer.parseInt(hexString.replace("#", ""), 16));
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "color", color);
            ctx.triggerOutput("flow");
        });

        registry.register("color_to_hex", (ctx, node) -> {
            Color color = ctx.getInputValue(node, "color", Color.class, Color.WHITE);
            String hex = String.format("#%06X", color.asRGB());
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "hex_string", hex);
            ctx.triggerOutput("flow");
        });

        registry.register("color_to_rgb", (ctx, node) -> {
            Color color = ctx.getInputValue(node, "color", Color.class, Color.WHITE);
            int rgb = color.asRGB();
            int red = (rgb >> 16) & 0xFF;
            int green = (rgb >> 8) & 0xFF;
            int blue = rgb & 0xFF;
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "red", red);
            ctx.setNodeOutput(nodeId, "green", green);
            ctx.setNodeOutput(nodeId, "blue", blue);
            ctx.triggerOutput("flow");
        });

        registry.register("color_mix", (ctx, node) -> {
            Color color1 = ctx.getInputValue(node, "color1", Color.class, Color.WHITE);
            Color color2 = ctx.getInputValue(node, "color2", Color.class, Color.BLACK);
            Double ratio = ctx.getInputValue(node, "ratio", Double.class, 0.5);
            int r1 = color1.getRed();
            int g1 = color1.getGreen();
            int b1 = color1.getBlue();
            int r2 = color2.getRed();
            int g2 = color2.getGreen();
            int b2 = color2.getBlue();
            int mixedRed = (int) (r1 + (r2 - r1) * ratio);
            int mixedGreen = (int) (g1 + (g2 - g1) * ratio);
            int mixedBlue = (int) (b1 + (b2 - b1) * ratio);
            Color mixedColor = Color.fromRGB(mixedRed, mixedGreen, mixedBlue);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "mixed_color", mixedColor);
            ctx.triggerOutput("flow");
        });

        registry.register("color_invert", (ctx, node) -> {
            Color color = ctx.getInputValue(node, "color", Color.class, Color.WHITE);
            int invertedRed = 255 - color.getRed();
            int invertedGreen = 255 - color.getGreen();
            int invertedBlue = 255 - color.getBlue();
            Color invertedColor = Color.fromRGB(invertedRed, invertedGreen, invertedBlue);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "inverted_color", invertedColor);
            ctx.triggerOutput("flow");
        });

        registry.register("color_brighten", (ctx, node) -> {
            Color color = ctx.getInputValue(node, "color", Color.class, Color.WHITE);
            Double amount = ctx.getInputValue(node, "amount", Double.class, 0.2);
            int newRed = (int) Math.min(255, color.getRed() + color.getRed() * amount);
            int newGreen = (int) Math.min(255, color.getGreen() + color.getGreen() * amount);
            int newBlue = (int) Math.min(255, color.getBlue() + color.getBlue() * amount);
            Color brightenedColor = Color.fromRGB(newRed, newGreen, newBlue);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "brightened_color", brightenedColor);
            ctx.triggerOutput("flow");
        });

        registry.register("color_darken", (ctx, node) -> {
            Color color = ctx.getInputValue(node, "color", Color.class, Color.WHITE);
            Double amount = ctx.getInputValue(node, "amount", Double.class, 0.2);
            int newRed = (int) Math.max(0, color.getRed() - color.getRed() * amount);
            int newGreen = (int) Math.max(0, color.getGreen() - color.getGreen() * amount);
            int newBlue = (int) Math.max(0, color.getBlue() - color.getBlue() * amount);
            Color darkenedColor = Color.fromRGB(newRed, newGreen, newBlue);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "darkened_color", darkenedColor);
            ctx.triggerOutput("flow");
        });

        registry.register("delay", (ctx, node) -> {
            Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 20);
            ctx.runLater(() -> ctx.triggerOutput("flow"), ticks);
        });

        registry.register("run_async", (ctx, node) -> {
            Bukkit.getScheduler().runTaskAsynchronously(restudio.resync.ReSync.getInstance(), () -> ctx.triggerOutput("async_flow"));
        });

        registry.register("run_sync", (ctx, node) -> {
            Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> ctx.triggerOutput("sync_flow"));
        });

        registry.register("console_log", (ctx, node) -> {
            String level = ctx.getInputValue(node, "level", String.class, "info");
            String message = ctx.getInputValue(node, "message", String.class, "");
            switch (level) {
                case "warning" -> Bukkit.getLogger().warning(message);
                case "severe" -> Bukkit.getLogger().severe(message);
                case "fine" -> Bukkit.getLogger().fine(message);
                default -> Bukkit.getLogger().info(message);
            }
            ctx.triggerOutput("flow");
        });
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
