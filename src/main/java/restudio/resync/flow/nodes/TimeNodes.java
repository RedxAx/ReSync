package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.ReSync;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class TimeNodes {
    
    private static final Map<String, BukkitTask> scheduledTasks = new ConcurrentHashMap<>();
    private static final long SERVER_START_TIME = System.currentTimeMillis();
    private static final Map<String, BiConsumer<FlowContext, FlowNode>> LEGACY_EXECUTORS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;
    
    private static void registerLegacyNodes(FlowRegistry registry) {
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

    public void registerNodes(FlowRegistry registry) {
        registerLegacyNodes(registry);
    }

    private static void ensureLegacyInitialized() {
        if (initialized) {
            return;
        }
        synchronized (TimeNodes.class) {
            if (initialized) {
                return;
            }
            FlowRegistry legacyRegistry = new FlowRegistry();
            registerLegacyNodes(legacyRegistry);
            for (String type : legacyRegistry.getRegisteredTypes()) {
                LEGACY_EXECUTORS.put(type, legacyRegistry.getExecutor(type));
            }
            initialized = true;
        }
    }

    private void executeLegacy(String id, FlowContext ctx, FlowNode node) {
        ensureLegacyInitialized();
        BiConsumer<FlowContext, FlowNode> executor = LEGACY_EXECUTORS.get(id);
        if (executor == null) {
            ctx.triggerOutput("flow");
            return;
        }
        executor.accept(ctx, node);
    }

    @DefineNode(id = "delay_ticks", displayName = "Delay Ticks", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "ticks", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void delayTicks(FlowContext ctx, FlowNode node) {
        executeLegacy("delay_ticks", ctx, node);
    }

    @DefineNode(id = "delay_seconds", displayName = "Delay Seconds", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "seconds", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void delaySeconds(FlowContext ctx, FlowNode node) {
        executeLegacy("delay_seconds", ctx, node);
    }

    @DefineNode(id = "delay_minutes", displayName = "Delay Minutes", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "minutes", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void delayMinutes(FlowContext ctx, FlowNode node) {
        executeLegacy("delay_minutes", ctx, node);
    }

    @DefineNode(id = "schedule_at_time", displayName = "Schedule at Time", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "time_string", dataType = FlowType.STRING),
                    @FlowPin(name = "flow_id", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "task_id", dataType = FlowType.STRING)
            })
    public void scheduleAtTime(FlowContext ctx, FlowNode node) {
        executeLegacy("schedule_at_time", ctx, node);
    }

    @DefineNode(id = "schedule_interval", displayName = "Schedule Interval", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "interval_ticks", dataType = FlowType.NUMBER),
                    @FlowPin(name = "flow_id", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "task_id", dataType = FlowType.STRING)
            })
    public void scheduleInterval(FlowContext ctx, FlowNode node) {
        executeLegacy("schedule_interval", ctx, node);
    }

    @DefineNode(id = "schedule_cron", displayName = "Schedule Cron", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "cron_expr", dataType = FlowType.STRING),
                    @FlowPin(name = "flow_id", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "task_id", dataType = FlowType.STRING)
            })
    public void scheduleCron(FlowContext ctx, FlowNode node) {
        executeLegacy("schedule_cron", ctx, node);
    }

    @DefineNode(id = "cancel_schedule", displayName = "Cancel Schedule", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "task_id", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void cancelSchedule(FlowContext ctx, FlowNode node) {
        executeLegacy("cancel_schedule", ctx, node);
    }

    @DefineNode(id = "get_current_time", displayName = "Get Current Time", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)},
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "time", dataType = FlowType.NUMBER)
            })
    public void getCurrentTime(FlowContext ctx, FlowNode node) {
        executeLegacy("get_current_time", ctx, node);
    }

    @DefineNode(id = "get_current_ticks", displayName = "Get Current Ticks", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)},
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "ticks", dataType = FlowType.NUMBER)
            })
    public void getCurrentTicks(FlowContext ctx, FlowNode node) {
        executeLegacy("get_current_ticks", ctx, node);
    }

    @DefineNode(id = "get_server_uptime", displayName = "Get Server Uptime", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)},
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "uptime", dataType = FlowType.STRING),
                    @FlowPin(name = "uptime_ms", dataType = FlowType.NUMBER)
            })
    public void getServerUptime(FlowContext ctx, FlowNode node) {
        executeLegacy("get_server_uptime", ctx, node);
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
