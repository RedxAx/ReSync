package restudio.resync.flow.nodes;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class LocationNodes {

    private static final Map<String, BiConsumer<FlowContext, FlowNode>> LEGACY_EXECUTORS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;

    private static void registerLegacyNodes(FlowRegistry registry) {
        registry.register("location_add", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Double offsetX = ctx.getInputValue(node, "offset_x", Double.class, 0.0);
            Double offsetY = ctx.getInputValue(node, "offset_y", Double.class, 0.0);
            Double offsetZ = ctx.getInputValue(node, "offset_z", Double.class, 0.0);

            Location result = location.clone().add(offsetX, offsetY, offsetZ);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "location", result);
            ctx.triggerOutput("flow");
        });

        registry.register("location_multiply", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Double factor = ctx.getInputValue(node, "factor", Double.class, 1.0);

            Location result = location.clone().multiply(factor);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "location", result);
            ctx.triggerOutput("flow");
        });

        registry.register("location_direction", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Float yaw = ctx.getInputValue(node, "yaw", Float.class, 0.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 0.0f);
            Double distance = ctx.getInputValue(node, "distance", Double.class, 1.0);

            double radiansYaw = Math.toRadians(-yaw);
            double radiansPitch = Math.toRadians(-pitch);

            double x = location.getX() - Math.sin(radiansYaw) * Math.cos(radiansPitch) * distance;
            double y = location.getY() + Math.sin(radiansPitch) * distance;
            double z = location.getZ() + Math.cos(radiansYaw) * Math.cos(radiansPitch) * distance;

            Location result = new Location(location.getWorld(), x, y, z);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "location", result);
            ctx.triggerOutput("flow");
        });

        registry.register("location_relative_to_entity", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            if (entity == null) {
                ctx.triggerOutput("flow");
                return;
            }

            String offsetType = ctx.getInputValue(node, "offset_type", String.class, "eye");
            Double offsetX = ctx.getInputValue(node, "offset_x", Double.class, 0.0);
            Double offsetY = ctx.getInputValue(node, "offset_y", Double.class, 0.0);
            Double offsetZ = ctx.getInputValue(node, "offset_z", Double.class, 0.0);

            Location baseLocation = switch (offsetType.toLowerCase()) {
                case "eye" -> entity instanceof LivingEntity living ? living.getEyeLocation() : entity.getLocation();
                case "feet" -> entity.getLocation();
                default -> entity.getLocation();
            };

            Location result = baseLocation.clone().add(offsetX, offsetY, offsetZ);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "location", result);
            ctx.triggerOutput("flow");
        });

        registry.register("location_look_at", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            Location targetLocation = ctx.getInputValue(node, "target_location", Location.class, null);

            if (location == null || targetLocation == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Location result = location.clone();
            double dx = targetLocation.getX() - result.getX();
            double dy = targetLocation.getY() - result.getY();
            double dz = targetLocation.getZ() - result.getZ();

            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > 0) {
                double yaw = Math.toDegrees(Math.atan2(-dx, dz));
                double pitch = Math.toDegrees(-Math.asin(dy / distance));

                result.setYaw((float) yaw);
                result.setPitch((float) pitch);
            }

            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "location", result);
            ctx.triggerOutput("flow");
        });

        registry.register("location_get_offset", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            String direction = ctx.getInputValue(node, "direction", String.class, "north");
            Double distance = ctx.getInputValue(node, "distance", Double.class, 1.0);

            double dx = 0, dy = 0, dz = 0;
            switch (direction.toLowerCase()) {
                case "north" -> dz = -distance;
                case "south" -> dz = distance;
                case "east" -> dx = distance;
                case "west" -> dx = -distance;
                case "up" -> dy = distance;
                case "down" -> dy = -distance;
            }

            Location result = location.clone().add(dx, dy, dz);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "location", result);
            ctx.triggerOutput("flow");
        });

        registry.register("location_center", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Location result = location.clone();
            result.setX(result.getBlockX() + 0.5);
            result.setY(result.getBlockY() + 0.5);
            result.setZ(result.getBlockZ() + 0.5);

            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "location", result);
            ctx.triggerOutput("flow");
        });

        registry.register("location_distance", (ctx, node) -> {
            Location location1 = ctx.getInputValue(node, "location1", Location.class, null);
            Location location2 = ctx.getInputValue(node, "location2", Location.class, null);

            if (location1 == null || location2 == null) {
                ctx.triggerOutput("flow");
                return;
            }

            double distance = location1.distance(location2);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "distance", distance);
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
        synchronized (LocationNodes.class) {
            if (initialized) {
                return;
            }
            FlowRegistry tempRegistry = new FlowRegistry();
            registerLegacyNodes(tempRegistry);
            for (String type : tempRegistry.getRegisteredTypes()) {
                LEGACY_EXECUTORS.put(type, tempRegistry.getExecutor(type));
            }
            initialized = true;
        }
    }

    private static void executeLegacy(String id, FlowContext ctx, FlowNode node) {
        ensureLegacyInitialized();
        BiConsumer<FlowContext, FlowNode> executor = LEGACY_EXECUTORS.get(id);
        if (executor != null) {
            executor.accept(ctx, node);
        }
    }

    @DefineNode(id = "location_add", displayName = "Location Add", category = NodeDefinition.NodeCategory.DATA,
        inputs = {
            @FlowPin(name = "location", dataType = FlowType.LOCATION),
            @FlowPin(name = "offset_x", dataType = FlowType.NUMBER),
            @FlowPin(name = "offset_y", dataType = FlowType.NUMBER),
            @FlowPin(name = "offset_z", dataType = FlowType.NUMBER)
        },
        outputs = {
            @FlowPin(name = "location", dataType = FlowType.LOCATION)
        })
    public void locationAdd(FlowContext ctx, FlowNode node) { executeLegacy("location_add", ctx, node); }

    @DefineNode(id = "location_multiply", displayName = "Location Multiply", category = NodeDefinition.NodeCategory.DATA,
        inputs = {
            @FlowPin(name = "location", dataType = FlowType.LOCATION),
            @FlowPin(name = "factor", dataType = FlowType.NUMBER)
        },
        outputs = {
            @FlowPin(name = "location", dataType = FlowType.LOCATION)
        })
    public void locationMultiply(FlowContext ctx, FlowNode node) { executeLegacy("location_multiply", ctx, node); }

    @DefineNode(id = "location_direction", displayName = "Location Direction", category = NodeDefinition.NodeCategory.DATA,
        inputs = {
            @FlowPin(name = "location", dataType = FlowType.LOCATION),
            @FlowPin(name = "yaw", dataType = FlowType.NUMBER),
            @FlowPin(name = "pitch", dataType = FlowType.NUMBER),
            @FlowPin(name = "distance", dataType = FlowType.NUMBER)
        },
        outputs = {
            @FlowPin(name = "location", dataType = FlowType.LOCATION)
        })
    public void locationDirection(FlowContext ctx, FlowNode node) { executeLegacy("location_direction", ctx, node); }

    @DefineNode(id = "location_relative_to_entity", displayName = "Location Relative To Entity", category = NodeDefinition.NodeCategory.DATA,
        inputs = {
            @FlowPin(name = "entity", dataType = FlowType.ENTITY),
            @FlowPin(name = "offset_type", dataType = FlowType.STRING),
            @FlowPin(name = "offset_x", dataType = FlowType.NUMBER),
            @FlowPin(name = "offset_y", dataType = FlowType.NUMBER),
            @FlowPin(name = "offset_z", dataType = FlowType.NUMBER)
        },
        outputs = {
            @FlowPin(name = "location", dataType = FlowType.LOCATION)
        })
    public void locationRelativeToEntity(FlowContext ctx, FlowNode node) { executeLegacy("location_relative_to_entity", ctx, node); }

    @DefineNode(id = "location_look_at", displayName = "Location Look At", category = NodeDefinition.NodeCategory.DATA,
        inputs = {
            @FlowPin(name = "location", dataType = FlowType.LOCATION),
            @FlowPin(name = "target_location", dataType = FlowType.LOCATION)
        },
        outputs = {
            @FlowPin(name = "location", dataType = FlowType.LOCATION)
        })
    public void locationLookAt(FlowContext ctx, FlowNode node) { executeLegacy("location_look_at", ctx, node); }

    @DefineNode(id = "location_get_offset", displayName = "Location Get Offset", category = NodeDefinition.NodeCategory.DATA,
        inputs = {
            @FlowPin(name = "location", dataType = FlowType.LOCATION),
            @FlowPin(name = "direction", dataType = FlowType.STRING),
            @FlowPin(name = "distance", dataType = FlowType.NUMBER)
        },
        outputs = {
            @FlowPin(name = "location", dataType = FlowType.LOCATION)
        })
    public void locationGetOffset(FlowContext ctx, FlowNode node) { executeLegacy("location_get_offset", ctx, node); }

    @DefineNode(id = "location_center", displayName = "Location Center", category = NodeDefinition.NodeCategory.DATA,
        inputs = {
            @FlowPin(name = "location", dataType = FlowType.LOCATION)
        },
        outputs = {
            @FlowPin(name = "location", dataType = FlowType.LOCATION)
        })
    public void locationCenter(FlowContext ctx, FlowNode node) { executeLegacy("location_center", ctx, node); }

    @DefineNode(id = "location_distance", displayName = "Location Distance", category = NodeDefinition.NodeCategory.DATA,
        inputs = {
            @FlowPin(name = "location1", dataType = FlowType.LOCATION),
            @FlowPin(name = "location2", dataType = FlowType.LOCATION)
        },
        outputs = {
            @FlowPin(name = "distance", dataType = FlowType.NUMBER)
        })
    public void locationDistance(FlowContext ctx, FlowNode node) { executeLegacy("location_distance", ctx, node); }

    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }
}
