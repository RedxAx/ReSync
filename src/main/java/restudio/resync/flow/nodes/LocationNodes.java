package restudio.resync.flow.nodes;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

public class LocationNodes implements NodeCategory {

    @Override
    public void registerNodes(FlowRegistry registry) {
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

    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }
}
