package restudio.resync.flow.handler.generic;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class TimeHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public TimeHandler() {
        operations.put("time_current", (ctx, node) -> {
            long millis = System.currentTimeMillis();
            ctx.setOutput(node, "time", millis);
        });

        operations.put("time_format", (ctx, node) -> {
            long time = ctx.getInputValue(node, "time", Long.class, 0L);
            String pattern = ctx.getInputValue(node, "format", String.class, "yyyy-MM-dd HH:mm:ss");
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
                String result = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(formatter);
                ctx.setOutput(node, "string", result);
            } catch (Exception e) {
                ctx.setOutput(node, "string", "");
            }
        });

        operations.put("time_parse", (ctx, node) -> {
            String string = ctx.getInputValue(node, "string", String.class, "");
            String pattern = ctx.getInputValue(node, "format", String.class, "yyyy-MM-dd HH:mm:ss");
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
                LocalDateTime dateTime = LocalDateTime.parse(string, formatter);
                long millis = dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                ctx.setOutput(node, "time", millis);
            } catch (Exception e) {
                ctx.setOutput(node, "time", 0L);
            }
        });

        operations.put("time_add", (ctx, node) -> {
            long time = ctx.getInputValue(node, "time", Long.class, 0L);
            long amount = ctx.getInputValue(node, "amount", Long.class, 0L);
            String unit = ctx.getInputValue(node, "unit", String.class, "seconds");
            Instant instant = Instant.ofEpochMilli(time);
            Instant result;
            switch (unit.toLowerCase()) {
                case "minutes" -> result = instant.plus(amount, ChronoUnit.MINUTES);
                case "hours" -> result = instant.plus(amount, ChronoUnit.HOURS);
                case "days" -> result = instant.plus(amount, ChronoUnit.DAYS);
                case "weeks" -> result = instant.plus(amount, ChronoUnit.WEEKS);
                case "months" -> result = instant.atZone(ZoneId.systemDefault()).plusMonths(amount).toInstant();
                case "years" -> result = instant.atZone(ZoneId.systemDefault()).plusYears(amount).toInstant();
                default -> result = instant.plus(amount, ChronoUnit.SECONDS);
            }
            ctx.setOutput(node, "time", result.toEpochMilli());
        });

        operations.put("time_diff", (ctx, node) -> {
            long time1 = ctx.getInputValue(node, "time1", Long.class, 0L);
            long time2 = ctx.getInputValue(node, "time2", Long.class, 0L);
            long diff = Math.abs(time1 - time2);
            ctx.setOutput(node, "diff", diff);
        });

        operations.put("time_to_ticks", (ctx, node) -> {
            long seconds = ctx.getInputValue(node, "seconds", Long.class, 0L);
            ctx.setOutput(node, "ticks", seconds * 20L);
        });

        operations.put("time_get_current_time", (ctx, node) -> {
            org.bukkit.World world = ctx.getInputValue(node, "world", org.bukkit.World.class, null);
            if (world != null) {
                ctx.setOutput(node, "time", (int) world.getTime());
            } else {
                ctx.setOutput(node, "time", 0);
            }
        });

        operations.put("time_get_current_ticks", (ctx, node) -> {
            ctx.setOutput(node, "ticks", (int) org.bukkit.Bukkit.getCurrentTick());
        });

    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("TimeHandler", this);
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
}
