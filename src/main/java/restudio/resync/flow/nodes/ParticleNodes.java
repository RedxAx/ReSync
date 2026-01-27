package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

import java.util.concurrent.atomic.AtomicInteger;

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

        registry.register("particle_player_spawn", (ctx, node) -> {
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
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> 
                            player.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, speed, null));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("particle_ellipse", (ctx, node) -> {
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
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("particle_spiral", (ctx, node) -> {
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
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("particle_cone", (ctx, node) -> {
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
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("particle_ring", (ctx, node) -> {
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
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("particle_cube", (ctx, node) -> {
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
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("particle_wave", (ctx, node) -> {
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
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("particle_text", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String text = ctx.getInputValue(node, "text", String.class, "A");
            String particleName = ctx.getInputValue(node, "particle_type", String.class, "FLAME");
            Double size = ctx.getInputValue(node, "size", Double.class, 0.3);

            if (location != null && location.getWorld() != null && text != null && !text.isEmpty()) {
                try {
                    Particle particle = Particle.valueOf(particleName.toUpperCase());
                    java.awt.Font font = new java.awt.Font("Arial", java.awt.Font.BOLD, 100);
                    java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(256, 128, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D g2d = image.createGraphics();
                    g2d.setFont(font);
                    g2d.setColor(java.awt.Color.WHITE);
                    java.awt.FontMetrics fm = g2d.getFontMetrics();
                    int width = fm.stringWidth(text);
                    int height = fm.getHeight();
                    g2d.drawString(text, (256 - width) / 2, (128 + height) / 2 - fm.getDescent());
                    g2d.dispose();

                    AtomicInteger particleCount = new AtomicInteger(0);
                    int maxParticles = 500;

                    if (Bukkit.isPrimaryThread()) {
                        for (int y = 0; y < 128 && particleCount.get() < maxParticles; y += 2) {
                            for (int x = 0; x < 256 && particleCount.get() < maxParticles; x += 2) {
                                int rgb = image.getRGB(x, y);
                                if ((rgb & 0xFF000000) != 0) {
                                    double px = (x - 128) * size;
                                    double py = (64 - y) * size;
                                    Location loc = location.clone().add(px, py, 0);
                                    location.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                                    particleCount.incrementAndGet();
                                }
                            }
                        }
                    } else {
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                            for (int y = 0; y < 128 && particleCount.get() < maxParticles; y += 2) {
                                for (int x = 0; x < 256 && particleCount.get() < maxParticles; x += 2) {
                                    int rgb = image.getRGB(x, y);
                                    if ((rgb & 0xFF000000) != 0) {
                                        double px = (x - 128) * size;
                                        double py = (64 - y) * size;
                                        Location loc = location.clone().add(px, py, 0);
                                        location.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                                        particleCount.incrementAndGet();
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
