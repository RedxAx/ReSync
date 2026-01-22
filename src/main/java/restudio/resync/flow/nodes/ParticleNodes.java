package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

public class ParticleNodes implements NodeCategory {

    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("particle_spawn", (ctx, node) -> {
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
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> location.getWorld().spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, speed, null));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("particle_area", (ctx, node) -> {
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
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("particle_line", (ctx, node) -> {
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
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("particle_circle", (ctx, node) -> {
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
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("particle_sphere", (ctx, node) -> {
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
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("particle_block_dust", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String blockTypeName = ctx.getInputValue(node, "block_type", String.class, "STONE");
            Integer count = ctx.getInputValue(node, "count", Integer.class, 10);

            if (location != null && location.getWorld() != null) {
                Material blockType = Material.getMaterial(blockTypeName.toUpperCase());
                if (blockType != null && blockType.isBlock()) {
                    org.bukkit.block.data.BlockData blockData = blockType.createBlockData();
                    if (Bukkit.isPrimaryThread()) {
                        location.getWorld().spawnParticle(Particle.BLOCK, location, count, blockData);
                    } else {
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () ->
                            location.getWorld().spawnParticle(Particle.BLOCK, location, count, blockData));
                    }
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("particle_item_break", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String itemTypeName = ctx.getInputValue(node, "item_type", String.class, "STONE");

            if (location != null && location.getWorld() != null) {
                Material itemType = Material.getMaterial(itemTypeName.toUpperCase());
                if (itemType != null && itemType.isItem()) {
                    if (Bukkit.isPrimaryThread()) {
                        location.getWorld().spawnParticle(Particle.ITEM, location, 1, 0, 0, 0, 0, 
                            new org.bukkit.inventory.ItemStack(itemType));
                    } else {
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () ->
                            location.getWorld().spawnParticle(Particle.ITEM, location, 1, 0, 0, 0, 0,
                                new org.bukkit.inventory.ItemStack(itemType)));
                    }
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("particle_explosion", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String particleName = ctx.getInputValue(node, "particle_type", String.class, "EXPLOSION");
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
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
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
