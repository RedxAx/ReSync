package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.Log;
import restudio.resync.ReSync;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class ScheduleHandler implements NodeHandler {
    private static final Map<String, BukkitTask> scheduledTasks = new ConcurrentHashMap<>();
    private static final Set<String> DELAY_OPS = Set.of("delay", "wait_ticks");
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public ScheduleHandler() {
        operations.put("delay", (ctx, node) -> {
            Integer seconds = ctx.getInputValue(node, "seconds", Integer.class, 1);
            long ticks = seconds * 20L;
            ctx.runLater(() -> ctx.triggerOutput("flow"), ticks);
        });

        operations.put("schedule", (ctx, node) -> {
            String timeString = ctx.getInputValue(node, "time_string", String.class, "12:00");
            String flowId = ctx.getInputValue(node, "flow_id", String.class, "");
            String[] parts = timeString.split(":");
            if (parts.length == 2) {
                int targetHour = Integer.parseInt(parts[0]);
                int targetMinute = Integer.parseInt(parts[1]);
                String taskId = "schedule_" + System.nanoTime();
                BukkitTask task = Bukkit.getScheduler().runTaskTimer(ReSync.getInstance(), () -> {
                    LocalDateTime now = LocalDateTime.now();
                    if (now.getHour() == targetHour && now.getMinute() == targetMinute) {
                        Log.fine("[Flow:Schedule] Executing flow: " + flowId);
                    }
                }, 20L, 20L * 60);
                scheduledTasks.put(taskId, task);
                ctx.setOutput(node, "task_id", taskId);
            }
        });

        operations.put("schedule_repeating", (ctx, node) -> {
            Integer intervalTicks = ctx.getInputValue(node, "interval_ticks", Integer.class, 1200);
            String flowId = ctx.getInputValue(node, "flow_id", String.class, "");
            String taskId = "schedule_" + System.nanoTime();
            BukkitTask task = Bukkit.getScheduler().runTaskTimer(ReSync.getInstance(), () -> {
                Log.fine("[Flow:Schedule] Executing flow: " + flowId);
            }, intervalTicks.longValue(), intervalTicks.longValue());
            scheduledTasks.put(taskId, task);
            ctx.setOutput(node, "task_id", taskId);
        });

        operations.put("interval", (ctx, node) -> {
            Integer seconds = ctx.getInputValue(node, "seconds", Integer.class, 1);
            String flowId = ctx.getInputValue(node, "flow_id", String.class, "");
            String taskId = "schedule_" + System.nanoTime();
            long ticks = seconds * 20L;
            BukkitTask task = Bukkit.getScheduler().runTaskTimer(ReSync.getInstance(), () -> {
                Log.fine("[Flow:Schedule] Executing flow: " + flowId);
            }, ticks, ticks);
            scheduledTasks.put(taskId, task);
            ctx.setOutput(node, "task_id", taskId);
        });

        operations.put("cron", (ctx, node) -> {
            String expression = ctx.getInputValue(node, "expression", String.class, "0 12 * * *");
            String flowId = ctx.getInputValue(node, "flow_id", String.class, "");
            String[] parts = expression.split("\\s+");
            if (parts.length >= 2) {
                int parsedMinute = Integer.parseInt(parts[0]);
                int parsedHour = Integer.parseInt(parts[1]);
                String taskId = "schedule_" + System.nanoTime();
                BukkitTask task = Bukkit.getScheduler().runTaskTimer(ReSync.getInstance(), () -> {
                    LocalDateTime now = LocalDateTime.now();
                    if (now.getMinute() == parsedMinute && now.getHour() == parsedHour) {
                        Log.fine("[Flow:Schedule] Executing flow: " + flowId);
                    }
                }, 20L * 60, 20L * 60);
                scheduledTasks.put(taskId, task);
                ctx.setOutput(node, "task_id", taskId);
            }
        });

        operations.put("cancel_task", (ctx, node) -> {
            String taskId = ctx.getInputValue(node, "task_id", String.class, "");
            BukkitTask task = scheduledTasks.remove(taskId);
            if (task != null) {
                task.cancel();
            }
        });

        operations.put("wait_ticks", (ctx, node) -> {
            Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 20);
            ctx.runLater(() -> ctx.triggerOutput("flow"), ticks);
        });

        operations.put("schedule_at_time", (ctx, node) -> {
            String time = ctx.getInputValue(node, "time", String.class, "12:00");
            // Simple time-based scheduling stub
        });

    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("ScheduleHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op != null) {
            op.accept(ctx, node);
        }
        if (!DELAY_OPS.contains(operation)) {
            ctx.triggerOutput("flow");
        }
    }
}
