package restudio.resync.flow.handler.generic;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class LocationHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public LocationHandler() {
        operations.put("location_add", (ctx, node) -> {
            Location location = requireLocation(ctx, node, "location");
            Double offsetX = ctx.getInputValue(node, "offset_x", Double.class, 0.0);
            Double offsetY = ctx.getInputValue(node, "offset_y", Double.class, 0.0);
            Double offsetZ = ctx.getInputValue(node, "offset_z", Double.class, 0.0);
            requireFinite(offsetX, "X offset");
            requireFinite(offsetY, "Y offset");
            requireFinite(offsetZ, "Z offset");
            Location result = location.clone().add(offsetX, offsetY, offsetZ);
            ctx.setOutput(node, "location", result);
        });

        operations.put("location_multiply", (ctx, node) -> {
            Location location = requireLocation(ctx, node, "location");
            Double factor = ctx.getInputValue(node, "factor", Double.class, 1.0);
            requireFinite(factor, "Location factor");
            Location result = location.clone().multiply(factor);
            ctx.setOutput(node, "location", result);
        });

        operations.put("location_direction", (ctx, node) -> {
            Location location = requireLocation(ctx, node, "location");
            Float yaw = ctx.getInputValue(node, "yaw", Float.class, 0.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 0.0f);
            Double distance = ctx.getInputValue(node, "distance", Double.class, 1.0);
            requireFinite(yaw.doubleValue(), "Location yaw");
            requireFinite(pitch.doubleValue(), "Location pitch");
            requireFinite(distance, "Location distance");
            Location result = location.clone();
            result.setYaw(yaw);
            result.setPitch(pitch);
            result.add(result.getDirection().multiply(distance));
            ctx.setOutput(node, "location", result);
        });

        operations.put("location_relative_to_entity", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            if (entity == null) throw new IllegalArgumentException("Entity is required");
            String offsetType = ctx.getInputValue(node, "offset_type", String.class, "eye");
            Double offsetX = ctx.getInputValue(node, "offset_x", Double.class, 0.0);
            Double offsetY = ctx.getInputValue(node, "offset_y", Double.class, 0.0);
            Double offsetZ = ctx.getInputValue(node, "offset_z", Double.class, 0.0);
            Location baseLocation = switch (offsetType.toLowerCase(Locale.ROOT)) {
                case "eye" -> entity instanceof LivingEntity living ? living.getEyeLocation() : entity.getLocation();
                case "feet" -> entity.getLocation();
                default -> throw new IllegalArgumentException("Unknown entity location offset type: " + offsetType);
            };
            requireFinite(offsetX, "X offset");
            requireFinite(offsetY, "Y offset");
            requireFinite(offsetZ, "Z offset");
            Location result = baseLocation.clone().add(offsetX, offsetY, offsetZ);
            ctx.setOutput(node, "location", result);
        });

        operations.put("location_look_at", (ctx, node) -> {
            Location location = requireLocation(ctx, node, "location");
            Location targetLocation = requireLocation(ctx, node, "target_location");
            requireSameWorld(location, targetLocation);
            Location result = location.clone();
            double dx = targetLocation.getX() - result.getX();
            double dy = targetLocation.getY() - result.getY();
            double dz = targetLocation.getZ() - result.getZ();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance <= 0) throw new IllegalArgumentException("Look-at locations must be different");
            double yaw = Math.toDegrees(Math.atan2(-dx, dz));
            double pitch = Math.toDegrees(-Math.asin(dy / distance));
            result.setYaw((float) yaw);
            result.setPitch((float) pitch);
            ctx.setOutput(node, "location", result);
        });

        operations.put("location_get_offset", (ctx, node) -> {
            Location location = requireLocation(ctx, node, "location");
            String direction = ctx.getInputValue(node, "direction", String.class, "north");
            Double distance = ctx.getInputValue(node, "distance", Double.class, 1.0);
            requireFinite(distance, "Location distance");
            double dx = 0, dy = 0, dz = 0;
            switch (direction.toLowerCase(Locale.ROOT)) {
                case "north" -> dz = -distance;
                case "south" -> dz = distance;
                case "east" -> dx = distance;
                case "west" -> dx = -distance;
                case "up" -> dy = distance;
                case "down" -> dy = -distance;
                default -> throw new IllegalArgumentException("Unknown location direction: " + direction);
            }
            Location result = location.clone().add(dx, dy, dz);
            ctx.setOutput(node, "location", result);
        });

        operations.put("location_center", (ctx, node) -> {
            Location location = requireLocation(ctx, node, "location");
            Location result = location.clone();
            result.setX(result.getBlockX() + 0.5);
            result.setY(result.getBlockY() + 0.5);
            result.setZ(result.getBlockZ() + 0.5);
            ctx.setOutput(node, "location", result);
        });

        operations.put("location_distance", (ctx, node) -> {
            Location location1 = requireLocation(ctx, node, "location1");
            Location location2 = requireLocation(ctx, node, "location2");
            requireSameWorld(location1, location2);
            double distance = location1.distance(location2);
            ctx.setOutput(node, "distance", distance);
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("LocationHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op == null) {
            throw new IllegalArgumentException("Unknown location operation: " + operation);
        }
        op.accept(ctx, node);
        ctx.triggerOutput("flow");
    }

    private static Location requireLocation(FlowContext context, FlowNode node, String inputName) {
        Location location = context.getInputValue(node, inputName, Location.class, null);
        if (location == null || location.getWorld() == null) throw new IllegalArgumentException("Location input is required: " + inputName);
        return location;
    }

    private static void requireSameWorld(Location first, Location second) {
        if (!first.getWorld().equals(second.getWorld())) throw new IllegalArgumentException("Locations must be in the same world");
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(field + " must be finite");
    }
}
