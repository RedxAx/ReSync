package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import restudio.flow.data.FlowNode;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class ParticleHandler implements NodeHandler {
    private static final int MAX_TEXT_LENGTH = 80;
    private static final int MAX_TEXT_IMAGE_WIDTH = 512;
    private static final int MAX_TEXT_IMAGE_HEIGHT = 160;
    private static final int MAX_TEXT_PARTICLES = 500;
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public ParticleHandler() {
        operations.put("particle_apply", this::applyParticle);

        operations.put("particle_spawn", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String particleName = ctx.getInputValue(node, "particle_type", String.class, "FLAME");
            Integer count = ctx.getInputValue(node, "count", Integer.class, 1);
            Double offsetX = ctx.getInputValue(node, "offset_x", Double.class, 0.0);
            Double offsetY = ctx.getInputValue(node, "offset_y", Double.class, 0.0);
            Double offsetZ = ctx.getInputValue(node, "offset_z", Double.class, 0.0);
            Double speed = ctx.getInputValue(node, "speed", Double.class, 0.0);

            if (location != null && location.getWorld() != null) {
                try {
                    Particle particle = Particle.valueOf(particleName.toUpperCase());
                    if (Bukkit.isPrimaryThread()) {
                        location.getWorld().spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, speed, null);
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> location.getWorld().spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, speed, null));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        });

        operations.put("particle_area", (ctx, node) -> {
            Location minLocation = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLocation = ctx.getInputValue(node, "max_location", Location.class, null);
            String particleName = ctx.getInputValue(node, "particle_type", String.class, "FLAME");
            Integer density = ctx.getInputValue(node, "density", Integer.class, 10);

            if (minLocation != null && maxLocation != null && minLocation.getWorld() != null && minLocation.getWorld().equals(maxLocation.getWorld())) {
                try {
                    Particle particle = Particle.valueOf(particleName.toUpperCase());
                    double stepX = Math.max(0.5, (maxLocation.getX() - minLocation.getX()) / Math.max(1, density));
                    double stepY = Math.max(0.5, (maxLocation.getY() - minLocation.getY()) / Math.max(1, density));
                    double stepZ = Math.max(0.5, (maxLocation.getZ() - minLocation.getZ()) / Math.max(1, density));

                    if (Bukkit.isPrimaryThread()) {
                        for (double x = minLocation.getX(); x <= maxLocation.getX(); x += stepX) {
                            for (double y = minLocation.getY(); y <= maxLocation.getY(); y += stepY) {
                                for (double z = minLocation.getZ(); z <= maxLocation.getZ(); z += stepZ) {
                                    Location loc = new Location(minLocation.getWorld(), x, y, z);
                                    minLocation.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                                }
                            }
                        }
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                            for (double x = minLocation.getX(); x <= maxLocation.getX(); x += stepX) {
                                for (double y = minLocation.getY(); y <= maxLocation.getY(); y += stepY) {
                                    for (double z = minLocation.getZ(); z <= maxLocation.getZ(); z += stepZ) {
                                        Location loc = new Location(minLocation.getWorld(), x, y, z);
                                        minLocation.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                                    }
                                }
                            }
                        });
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        });

        operations.put("particle_line", (ctx, node) -> {
            Location startLocation = ctx.getInputValue(node, "start_location", Location.class, null);
            Location endLocation = ctx.getInputValue(node, "end_location", Location.class, null);
            String particleName = ctx.getInputValue(node, "particle_type", String.class, "FLAME");
            Integer density = ctx.getInputValue(node, "density", Integer.class, 10);

            if (startLocation != null && endLocation != null && startLocation.getWorld() != null && startLocation.getWorld().equals(endLocation.getWorld())) {
                try {
                    Particle particle = Particle.valueOf(particleName.toUpperCase());
                    double distance = startLocation.distance(endLocation);
                    double step = distance / Math.max(1, density);

                    if (Bukkit.isPrimaryThread()) {
                        for (double d = 0; d <= distance; d += step) {
                            double ratio = d / distance;
                            double x = startLocation.getX() + (endLocation.getX() - startLocation.getX()) * ratio;
                            double y = startLocation.getY() + (endLocation.getY() - startLocation.getY()) * ratio;
                            double z = startLocation.getZ() + (endLocation.getZ() - startLocation.getZ()) * ratio;
                            Location loc = new Location(startLocation.getWorld(), x, y, z);
                            startLocation.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                        }
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                            for (double d = 0; d <= distance; d += step) {
                                double ratio = d / distance;
                                double x = startLocation.getX() + (endLocation.getX() - startLocation.getX()) * ratio;
                                double y = startLocation.getY() + (endLocation.getY() - startLocation.getY()) * ratio;
                                double z = startLocation.getZ() + (endLocation.getZ() - startLocation.getZ()) * ratio;
                                Location loc = new Location(startLocation.getWorld(), x, y, z);
                                startLocation.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                            }
                        });
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        });

        operations.put("particle_circle", (ctx, node) -> {
            Location centerLocation = ctx.getInputValue(node, "center_location", Location.class, null);
            Double radius = ctx.getInputValue(node, "radius", Double.class, 5.0);
            String particleName = ctx.getInputValue(node, "particle_type", String.class, "FLAME");
            Integer points = ctx.getInputValue(node, "points", Integer.class, 20);

            if (centerLocation != null && centerLocation.getWorld() != null && radius > 0 && points > 0) {
                try {
                    Particle particle = Particle.valueOf(particleName.toUpperCase());

                    if (Bukkit.isPrimaryThread()) {
                        for (int i = 0; i < points; i++) {
                            double angle = (2 * Math.PI * i) / points;
                            double x = centerLocation.getX() + radius * Math.cos(angle);
                            double z = centerLocation.getZ() + radius * Math.sin(angle);
                            Location loc = new Location(centerLocation.getWorld(), x, centerLocation.getY(), z);
                            centerLocation.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                        }
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                            for (int i = 0; i < points; i++) {
                                double angle = (2 * Math.PI * i) / points;
                                double x = centerLocation.getX() + radius * Math.cos(angle);
                                double z = centerLocation.getZ() + radius * Math.sin(angle);
                                Location loc = new Location(centerLocation.getWorld(), x, centerLocation.getY(), z);
                                centerLocation.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                            }
                        });
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        });

        operations.put("particle_sphere", (ctx, node) -> {
            Location centerLocation = ctx.getInputValue(node, "center_location", Location.class, null);
            Double radius = ctx.getInputValue(node, "radius", Double.class, 5.0);
            String particleName = ctx.getInputValue(node, "particle_type", String.class, "FLAME");
            Integer points = ctx.getInputValue(node, "points", Integer.class, 50);

            if (centerLocation != null && centerLocation.getWorld() != null && radius > 0 && points > 0) {
                try {
                    Particle particle = Particle.valueOf(particleName.toUpperCase());
                    double phi = Math.PI * (3.0 - Math.sqrt(5.0));

                    if (Bukkit.isPrimaryThread()) {
                        for (int i = 0; i < points; i++) {
                            double y = 1.0 - (i / (double) (points - 1)) * 2.0;
                            double radiusAtY = Math.sqrt(1.0 - y * y);
                            double theta = phi * i;
                            double x = Math.cos(theta) * radiusAtY;
                            double z = Math.sin(theta) * radiusAtY;
                            Location loc = new Location(centerLocation.getWorld(),
                                    centerLocation.getX() + x * radius,
                                    centerLocation.getY() + y * radius,
                                    centerLocation.getZ() + z * radius);
                            centerLocation.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                        }
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                            for (int i = 0; i < points; i++) {
                                double y = 1.0 - (i / (double) (points - 1)) * 2.0;
                                double radiusAtY = Math.sqrt(1.0 - y * y);
                                double theta = phi * i;
                                double x = Math.cos(theta) * radiusAtY;
                                double z = Math.sin(theta) * radiusAtY;
                                Location loc = new Location(centerLocation.getWorld(),
                                        centerLocation.getX() + x * radius,
                                        centerLocation.getY() + y * radius,
                                        centerLocation.getZ() + z * radius);
                                centerLocation.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                            }
                        });
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        });

        operations.put("particle_block_dust", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String blockTypeName = ctx.getInputValue(node, "block_type", String.class, "STONE");
            Integer count = ctx.getInputValue(node, "count", Integer.class, 10);

            if (location != null && location.getWorld() != null) {
                Material blockType = Material.getMaterial(blockTypeName.toUpperCase());
                if (blockType != null && blockType.isBlock()) {
                    BlockData blockData = blockType.createBlockData();
                    if (Bukkit.isPrimaryThread()) {
                        location.getWorld().spawnParticle(Particle.BLOCK, location, count, blockData);
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () ->
                                location.getWorld().spawnParticle(Particle.BLOCK, location, count, blockData));
                    }
                }
            }
        });

        operations.put("particle_item_break", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String itemTypeName = ctx.getInputValue(node, "item_type", String.class, "STONE");

            if (location != null && location.getWorld() != null) {
                Material itemType = Material.getMaterial(itemTypeName.toUpperCase());
                if (itemType != null && itemType.isItem()) {
                    if (Bukkit.isPrimaryThread()) {
                        location.getWorld().spawnParticle(Particle.ITEM, location, 1, 0, 0, 0, 0,
                                new ItemStack(itemType));
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () ->
                                location.getWorld().spawnParticle(Particle.ITEM, location, 1, 0, 0, 0, 0,
                                        new ItemStack(itemType)));
                    }
                }
            }
        });

        operations.put("particle_explosion", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            Boolean large = ctx.getInputValue(node, "large", Boolean.class, false);

            if (location != null && location.getWorld() != null) {
                try {
                    if (Bukkit.isPrimaryThread()) {
                        if (large) {
                            location.getWorld().spawnParticle(Particle.LAVA, location, 20, 1.0, 1.0, 1.0, 0.1);
                        } else {
                            location.getWorld().spawnParticle(Particle.FLAME, location, 30, 0.5, 0.5, 0.5, 0.05);
                        }
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                            if (large) {
                                location.getWorld().spawnParticle(Particle.LAVA, location, 20, 1.0, 1.0, 1.0, 0.1);
                            } else {
                                location.getWorld().spawnParticle(Particle.FLAME, location, 30, 0.5, 0.5, 0.5, 0.05);
                            }
                        });
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        });

        operations.put("particle_player_spawn", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String particleName = ctx.getInputValue(node, "particle_type", String.class, "FLAME");
            Integer count = ctx.getInputValue(node, "count", Integer.class, 1);
            Double offsetX = ctx.getInputValue(node, "offset_x", Double.class, 0.0);
            Double offsetY = ctx.getInputValue(node, "offset_y", Double.class, 0.0);
            Double offsetZ = ctx.getInputValue(node, "offset_z", Double.class, 0.0);
            Double speed = ctx.getInputValue(node, "speed", Double.class, 0.0);

            if (player != null && location != null && location.getWorld() != null) {
                try {
                    Particle particle = Particle.valueOf(particleName.toUpperCase());
                    if (Bukkit.isPrimaryThread()) {
                        player.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, speed, null);
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () ->
                                player.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, speed, null));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        });

        operations.put("particle_ellipse", (ctx, node) -> {
            Location centerLocation = ctx.getInputValue(node, "center_location", Location.class, null);
            Double radiusX = ctx.getInputValue(node, "radius_x", Double.class, 5.0);
            Double radiusZ = ctx.getInputValue(node, "radius_z", Double.class, 3.0);
            String particleName = ctx.getInputValue(node, "particle_type", String.class, "FLAME");
            Integer count = ctx.getInputValue(node, "count", Integer.class, 30);

            if (centerLocation != null && centerLocation.getWorld() != null && count > 0) {
                try {
                    Particle particle = Particle.valueOf(particleName.toUpperCase());

                    if (Bukkit.isPrimaryThread()) {
                        for (int i = 0; i < count; i++) {
                            double angle = (2 * Math.PI * i) / count;
                            double x = centerLocation.getX() + radiusX * Math.cos(angle);
                            double z = centerLocation.getZ() + radiusZ * Math.sin(angle);
                            Location loc = new Location(centerLocation.getWorld(), x, centerLocation.getY(), z);
                            centerLocation.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                        }
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                            for (int i = 0; i < count; i++) {
                                double angle = (2 * Math.PI * i) / count;
                                double x = centerLocation.getX() + radiusX * Math.cos(angle);
                                double z = centerLocation.getZ() + radiusZ * Math.sin(angle);
                                Location loc = new Location(centerLocation.getWorld(), x, centerLocation.getY(), z);
                                centerLocation.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                            }
                        });
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        });

        operations.put("particle_spiral", (ctx, node) -> {
            Location centerLocation = ctx.getInputValue(node, "center_location", Location.class, null);
            Double radius = ctx.getInputValue(node, "radius", Double.class, 3.0);
            Double height = ctx.getInputValue(node, "height", Double.class, 5.0);
            Integer rotations = ctx.getInputValue(node, "rotations", Integer.class, 2);
            String particleName = ctx.getInputValue(node, "particle_type", String.class, "FLAME");
            Integer count = ctx.getInputValue(node, "count", Integer.class, 50);

            if (centerLocation != null && centerLocation.getWorld() != null && count > 0) {
                try {
                    Particle particle = Particle.valueOf(particleName.toUpperCase());

                    if (Bukkit.isPrimaryThread()) {
                        for (int i = 0; i < count; i++) {
                            double angle = (2 * Math.PI * rotations * i) / count;
                            double y = (height * i) / count;
                            double x = centerLocation.getX() + radius * Math.cos(angle);
                            double z = centerLocation.getZ() + radius * Math.sin(angle);
                            Location loc = new Location(centerLocation.getWorld(), x, centerLocation.getY() + y, z);
                            centerLocation.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                        }
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                            for (int i = 0; i < count; i++) {
                                double angle = (2 * Math.PI * rotations * i) / count;
                                double y = (height * i) / count;
                                double x = centerLocation.getX() + radius * Math.cos(angle);
                                double z = centerLocation.getZ() + radius * Math.sin(angle);
                                Location loc = new Location(centerLocation.getWorld(), x, centerLocation.getY() + y, z);
                                centerLocation.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                            }
                        });
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        });

        operations.put("particle_cone", (ctx, node) -> {
            Location centerLocation = ctx.getInputValue(node, "center_location", Location.class, null);
            Double height = ctx.getInputValue(node, "height", Double.class, 5.0);
            Double radius = ctx.getInputValue(node, "radius", Double.class, 3.0);
            Vector directionVector = ctx.getInputValue(node, "direction_vector", Vector.class, new Vector(0, 1, 0));
            String particleName = ctx.getInputValue(node, "particle_type", String.class, "FLAME");
            Integer count = ctx.getInputValue(node, "count", Integer.class, 50);

            if (centerLocation != null && centerLocation.getWorld() != null && count > 0) {
                try {
                    Particle particle = Particle.valueOf(particleName.toUpperCase());
                    Vector direction = directionVector.clone().normalize();

                    if (Bukkit.isPrimaryThread()) {
                        for (int i = 0; i < count; i++) {
                            double progress = (double) i / count;
                            double currentRadius = radius * progress;
                            double currentHeight = height * progress;
                            double angle = (2 * Math.PI * i) % (2 * Math.PI);

                            Vector radial = new Vector(Math.cos(angle), 0, Math.sin(angle));
                            Vector side = direction.clone().getCrossProduct(new Vector(0, 1, 0)).normalize();
                            if (side.length() == 0) side = new Vector(1, 0, 0);
                            Vector up = side.clone().getCrossProduct(direction).normalize();

                            Vector offset = side.clone().multiply(radial.getX()).add(up.clone().multiply(radial.getZ())).multiply(currentRadius);
                            Vector heightOffset = direction.clone().multiply(currentHeight);

                            Location loc = centerLocation.clone().add(offset).add(heightOffset);
                            centerLocation.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                        }
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                            for (int i = 0; i < count; i++) {
                                double progress = (double) i / count;
                                double currentRadius = radius * progress;
                                double currentHeight = height * progress;
                                double angle = (2 * Math.PI * i) % (2 * Math.PI);

                                Vector radial = new Vector(Math.cos(angle), 0, Math.sin(angle));
                                Vector side = direction.clone().getCrossProduct(new Vector(0, 1, 0)).normalize();
                                if (side.length() == 0) side = new Vector(1, 0, 0);
                                Vector up = side.clone().getCrossProduct(direction).normalize();

                                Vector offset = side.clone().multiply(radial.getX()).add(up.clone().multiply(radial.getZ())).multiply(currentRadius);
                                Vector heightOffset = direction.clone().multiply(currentHeight);

                                Location loc = centerLocation.clone().add(offset).add(heightOffset);
                                centerLocation.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                            }
                        });
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        });

        operations.put("particle_ring", (ctx, node) -> {
            Location centerLocation = ctx.getInputValue(node, "center_location", Location.class, null);
            Double radius = ctx.getInputValue(node, "radius", Double.class, 5.0);
            String particleName = ctx.getInputValue(node, "particle_type", String.class, "FLAME");
            Integer count = ctx.getInputValue(node, "count", Integer.class, 30);
            String axis = ctx.getInputValue(node, "axis", String.class, "y");

            if (centerLocation != null && centerLocation.getWorld() != null && radius > 0 && count > 0) {
                try {
                    Particle particle = Particle.valueOf(particleName.toUpperCase());

                    if (Bukkit.isPrimaryThread()) {
                        for (int i = 0; i < count; i++) {
                            double angle = (2 * Math.PI * i) / count;
                            double x, y, z;

                            switch (axis.toLowerCase()) {
                                case "x" -> {
                                    x = centerLocation.getX();
                                    y = centerLocation.getY() + radius * Math.cos(angle);
                                    z = centerLocation.getZ() + radius * Math.sin(angle);
                                }
                                case "y" -> {
                                    x = centerLocation.getX() + radius * Math.cos(angle);
                                    y = centerLocation.getY();
                                    z = centerLocation.getZ() + radius * Math.sin(angle);
                                }
                                case "z" -> {
                                    x = centerLocation.getX() + radius * Math.cos(angle);
                                    y = centerLocation.getY() + radius * Math.sin(angle);
                                    z = centerLocation.getZ();
                                }
                                default -> {
                                    x = centerLocation.getX() + radius * Math.cos(angle);
                                    y = centerLocation.getY();
                                    z = centerLocation.getZ() + radius * Math.sin(angle);
                                }
                            }

                            Location loc = new Location(centerLocation.getWorld(), x, y, z);
                            centerLocation.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                        }
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                            for (int i = 0; i < count; i++) {
                                double angle = (2 * Math.PI * i) / count;
                                double x, y, z;

                                switch (axis.toLowerCase()) {
                                    case "x" -> {
                                        x = centerLocation.getX();
                                        y = centerLocation.getY() + radius * Math.cos(angle);
                                        z = centerLocation.getZ() + radius * Math.sin(angle);
                                    }
                                    case "y" -> {
                                        x = centerLocation.getX() + radius * Math.cos(angle);
                                        y = centerLocation.getY();
                                        z = centerLocation.getZ() + radius * Math.sin(angle);
                                    }
                                    case "z" -> {
                                        x = centerLocation.getX() + radius * Math.cos(angle);
                                        y = centerLocation.getY() + radius * Math.sin(angle);
                                        z = centerLocation.getZ();
                                    }
                                    default -> {
                                        x = centerLocation.getX() + radius * Math.cos(angle);
                                        y = centerLocation.getY();
                                        z = centerLocation.getZ() + radius * Math.sin(angle);
                                    }
                                }

                                Location loc = new Location(centerLocation.getWorld(), x, y, z);
                                centerLocation.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                            }
                        });
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        });

        operations.put("particle_cube", (ctx, node) -> {
            Location minLocation = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLocation = ctx.getInputValue(node, "max_location", Location.class, null);
            String particleName = ctx.getInputValue(node, "particle_type", String.class, "FLAME");
            Boolean isFilled = ctx.getInputValue(node, "is_filled", Boolean.class, false);

            if (minLocation != null && maxLocation != null && minLocation.getWorld() != null && minLocation.getWorld().equals(maxLocation.getWorld())) {
                try {
                    Particle particle = Particle.valueOf(particleName.toUpperCase());
                    double minX = Math.min(minLocation.getX(), maxLocation.getX());
                    double maxX = Math.max(minLocation.getX(), maxLocation.getX());
                    double minY = Math.min(minLocation.getY(), maxLocation.getY());
                    double maxY = Math.max(minLocation.getY(), maxLocation.getY());
                    double minZ = Math.min(minLocation.getZ(), maxLocation.getZ());
                    double maxZ = Math.max(minLocation.getZ(), maxLocation.getZ());
                    double step = isFilled ? 0.5 : 1.0;

                    if (Bukkit.isPrimaryThread()) {
                        for (double x = minX; x <= maxX; x += step) {
                            for (double y = minY; y <= maxY; y += step) {
                                for (double z = minZ; z <= maxZ; z += step) {
                                    boolean onEdge = Math.abs(x - minX) < step || Math.abs(x - maxX) < step ||
                                            Math.abs(y - minY) < step || Math.abs(y - maxY) < step ||
                                            Math.abs(z - minZ) < step || Math.abs(z - maxZ) < step;

                                    if (isFilled || onEdge) {
                                        Location loc = new Location(minLocation.getWorld(), x, y, z);
                                        minLocation.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                                    }
                                }
                            }
                        }
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                            for (double x = minX; x <= maxX; x += step) {
                                for (double y = minY; y <= maxY; y += step) {
                                    for (double z = minZ; z <= maxZ; z += step) {
                                        boolean onEdge = Math.abs(x - minX) < step || Math.abs(x - maxX) < step ||
                                                Math.abs(y - minY) < step || Math.abs(y - maxY) < step ||
                                                Math.abs(z - minZ) < step || Math.abs(z - maxZ) < step;

                                        if (isFilled || onEdge) {
                                            Location loc = new Location(minLocation.getWorld(), x, y, z);
                                            minLocation.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                                        }
                                    }
                                }
                            }
                        });
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        });

        operations.put("particle_wave", (ctx, node) -> {
            Location startLocation = ctx.getInputValue(node, "start_location", Location.class, null);
            Vector direction = ctx.getInputValue(node, "direction", Vector.class, new Vector(1, 0, 0));
            Double amplitude = ctx.getInputValue(node, "amplitude", Double.class, 1.0);
            Double frequency = ctx.getInputValue(node, "frequency", Double.class, 0.5);
            Double length = ctx.getInputValue(node, "length", Double.class, 10.0);
            String particleName = ctx.getInputValue(node, "particle_type", String.class, "FLAME");

            if (startLocation != null && startLocation.getWorld() != null) {
                try {
                    Particle particle = Particle.valueOf(particleName.toUpperCase());
                    Vector dir = direction.clone().normalize();
                    int steps = (int) (length * 10);

                    if (Bukkit.isPrimaryThread()) {
                        for (int i = 0; i <= steps; i++) {
                            double distance = (length * i) / steps;
                            double waveOffset = Math.sin(distance * frequency) * amplitude;

                            Vector forward = dir.clone().multiply(distance);
                            Vector waveUp = new Vector(0, 1, 0);
                            Vector waveDir = dir.clone().getCrossProduct(waveUp).normalize();
                            if (waveDir.length() < 0.1) waveDir = new Vector(0, 0, 1);
                            Vector offset = waveDir.multiply(waveOffset);

                            Location loc = startLocation.clone().add(forward).add(offset);
                            startLocation.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                        }
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                            for (int i = 0; i <= steps; i++) {
                                double distance = (length * i) / steps;
                                double waveOffset = Math.sin(distance * frequency) * amplitude;

                                Vector forward = dir.clone().multiply(distance);
                                Vector waveUp = new Vector(0, 1, 0);
                                Vector waveDir = dir.clone().getCrossProduct(waveUp).normalize();
                                if (waveDir.length() < 0.1) waveDir = new Vector(0, 0, 1);
                                Vector offset = waveDir.multiply(waveOffset);

                                Location loc = startLocation.clone().add(forward).add(offset);
                                startLocation.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                            }
                        });
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        });

        operations.put("particle_text", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String text = clampText(ctx.getInputValue(node, "text", String.class, "A"));
            String particleName = ctx.getInputValue(node, "particle_type", String.class, "FLAME");
            Double size = ctx.getInputValue(node, "size", Double.class, 0.3);

            if (location != null && location.getWorld() != null && text != null && !text.isEmpty()) {
                try {
                    Particle particle = Particle.valueOf(particleName.toUpperCase());
                    Font font = new Font("Arial", Font.BOLD, 100);
                    BufferedImage measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D measureGraphics = measure.createGraphics();
                    measureGraphics.setFont(font);
                    FontMetrics fm = measureGraphics.getFontMetrics();
                    int textWidth = fm.stringWidth(text);
                    int textHeight = fm.getHeight();
                    int imageWidth = Math.min(MAX_TEXT_IMAGE_WIDTH, Math.max(64, textWidth + 24));
                    int imageHeight = Math.min(MAX_TEXT_IMAGE_HEIGHT, Math.max(64, textHeight + 24));
                    measureGraphics.dispose();
                    BufferedImage image = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g2d = image.createGraphics();
                    g2d.setFont(font);
                    g2d.setColor(Color.WHITE);
                    g2d.drawString(text, 12, 12 + fm.getAscent());
                    g2d.dispose();

                    int minPixelX = imageWidth;
                    int maxPixelX = 0;
                    int minPixelY = imageHeight;
                    int maxPixelY = 0;
                    for (int y = 0; y < imageHeight; y += 2) {
                        for (int x = 0; x < imageWidth; x += 2) {
                            int rgb = image.getRGB(x, y);
                            if ((rgb & 0xFF000000) != 0) {
                                minPixelX = Math.min(minPixelX, x);
                                maxPixelX = Math.max(maxPixelX, x);
                                minPixelY = Math.min(minPixelY, y);
                                maxPixelY = Math.max(maxPixelY, y);
                            }
                        }
                    }
                    if (minPixelX > maxPixelX || minPixelY > maxPixelY) {
                        return;
                    }
                    double centerPixelX = (minPixelX + maxPixelX) / 2.0;
                    double centerPixelY = (minPixelY + maxPixelY) / 2.0;
                    double particleScale = Math.max(0.005, size) / 10.0;

                    if (Bukkit.isPrimaryThread()) {
                        emitTextParticles(location, particle, image, imageWidth, imageHeight, centerPixelX, centerPixelY, particleScale);
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> emitTextParticles(location, particle, image, imageWidth, imageHeight, centerPixelX, centerPixelY, particleScale));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        });

        operations.put("particle_spawn", (ctx, node) -> applyLegacyParticle(ctx, node, "point"));
        operations.put("particle_area", (ctx, node) -> applyLegacyParticle(ctx, node, "area"));
        operations.put("particle_player_spawn", (ctx, node) -> applyLegacyParticle(ctx, node, "player"));
        operations.put("particle_line", (ctx, node) -> applyLegacyParticle(ctx, node, "line"));
        operations.put("particle_circle", (ctx, node) -> applyLegacyParticle(ctx, node, "circle"));
        operations.put("particle_sphere", (ctx, node) -> applyLegacyParticle(ctx, node, "sphere"));
        operations.put("particle_ellipse", (ctx, node) -> applyLegacyParticle(ctx, node, "ellipse"));
        operations.put("particle_spiral", (ctx, node) -> applyLegacyParticle(ctx, node, "spiral"));
        operations.put("particle_cone", (ctx, node) -> applyLegacyParticle(ctx, node, "cone"));
        operations.put("particle_ring", (ctx, node) -> applyLegacyParticle(ctx, node, "ring"));
        operations.put("particle_cube", (ctx, node) -> applyLegacyParticle(ctx, node, "cube"));
        operations.put("particle_wave", (ctx, node) -> applyLegacyParticle(ctx, node, "wave"));
        operations.put("particle_text", (ctx, node) -> applyLegacyParticle(ctx, node, "text"));
        operations.put("particle_block_dust", (ctx, node) -> applyLegacyParticle(ctx, node, "block_dust"));
        operations.put("particle_item_break", (ctx, node) -> applyLegacyParticle(ctx, node, "item_break"));
        operations.put("particle_explosion", (ctx, node) -> applyLegacyParticle(ctx, node, "explosion"));
    }

    private void applyLegacyParticle(FlowContext ctx, FlowNode node, String mode) {
        Map<String, Object> inputs = new java.util.HashMap<>(node.getInputValues() != null ? node.getInputValues() : Map.of());
        inputs.put("mode", mode);
        copyIfMissing(inputs, "particle", "particle_type");
        copyIfMissing(inputs, "location", "center_location");
        copyIfMissing(inputs, "filled", "is_filled");
        copyIfMissing(inputs, "count", "points");
        FlowNode particleNode = new FlowNode("particle.apply", node.getX(), node.getY(), inputs);
        particleNode.setHandlerConfig(Map.of("operation", "particle_apply"));
        applyParticle(ctx, particleNode);
    }

    private void copyIfMissing(Map<String, Object> inputs, String target, String source) {
        if (!inputs.containsKey(target) && inputs.containsKey(source)) {
            inputs.put(target, inputs.get(source));
        }
    }

    private void applyParticle(FlowContext ctx, FlowNode node) {
        String spawnType = text(ctx, node, "mode", text(ctx, node, "spawn_type", "point")).toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        String particleName = text(ctx, node, "particle", text(ctx, node, "particle_type", "FLAME"));
        Particle particle = parseParticle(particleName);

        switch (spawnType) {
            case "point", "spawn" -> spawnPoint(ctx, node, particle);
            case "burst" -> spawnBurst(ctx, node, particle);
            case "player", "player_spawn" -> spawnForPlayer(ctx, node, particle);
            case "area" -> spawnArea(ctx, node, particle, true);
            case "line" -> spawnLine(ctx, node, particle);
            case "circle" -> spawnCircle(ctx, node, particle);
            case "ring" -> spawnRing(ctx, node, particle);
            case "sphere" -> spawnSphere(ctx, node, particle);
            case "ellipse" -> spawnEllipse(ctx, node, particle);
            case "spiral" -> spawnSpiral(ctx, node, particle);
            case "cone" -> spawnCone(ctx, node, particle);
            case "cube" -> spawnCube(ctx, node, particle);
            case "wave" -> spawnWave(ctx, node, particle);
            case "text" -> spawnText(ctx, node, particle);
            case "block_dust", "block" -> spawnBlockDust(ctx, node);
            case "item_break", "item" -> spawnItemBreak(ctx, node);
            case "explosion" -> spawnExplosion(ctx, node);
            default -> spawnPoint(ctx, node, particle);
        }
    }

    private void spawnPoint(FlowContext ctx, FlowNode node, Particle particle) {
        Location location = location(ctx, node, "location");
        if (!valid(location)) return;
        int count = integer(ctx, node, "count", 1);
        double offsetX = decimal(ctx, node, "offset_x", 0.0);
        double offsetY = decimal(ctx, node, "offset_y", 0.0);
        double offsetZ = decimal(ctx, node, "offset_z", 0.0);
        double speed = decimal(ctx, node, "speed", 0.0);
        run(location, () -> location.getWorld().spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, speed, null));
    }

    private void spawnBurst(FlowContext ctx, FlowNode node, Particle particle) {
        Location location = location(ctx, node, "location");
        if (!valid(location)) return;
        int count = integer(ctx, node, "count", 24);
        double spread = decimal(ctx, node, "spread", 0.6);
        double speed = decimal(ctx, node, "speed", 0.02);
        run(location, () -> location.getWorld().spawnParticle(particle, location, count, spread, spread, spread, speed, null));
    }

    private void spawnForPlayer(FlowContext ctx, FlowNode node, Particle particle) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        Location location = location(ctx, node, "location");
        if (player == null || !valid(location)) return;
        int count = integer(ctx, node, "count", 1);
        double offsetX = decimal(ctx, node, "offset_x", 0.0);
        double offsetY = decimal(ctx, node, "offset_y", 0.0);
        double offsetZ = decimal(ctx, node, "offset_z", 0.0);
        double speed = decimal(ctx, node, "speed", 0.0);
        run(location, () -> player.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, speed, null));
    }

    private void spawnArea(FlowContext ctx, FlowNode node, Particle particle, boolean filled) {
        Location min = location(ctx, node, "min_location");
        Location max = location(ctx, node, "max_location");
        if (!sameWorld(min, max)) return;
        int density = Math.max(1, integer(ctx, node, "density", 10));
        double stepX = Math.max(0.5, Math.abs(max.getX() - min.getX()) / density);
        double stepY = Math.max(0.5, Math.abs(max.getY() - min.getY()) / density);
        double stepZ = Math.max(0.5, Math.abs(max.getZ() - min.getZ()) / density);
        double minX = Math.min(min.getX(), max.getX());
        double maxX = Math.max(min.getX(), max.getX());
        double minY = Math.min(min.getY(), max.getY());
        double maxY = Math.max(min.getY(), max.getY());
        double minZ = Math.min(min.getZ(), max.getZ());
        double maxZ = Math.max(min.getZ(), max.getZ());
        run(min, () -> {
            for (double x = minX; x <= maxX; x += stepX) {
                for (double y = minY; y <= maxY; y += stepY) {
                    for (double z = minZ; z <= maxZ; z += stepZ) {
                        Location loc = new Location(min.getWorld(), x, y, z);
                        min.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                    }
                }
            }
        });
    }

    private void spawnLine(FlowContext ctx, FlowNode node, Particle particle) {
        Location start = location(ctx, node, "start_location");
        Location end = location(ctx, node, "end_location");
        if (!sameWorld(start, end)) return;
        int density = Math.max(1, integer(ctx, node, "density", 10));
        double distance = start.distance(end);
        if (distance <= 0.0) {
            run(start, () -> start.getWorld().spawnParticle(particle, start, 1, 0, 0, 0, 0));
            return;
        }
        run(start, () -> {
            for (int i = 0; i <= density; i++) {
                double ratio = i / (double) density;
                Location loc = new Location(start.getWorld(),
                    start.getX() + (end.getX() - start.getX()) * ratio,
                    start.getY() + (end.getY() - start.getY()) * ratio,
                    start.getZ() + (end.getZ() - start.getZ()) * ratio);
                start.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
            }
        });
    }

    private void spawnCircle(FlowContext ctx, FlowNode node, Particle particle) {
        Location center = location(ctx, node, "location");
        if (!valid(center)) return;
        double radius = Math.max(0.0, decimal(ctx, node, "radius", 3.0));
        int count = Math.max(1, integer(ctx, node, "count", integer(ctx, node, "points", 30)));
        run(center, () -> {
            for (int i = 0; i < count; i++) {
                double angle = (2 * Math.PI * i) / count;
                Location loc = new Location(center.getWorld(), center.getX() + radius * Math.cos(angle), center.getY(), center.getZ() + radius * Math.sin(angle));
                center.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
            }
        });
    }

    private void spawnRing(FlowContext ctx, FlowNode node, Particle particle) {
        Location center = location(ctx, node, "location");
        if (!valid(center)) return;
        double radius = Math.max(0.0, decimal(ctx, node, "radius", 3.0));
        int count = Math.max(1, integer(ctx, node, "count", 30));
        String axis = text(ctx, node, "axis", "y").toLowerCase(Locale.ROOT);
        run(center, () -> {
            for (int i = 0; i < count; i++) {
                double angle = (2 * Math.PI * i) / count;
                double x;
                double y;
                double z;
                switch (axis) {
                    case "x" -> {
                        x = center.getX();
                        y = center.getY() + radius * Math.cos(angle);
                        z = center.getZ() + radius * Math.sin(angle);
                    }
                    case "z" -> {
                        x = center.getX() + radius * Math.cos(angle);
                        y = center.getY() + radius * Math.sin(angle);
                        z = center.getZ();
                    }
                    default -> {
                        x = center.getX() + radius * Math.cos(angle);
                        y = center.getY();
                        z = center.getZ() + radius * Math.sin(angle);
                    }
                }
                center.getWorld().spawnParticle(particle, new Location(center.getWorld(), x, y, z), 1, 0, 0, 0, 0);
            }
        });
    }

    private void spawnSphere(FlowContext ctx, FlowNode node, Particle particle) {
        Location center = location(ctx, node, "location");
        if (!valid(center)) return;
        double radius = Math.max(0.0, decimal(ctx, node, "radius", 3.0));
        int count = Math.max(2, integer(ctx, node, "count", integer(ctx, node, "points", 50)));
        run(center, () -> {
            double phi = Math.PI * (3.0 - Math.sqrt(5.0));
            for (int i = 0; i < count; i++) {
                double y = 1.0 - (i / (double) (count - 1)) * 2.0;
                double radiusAtY = Math.sqrt(1.0 - y * y);
                double theta = phi * i;
                double x = Math.cos(theta) * radiusAtY;
                double z = Math.sin(theta) * radiusAtY;
                Location loc = new Location(center.getWorld(), center.getX() + x * radius, center.getY() + y * radius, center.getZ() + z * radius);
                center.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
            }
        });
    }

    private void spawnEllipse(FlowContext ctx, FlowNode node, Particle particle) {
        Location center = location(ctx, node, "location");
        if (!valid(center)) return;
        double radiusX = decimal(ctx, node, "radius_x", 5.0);
        double radiusZ = decimal(ctx, node, "radius_z", 3.0);
        int count = Math.max(1, integer(ctx, node, "count", 30));
        run(center, () -> {
            for (int i = 0; i < count; i++) {
                double angle = (2 * Math.PI * i) / count;
                Location loc = new Location(center.getWorld(), center.getX() + radiusX * Math.cos(angle), center.getY(), center.getZ() + radiusZ * Math.sin(angle));
                center.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
            }
        });
    }

    private void spawnSpiral(FlowContext ctx, FlowNode node, Particle particle) {
        Location center = location(ctx, node, "location");
        if (!valid(center)) return;
        double radius = decimal(ctx, node, "radius", 3.0);
        double height = decimal(ctx, node, "height", 5.0);
        double rotations = decimal(ctx, node, "rotations", 3.0);
        int count = Math.max(1, integer(ctx, node, "count", 100));
        run(center, () -> {
            for (int i = 0; i < count; i++) {
                double angle = (2 * Math.PI * rotations * i) / count;
                double y = (height * i) / count;
                Location loc = new Location(center.getWorld(), center.getX() + radius * Math.cos(angle), center.getY() + y, center.getZ() + radius * Math.sin(angle));
                center.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
            }
        });
    }

    private void spawnCone(FlowContext ctx, FlowNode node, Particle particle) {
        Location center = location(ctx, node, "location");
        if (!valid(center)) return;
        double radius = decimal(ctx, node, "radius", 3.0);
        double height = decimal(ctx, node, "height", 5.0);
        int count = Math.max(1, integer(ctx, node, "count", 50));
        run(center, () -> {
            for (int i = 0; i < count; i++) {
                double progress = i / (double) count;
                double angle = (2 * Math.PI * i) % (2 * Math.PI);
                Location loc = new Location(center.getWorld(),
                    center.getX() + radius * progress * Math.cos(angle),
                    center.getY() + height * progress,
                    center.getZ() + radius * progress * Math.sin(angle));
                center.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
            }
        });
    }

    private void spawnCube(FlowContext ctx, FlowNode node, Particle particle) {
        Location min = location(ctx, node, "min_location");
        Location max = location(ctx, node, "max_location");
        if (!sameWorld(min, max)) return;
        boolean filled = bool(ctx, node, "filled", bool(ctx, node, "is_filled", false));
        double step = Math.max(0.1, decimal(ctx, node, "step", filled ? 0.5 : 1.0));
        double minX = Math.min(min.getX(), max.getX());
        double maxX = Math.max(min.getX(), max.getX());
        double minY = Math.min(min.getY(), max.getY());
        double maxY = Math.max(min.getY(), max.getY());
        double minZ = Math.min(min.getZ(), max.getZ());
        double maxZ = Math.max(min.getZ(), max.getZ());
        run(min, () -> {
            for (double x = minX; x <= maxX; x += step) {
                for (double y = minY; y <= maxY; y += step) {
                    for (double z = minZ; z <= maxZ; z += step) {
                        boolean onEdge = Math.abs(x - minX) < step || Math.abs(x - maxX) < step || Math.abs(y - minY) < step || Math.abs(y - maxY) < step || Math.abs(z - minZ) < step || Math.abs(z - maxZ) < step;
                        if (filled || onEdge) {
                            min.getWorld().spawnParticle(particle, new Location(min.getWorld(), x, y, z), 1, 0, 0, 0, 0);
                        }
                    }
                }
            }
        });
    }

    private void spawnWave(FlowContext ctx, FlowNode node, Particle particle) {
        Location start = location(ctx, node, "start_location");
        if (!valid(start)) return;
        Vector direction = ctx.getInputValue(node, "direction", Vector.class, new Vector(1, 0, 0));
        double amplitude = decimal(ctx, node, "amplitude", 1.0);
        double frequency = decimal(ctx, node, "frequency", 0.5);
        double length = decimal(ctx, node, "length", 10.0);
        int steps = Math.max(1, (int) (length * 10));
        Vector dir = direction.clone().normalize();
        run(start, () -> {
            for (int i = 0; i <= steps; i++) {
                double distance = (length * i) / steps;
                double waveOffset = Math.sin(distance * frequency) * amplitude;
                Vector forward = dir.clone().multiply(distance);
                Vector waveDir = dir.clone().getCrossProduct(new Vector(0, 1, 0)).normalize();
                if (waveDir.length() < 0.1) waveDir = new Vector(0, 0, 1);
                Location loc = start.clone().add(forward).add(waveDir.multiply(waveOffset));
                start.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
            }
        });
    }

    private void spawnText(FlowContext ctx, FlowNode node, Particle particle) {
        Location location = location(ctx, node, "location");
        String text = clampText(text(ctx, node, "text", "A"));
        double size = decimal(ctx, node, "size", 0.3);
        if (!valid(location) || text.isEmpty()) return;
        run(location, () -> {
            Font font = new Font("Arial", Font.BOLD, 100);
            BufferedImage measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D measureGraphics = measure.createGraphics();
            measureGraphics.setFont(font);
            FontMetrics fm = measureGraphics.getFontMetrics();
            int textWidth = fm.stringWidth(text);
            int textHeight = fm.getHeight();
            int imageWidth = Math.min(MAX_TEXT_IMAGE_WIDTH, Math.max(64, textWidth + 24));
            int imageHeight = Math.min(MAX_TEXT_IMAGE_HEIGHT, Math.max(64, textHeight + 24));
            measureGraphics.dispose();
            BufferedImage image = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = image.createGraphics();
            g2d.setFont(font);
            g2d.setColor(Color.WHITE);
            g2d.drawString(text, 12, 12 + fm.getAscent());
            g2d.dispose();
            int minPixelX = imageWidth;
            int maxPixelX = 0;
            int minPixelY = imageHeight;
            int maxPixelY = 0;
            for (int y = 0; y < imageHeight; y += 2) {
                for (int x = 0; x < imageWidth; x += 2) {
                    int rgb = image.getRGB(x, y);
                    if ((rgb & 0xFF000000) != 0) {
                        minPixelX = Math.min(minPixelX, x);
                        maxPixelX = Math.max(maxPixelX, x);
                        minPixelY = Math.min(minPixelY, y);
                        maxPixelY = Math.max(maxPixelY, y);
                    }
                }
            }
            if (minPixelX > maxPixelX || minPixelY > maxPixelY) {
                return;
            }
            double centerPixelX = (minPixelX + maxPixelX) / 2.0;
            double centerPixelY = (minPixelY + maxPixelY) / 2.0;
            double particleScale = Math.max(0.005, size) / 10.0;
            emitTextParticles(location, particle, image, imageWidth, imageHeight, centerPixelX, centerPixelY, particleScale);
        });
    }

    private String clampText(String text) {
        String value = text != null ? text : "";
        return value.length() > MAX_TEXT_LENGTH ? value.substring(0, MAX_TEXT_LENGTH) : value;
    }

    private void emitTextParticles(Location location, Particle particle, BufferedImage image, int imageWidth, int imageHeight, double centerPixelX, double centerPixelY, double particleScale) {
        int emitted = 0;
        for (int y = 0; y < imageHeight && emitted < MAX_TEXT_PARTICLES; y += 2) {
            for (int x = 0; x < imageWidth && emitted < MAX_TEXT_PARTICLES; x += 2) {
                int rgb = image.getRGB(x, y);
                if ((rgb & 0xFF000000) != 0) {
                    Location loc = location.clone().add((x - centerPixelX) * particleScale, (centerPixelY - y) * particleScale, 0);
                    location.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                    emitted++;
                }
            }
        }
    }

    private void spawnBlockDust(FlowContext ctx, FlowNode node) {
        Location location = location(ctx, node, "location");
        if (!valid(location)) return;
        Material blockType = Material.getMaterial(text(ctx, node, "block_type", "STONE").toUpperCase(Locale.ROOT));
        if (blockType == null || !blockType.isBlock()) return;
        int count = integer(ctx, node, "count", 10);
        BlockData blockData = blockType.createBlockData();
        run(location, () -> location.getWorld().spawnParticle(Particle.BLOCK, location, count, blockData));
    }

    private void spawnItemBreak(FlowContext ctx, FlowNode node) {
        Location location = location(ctx, node, "location");
        if (!valid(location)) return;
        Material itemType = Material.getMaterial(text(ctx, node, "item_type", "STONE").toUpperCase(Locale.ROOT));
        if (itemType == null || !itemType.isItem()) return;
        run(location, () -> location.getWorld().spawnParticle(Particle.ITEM, location, 1, 0, 0, 0, 0, new ItemStack(itemType)));
    }

    private void spawnExplosion(FlowContext ctx, FlowNode node) {
        Location location = location(ctx, node, "location");
        if (!valid(location)) return;
        boolean large = bool(ctx, node, "large", false);
        run(location, () -> {
            if (large) {
                location.getWorld().spawnParticle(Particle.LAVA, location, 20, 1.0, 1.0, 1.0, 0.1);
            } else {
                location.getWorld().spawnParticle(Particle.FLAME, location, 30, 0.5, 0.5, 0.5, 0.05);
            }
        });
    }

    private Particle parseParticle(String name) {
        String value = name == null || name.isBlank() ? "FLAME" : name.trim();
        if (value.contains(":")) {
            String key = value.toLowerCase(Locale.ROOT);
            for (Particle particle : org.bukkit.Registry.PARTICLE_TYPE) {
                if (particle.getKey().toString().equalsIgnoreCase(key)) {
                    return particle;
                }
            }
            value = value.substring(value.indexOf(':') + 1);
        }
        value = value.replace('.', '_').replace('-', '_');
        try {
            return Particle.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Particle.FLAME;
        }
    }

    private void run(Location location, Runnable task) {
        if (!valid(location) || task == null) return;
        Runnable safeTask = () -> {
            try {
                task.run();
            } catch (Exception ignored) {
            }
        };
        if (Bukkit.isPrimaryThread()) {
            safeTask.run();
        } else {
            Bukkit.getScheduler().runTask(ReSync.getInstance(), safeTask);
        }
    }

    private boolean valid(Location location) {
        return location != null && location.getWorld() != null;
    }

    private boolean sameWorld(Location first, Location second) {
        return valid(first) && valid(second) && first.getWorld().equals(second.getWorld());
    }

    private Location location(FlowContext ctx, FlowNode node, String pin) {
        return ctx.getInputValue(node, pin, Location.class, null);
    }

    private String text(FlowContext ctx, FlowNode node, String pin, String fallback) {
        String value = ctx.getInputValue(node, pin, String.class, fallback);
        return value != null ? value : fallback;
    }

    private int integer(FlowContext ctx, FlowNode node, String pin, int fallback) {
        Object value = ctx.getInputValue(node, pin);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return (int) Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private double decimal(FlowContext ctx, FlowNode node, String pin, double fallback) {
        Object value = ctx.getInputValue(node, pin);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private boolean bool(FlowContext ctx, FlowNode node, String pin, boolean fallback) {
        Boolean value = ctx.getInputValue(node, pin, Boolean.class, fallback);
        return value != null ? value : fallback;
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("ParticleHandler", this);
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
