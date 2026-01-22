package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;
import restudio.resync.ReSync;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TimeNodes implements NodeCategory {
    
    private static final Map<String, BukkitTask> scheduledTasks = new ConcurrentHashMap<>();
    private static final long SERVER_START_TIME = System.currentTimeMillis();
    
    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("delay_ticks", (ctx, node) -> {
            Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 20);
            ctx.runLater(() -> ctx.triggerOutput("flow"), ticks);
        });
        
        registry.register("delay_seconds", (ctx, node) -> {
            Integer seconds = ctx.getInputValue(node, "seconds", Integer.class, 1);
            long ticks = seconds * 20L;
            ctx.runLater(() -> ctx.triggerOutput("flow"), ticks);
        });
        
        registry.register("delay_minutes", (ctx, node) -> {
            Integer minutes = ctx.getInputValue(node, "minutes", Integer.class, 1);
            long ticks = minutes * 20L * 60;
            ctx.runLater(() -> ctx.triggerOutput("flow"), ticks);
        });
        
        registry.register("schedule_at_time", (ctx, node) -> {
            String timeString = ctx.getInputValue(node, "time_string", String.class, "12:00");
            String flowId = ctx.getInputValue(node, "flow_id", String.class, "");
            
            String[] parts = timeString.split(":");
            if (parts.length == 2) {
                int targetHour = Integer.parseInt(parts[0]);
                int targetMinute = Integer.parseInt(parts[1]);
                
                String taskId = "schedule_" + System.nanoTime();
                BukkitTask task = Bukkit.getScheduler().runTaskTimer(ReSync.getInstance(), () -> {
                    java.time.LocalDateTime now = java.time.LocalDateTime.now();
                    if (now.getHour() == targetHour && now.getMinute() == targetMinute) {
                        Bukkit.getLogger().info("[Flow Schedule] Executing flow: " + flowId);
                    }
                }, 20L, 20L * 60);
                
                scheduledTasks.put(taskId, task);
                String nodeId = findNodeId(ctx, node);
                ctx.setNodeOutput(nodeId, "task_id", taskId);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("schedule_interval", (ctx, node) -> {
            Integer intervalTicks = ctx.getInputValue(node, "interval_ticks", Integer.class, 1200);
            String flowId = ctx.getInputValue(node, "flow_id", String.class, "");
            
            String taskId = "schedule_" + System.nanoTime();
            BukkitTask task = Bukkit.getScheduler().runTaskTimer(ReSync.getInstance(), () -> {
                Bukkit.getLogger().info("[Flow Schedule] Executing flow: " + flowId);
            }, intervalTicks.longValue(), intervalTicks.longValue());
            
            scheduledTasks.put(taskId, task);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "task_id", taskId);
            ctx.triggerOutput("flow");
        });
        
        registry.register("schedule_cron", (ctx, node) -> {
            String cronExpr = ctx.getInputValue(node, "cron_expr", String.class, "0 * * * *");
            String flowId = ctx.getInputValue(node, "flow_id", String.class, "");
            
            String[] parts = cronExpr.split(" ");
            if (parts.length >= 5) {
                int minute = parts[0].equals("*") ? -1 : Integer.parseInt(parts[0]);
                int hour = parts[1].equals("*") ? -1 : Integer.parseInt(parts[1]);
                
                String taskId = "schedule_" + System.nanoTime();
                BukkitTask task = Bukkit.getScheduler().runTaskTimer(ReSync.getInstance(), () -> {
                    java.time.LocalDateTime now = java.time.LocalDateTime.now();
                    boolean shouldRun = true;
                    if (minute != -1 && now.getMinute() != minute) shouldRun = false;
                    if (hour != -1 && now.getHour() != hour) shouldRun = false;
                    
                    if (shouldRun) {
                        Bukkit.getLogger().info("[Flow Schedule] Executing flow: " + flowId);
                    }
                }, 20L, 20L * 60);
                
                scheduledTasks.put(taskId, task);
                String nodeId = findNodeId(ctx, node);
                ctx.setNodeOutput(nodeId, "task_id", taskId);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("cancel_schedule", (ctx, node) -> {
            String taskId = ctx.getInputValue(node, "task_id", String.class, "");
            
            BukkitTask task = scheduledTasks.remove(taskId);
            if (task != null) {
                task.cancel();
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("get_current_time", (ctx, node) -> {
            long time = Bukkit.getWorlds().isEmpty() ? 6000 : Bukkit.getWorlds().get(0).getTime();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "time", time);
            ctx.triggerOutput("flow");
        });
        
        registry.register("get_current_ticks", (ctx, node) -> {
            long ticks = Bukkit.getCurrentTick();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "ticks", ticks);
            ctx.triggerOutput("flow");
        });
        
        registry.register("get_server_uptime", (ctx, node) -> {
            long uptimeMs = System.currentTimeMillis() - SERVER_START_TIME;
            long seconds = uptimeMs / 1000;
            long minutes = seconds / 60;
            long hours = minutes / 60;
            long days = hours / 24;
            
            String duration;
            if (days > 0) {
                duration = String.format("%dd %dh %dm", days, hours % 24, minutes % 60);
            } else if (hours > 0) {
                duration = String.format("%dh %dm", hours, minutes % 60);
            } else if (minutes > 0) {
                duration = String.format("%dm %ds", minutes, seconds % 60);
            } else {
                duration = String.format("%ds", seconds);
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "uptime", duration);
            ctx.setNodeOutput(nodeId, "uptime_ms", uptimeMs);
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
