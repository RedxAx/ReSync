package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import restudio.flow.data.FlowNode;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class MiscHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();
    private final Set<String> selfManagingOutputs = Set.of("delay", "run_async", "run_sync");

    public MiscHandler() {
        operations.put("log", (ctx, node) -> {
            Object text = ctx.getInputValue(node, "text", String.class, "");
            Log.info("[Flow] " + text);
        });

        operations.put("cancel_event", (ctx, node) -> {
            Boolean cancel = ctx.getInputValue(node, "cancel", Boolean.class, true);
            if (Boolean.TRUE.equals(cancel) && ctx.getEvent() instanceof Cancellable cancellable) {
                cancellable.setCancelled(true);
            }
        });

        operations.put("delay", (ctx, node) -> {
            int ticks = ctx.getInputValue(node, "ticks", Integer.class, 20);
            ctx.setOutput(node, "done", true);
            ctx.runLater(() -> ctx.triggerOutput("flow"), ticks);
        });

        operations.put("event_caller", (ctx, node) -> {
            ctx.setOutput(node, "caller", ctx.getPlayer());
        });

        operations.put("event_type", (ctx, node) -> {
            String type = ctx.getEvent() != null ? ctx.getEvent().getClass().getSimpleName() : "";
            ctx.setOutput(node, "type", type);
        });

        operations.put("event_is_cancelled", (ctx, node) -> {
            boolean cancelled = ctx.getEvent() instanceof Cancellable c && c.isCancelled();
            ctx.setOutput(node, "is_cancelled", cancelled);
        });

        operations.put("time_current_ticks", (ctx, node) -> {
            String worldName = ctx.getInputValue(node, "world", String.class, null);
            long ticks = worldName != null && Bukkit.getWorld(worldName) != null
                ? Bukkit.getWorld(worldName).getFullTime()
                : Bukkit.getWorlds().isEmpty() ? 0 : Bukkit.getWorlds().get(0).getFullTime();
            ctx.setOutput(node, "ticks", ticks);
        });

        operations.put("time_current_real_ms", (ctx, node) -> {
            ctx.setOutput(node, "time_ms", System.currentTimeMillis());
        });

        operations.put("time_current_real_seconds", (ctx, node) -> {
            ctx.setOutput(node, "time_seconds", System.currentTimeMillis() / 1000);
        });

        operations.put("time_format", (ctx, node) -> {
            Long timestampMs = ctx.getInputValue(node, "timestamp_ms", Long.class, System.currentTimeMillis());
            String pattern = ctx.getInputValue(node, "format_pattern", String.class, "yyyy-MM-dd HH:mm:ss");
            Instant instant = Instant.ofEpochMilli(timestampMs);
            LocalDateTime dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
            String formatted = dateTime.format(DateTimeFormatter.ofPattern(pattern));
            ctx.setOutput(node, "formatted_string", formatted);
        });

        operations.put("time_parse", (ctx, node) -> {
            String dateString = ctx.getInputValue(node, "date_string", String.class, "");
            String pattern = ctx.getInputValue(node, "format_pattern", String.class, "yyyy-MM-dd HH:mm:ss");
            long timestampMs = 0;
            try {
                LocalDateTime dateTime = LocalDateTime.parse(dateString, DateTimeFormatter.ofPattern(pattern));
                timestampMs = dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (Exception ignored) {
            }
            ctx.setOutput(node, "timestamp_ms", timestampMs);
        });

        operations.put("time_add", (ctx, node) -> {
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
            ctx.setOutput(node, "new_timestamp", instant.toEpochMilli());
        });

        operations.put("time_diff", (ctx, node) -> {
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
            ctx.setOutput(node, "diff_value", diff);
        });

        operations.put("time_between", (ctx, node) -> {
            Long startMs = ctx.getInputValue(node, "start_ms", Long.class, 0L);
            Long endMs = ctx.getInputValue(node, "end_ms", Long.class, 0L);
            Instant start = Instant.ofEpochMilli(startMs);
            Instant end = Instant.ofEpochMilli(endMs);
            Duration duration = Duration.between(start, end);
            ctx.setOutput(node, "days", duration.toDays());
            ctx.setOutput(node, "hours", duration.toHoursPart());
            ctx.setOutput(node, "minutes", duration.toMinutesPart());
            ctx.setOutput(node, "seconds", duration.toSecondsPart());
            ctx.setOutput(node, "milliseconds", duration.toMillisPart());
        });

        operations.put("time_is_before", (ctx, node) -> {
            Long timestamp1Ms = ctx.getInputValue(node, "timestamp1_ms", Long.class, 0L);
            Long timestamp2Ms = ctx.getInputValue(node, "timestamp2_ms", Long.class, 0L);
            ctx.setOutput(node, "is_before", timestamp1Ms < timestamp2Ms);
        });

        operations.put("time_is_after", (ctx, node) -> {
            Long timestamp1Ms = ctx.getInputValue(node, "timestamp1_ms", Long.class, 0L);
            Long timestamp2Ms = ctx.getInputValue(node, "timestamp2_ms", Long.class, 0L);
            ctx.setOutput(node, "is_after", timestamp1Ms > timestamp2Ms);
        });

        operations.put("time_convert_ticks_to_ms", (ctx, node) -> {
            Long ticks = ctx.getInputValue(node, "ticks", Long.class, 0L);
            ctx.setOutput(node, "milliseconds", ticks * 50);
        });

        operations.put("time_convert_ms_to_ticks", (ctx, node) -> {
            Long milliseconds = ctx.getInputValue(node, "milliseconds", Long.class, 0L);
            ctx.setOutput(node, "ticks", milliseconds / 50);
        });

        operations.put("console_log", (ctx, node) -> {
            String level = ctx.getInputValue(node, "level", String.class, "info");
            String message = ctx.getInputValue(node, "message", String.class, "");
            switch (level) {
                case "warning" -> Log.warn(message);
                case "severe" -> Log.error(message);
                default -> Log.info(message);
            }
        });

        operations.put("run_async", (ctx, node) -> {
            Bukkit.getScheduler().runTaskAsynchronously(ReSync.getInstance(), () -> ctx.triggerOutput("async_flow"));
        });

        operations.put("run_sync", (ctx, node) -> {
            Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> ctx.triggerOutput("sync_flow"));
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("MiscHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op != null) {
            op.accept(ctx, node);
        }
        if (operation != null && selfManagingOutputs.contains(operation)) {
            return;
        }
        ctx.triggerOutput("flow");
    }
}
