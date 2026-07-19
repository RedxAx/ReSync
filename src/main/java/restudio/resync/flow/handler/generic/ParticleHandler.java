package restudio.resync.flow.handler.generic;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class ParticleHandler implements NodeHandler {
    private static final int MAX_TEXT_LENGTH = 80;
    private static final int MAX_TEXT_IMAGE_WIDTH = 512;
    private static final int MAX_TEXT_IMAGE_HEIGHT = 160;
    private static final int MAX_TEXT_PARTICLES = 500;
    private static final int MAX_PARTICLES_PER_ACTION = 10_000;
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public ParticleHandler() {
        operations.put("particle_apply", this::applyParticle);
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
        Map<String, Object> inputs = new HashMap<>(node.getInputValues() != null ? node.getInputValues() : Map.of());
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
            case "area" -> spawnArea(ctx, node, particle);
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
            default -> throw new IllegalArgumentException("Unknown particle mode: " + spawnType);
        }
    }

    private void spawnPoint(FlowContext ctx, FlowNode node, Particle particle) {
        Location location = location(ctx, node, "location");
        requireLocation(location);
        int count = boundedCount(integer(ctx, node, "count", 1), 0, "count");
        double offsetX = decimal(ctx, node, "offset_x", 0.0);
        double offsetY = decimal(ctx, node, "offset_y", 0.0);
        double offsetZ = decimal(ctx, node, "offset_z", 0.0);
        double speed = decimal(ctx, node, "speed", 0.0);
        requireParticleMotion(offsetX, offsetY, offsetZ, speed);
        run(location, () -> location.getWorld().spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, speed, null));
    }

    private void spawnBurst(FlowContext ctx, FlowNode node, Particle particle) {
        Location location = location(ctx, node, "location");
        requireLocation(location);
        int count = boundedCount(integer(ctx, node, "count", 24), 0, "count");
        double spread = decimal(ctx, node, "spread", 0.6);
        double speed = decimal(ctx, node, "speed", 0.02);
        if (spread < 0.0 || spread > 1024.0) throw new IllegalArgumentException("Particle burst spread must be between 0 and 1024");
        requireParticleMotion(spread, spread, spread, speed);
        run(location, () -> location.getWorld().spawnParticle(particle, location, count, spread, spread, spread, speed, null));
    }

    private void spawnForPlayer(FlowContext ctx, FlowNode node, Particle particle) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        Location location = location(ctx, node, "location");
        if (player == null) {
            throw new IllegalArgumentException("Player is required");
        }
        requireLocation(location);
        int count = boundedCount(integer(ctx, node, "count", 1), 0, "count");
        double offsetX = decimal(ctx, node, "offset_x", 0.0);
        double offsetY = decimal(ctx, node, "offset_y", 0.0);
        double offsetZ = decimal(ctx, node, "offset_z", 0.0);
        double speed = decimal(ctx, node, "speed", 0.0);
        requireParticleMotion(offsetX, offsetY, offsetZ, speed);
        run(location, () -> player.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, speed, null));
    }

    private void spawnArea(FlowContext ctx, FlowNode node, Particle particle) {
        Location min = location(ctx, node, "min_location");
        Location max = location(ctx, node, "max_location");
        requireSameWorld(min, max);
        int density = boundedCount(integer(ctx, node, "density", 10), 1, "density");
        double stepX = Math.max(0.5, Math.abs(max.getX() - min.getX()) / density);
        double stepY = Math.max(0.5, Math.abs(max.getY() - min.getY()) / density);
        double stepZ = Math.max(0.5, Math.abs(max.getZ() - min.getZ()) / density);
        double minX = Math.min(min.getX(), max.getX());
        double maxX = Math.max(min.getX(), max.getX());
        double minY = Math.min(min.getY(), max.getY());
        double maxY = Math.max(min.getY(), max.getY());
        double minZ = Math.min(min.getZ(), max.getZ());
        double maxZ = Math.max(min.getZ(), max.getZ());
        requireParticleBudget(axisSamples(minX, maxX, stepX), axisSamples(minY, maxY, stepY), axisSamples(minZ, maxZ, stepZ));
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
        requireSameWorld(start, end);
        int density = boundedCount(integer(ctx, node, "density", 10), 1, "density");
        requireParticleBudget(density + 1L);
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
        requireLocation(center);
        double radius = nonNegative(decimal(ctx, node, "radius", 3.0), "Particle circle radius");
        int count = boundedCount(integer(ctx, node, "count", integer(ctx, node, "points", 30)), 1, "count");
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
        requireLocation(center);
        double radius = nonNegative(decimal(ctx, node, "radius", 3.0), "Particle ring radius");
        int count = boundedCount(integer(ctx, node, "count", 30), 1, "count");
        String axis = text(ctx, node, "axis", "y").toLowerCase(Locale.ROOT);
        if (!axis.equals("x") && !axis.equals("y") && !axis.equals("z")) {
            throw new IllegalArgumentException("Particle ring axis must be x, y, or z");
        }
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
                    case "y" -> {
                        x = center.getX() + radius * Math.cos(angle);
                        y = center.getY();
                        z = center.getZ() + radius * Math.sin(angle);
                    }
                    default -> throw new IllegalStateException("Unexpected particle ring axis: " + axis);
                }
                center.getWorld().spawnParticle(particle, new Location(center.getWorld(), x, y, z), 1, 0, 0, 0, 0);
            }
        });
    }

    private void spawnSphere(FlowContext ctx, FlowNode node, Particle particle) {
        Location center = location(ctx, node, "location");
        requireLocation(center);
        double radius = nonNegative(decimal(ctx, node, "radius", 3.0), "Particle sphere radius");
        int count = boundedCount(integer(ctx, node, "count", integer(ctx, node, "points", 50)), 2, "count");
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
        requireLocation(center);
        double radiusX = decimal(ctx, node, "radius_x", 5.0);
        double radiusZ = decimal(ctx, node, "radius_z", 3.0);
        nonNegative(radiusX, "Particle ellipse X radius");
        nonNegative(radiusZ, "Particle ellipse Z radius");
        int count = boundedCount(integer(ctx, node, "count", 30), 1, "count");
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
        requireLocation(center);
        double radius = decimal(ctx, node, "radius", 3.0);
        double height = decimal(ctx, node, "height", 5.0);
        double rotations = decimal(ctx, node, "rotations", 3.0);
        nonNegative(radius, "Particle spiral radius");
        nonNegative(height, "Particle spiral height");
        if (rotations <= 0.0 || rotations > 100.0) throw new IllegalArgumentException("Particle spiral rotations must be between 0 and 100");
        int count = boundedCount(integer(ctx, node, "count", 100), 1, "count");
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
        requireLocation(center);
        double radius = decimal(ctx, node, "radius", 3.0);
        double height = decimal(ctx, node, "height", 5.0);
        nonNegative(radius, "Particle cone radius");
        nonNegative(height, "Particle cone height");
        int count = boundedCount(integer(ctx, node, "count", 50), 1, "count");
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
        requireSameWorld(min, max);
        boolean filled = bool(ctx, node, "filled", bool(ctx, node, "is_filled", false));
        double step = decimal(ctx, node, "step", filled ? 0.5 : 1.0);
        if (step < 0.1) throw new IllegalArgumentException("Particle cube step must be at least 0.1");
        double minX = Math.min(min.getX(), max.getX());
        double maxX = Math.max(min.getX(), max.getX());
        double minY = Math.min(min.getY(), max.getY());
        double maxY = Math.max(min.getY(), max.getY());
        double minZ = Math.min(min.getZ(), max.getZ());
        double maxZ = Math.max(min.getZ(), max.getZ());
        requireParticleBudget(axisSamples(minX, maxX, step), axisSamples(minY, maxY, step), axisSamples(minZ, maxZ, step));
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
        requireLocation(start);
        Vector direction = ctx.getInputValue(node, "direction", Vector.class, new Vector(1, 0, 0));
        double amplitude = decimal(ctx, node, "amplitude", 1.0);
        double frequency = decimal(ctx, node, "frequency", 0.5);
        double length = decimal(ctx, node, "length", 10.0);
        nonNegative(length, "Particle wave length");
        if (Math.abs(amplitude) > 1024.0) throw new IllegalArgumentException("Particle wave amplitude cannot exceed 1024");
        if (Math.abs(frequency) > 1000.0) throw new IllegalArgumentException("Particle wave frequency cannot exceed 1000");
        requireDirection(direction, "Particle wave direction");
        int steps = boundedCount((int) Math.ceil(length * 10.0), 1, "steps");
        requireParticleBudget(steps + 1L);
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
        String text = requireText(text(ctx, node, "text", "A"));
        double size = decimal(ctx, node, "size", 0.3);
        requireLocation(location);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Particle text is required");
        }
        if (size <= 0.0 || size > 10.0) throw new IllegalArgumentException("Particle text size must be between 0 and 10");
        run(location, () -> {
            Font font = new Font("Arial", Font.BOLD, 100);
            BufferedImage measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D measureGraphics = measure.createGraphics();
            measureGraphics.setFont(font);
            FontMetrics fm = measureGraphics.getFontMetrics();
            int textWidth = fm.stringWidth(text);
            int textHeight = fm.getHeight();
            if (textWidth + 24 > MAX_TEXT_IMAGE_WIDTH || textHeight + 24 > MAX_TEXT_IMAGE_HEIGHT) {
                measureGraphics.dispose();
                throw new IllegalArgumentException("Particle text exceeds the render bounds");
            }
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
                throw new IllegalArgumentException("Particle text did not produce renderable pixels");
            }
            double centerPixelX = (minPixelX + maxPixelX) / 2.0;
            double centerPixelY = (minPixelY + maxPixelY) / 2.0;
            double particleScale = size / 10.0;
            emitTextParticles(location, particle, image, imageWidth, imageHeight, centerPixelX, centerPixelY, particleScale);
        });
    }

    private String requireText(String text) {
        String value = text != null ? text : "";
        if (value.length() > MAX_TEXT_LENGTH) throw new IllegalArgumentException("Particle text cannot exceed " + MAX_TEXT_LENGTH + " characters");
        return value;
    }

    private void emitTextParticles(Location location, Particle particle, BufferedImage image, int imageWidth, int imageHeight, double centerPixelX, double centerPixelY, double particleScale) {
        int opaquePixels = 0;
        for (int y = 0; y < imageHeight; y += 2) {
            for (int x = 0; x < imageWidth; x += 2) {
                if ((image.getRGB(x, y) & 0xFF000000) != 0) opaquePixels++;
            }
        }
        double interval = opaquePixels > MAX_TEXT_PARTICLES ? opaquePixels / (double) MAX_TEXT_PARTICLES : 1.0;
        double nextEmission = 0.0;
        int visited = 0;
        int emitted = 0;
        for (int y = 0; y < imageHeight; y += 2) {
            for (int x = 0; x < imageWidth; x += 2) {
                int rgb = image.getRGB(x, y);
                if ((rgb & 0xFF000000) != 0) {
                    if (emitted < MAX_TEXT_PARTICLES && visited >= Math.floor(nextEmission)) {
                        Location loc = location.clone().add((x - centerPixelX) * particleScale, (centerPixelY - y) * particleScale, 0);
                        location.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                        emitted++;
                        nextEmission += interval;
                    }
                    visited++;
                }
            }
        }
    }

    private void spawnBlockDust(FlowContext ctx, FlowNode node) {
        Location location = location(ctx, node, "location");
        requireLocation(location);
        Material blockType = Material.getMaterial(text(ctx, node, "block_type", "STONE").toUpperCase(Locale.ROOT));
        if (blockType == null || !blockType.isBlock()) {
            throw new IllegalArgumentException("Particle block material is invalid");
        }
        int count = boundedCount(integer(ctx, node, "count", 10), 0, "count");
        BlockData blockData = blockType.createBlockData();
        run(location, () -> location.getWorld().spawnParticle(Particle.BLOCK, location, count, blockData));
    }

    private void spawnItemBreak(FlowContext ctx, FlowNode node) {
        Location location = location(ctx, node, "location");
        requireLocation(location);
        Material itemType = Material.getMaterial(text(ctx, node, "item_type", "STONE").toUpperCase(Locale.ROOT));
        if (itemType == null || !itemType.isItem()) {
            throw new IllegalArgumentException("Particle item material is invalid");
        }
        run(location, () -> location.getWorld().spawnParticle(Particle.ITEM, location, 1, 0, 0, 0, 0, new ItemStack(itemType)));
    }

    private void spawnExplosion(FlowContext ctx, FlowNode node) {
        Location location = location(ctx, node, "location");
        requireLocation(location);
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
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Particle is required");
        }
        String value = name.trim();
        if (value.contains(":")) {
            String key = value.toLowerCase(Locale.ROOT);
            for (Particle particle : Registry.PARTICLE_TYPE) {
                if (particle.getKey().toString().equalsIgnoreCase(key)) {
                    return particle;
                }
            }
            value = value.substring(value.indexOf(':') + 1);
        }
        value = value.replace('.', '_').replace('-', '_');
        try {
            return Particle.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown particle: " + name, exception);
        }
    }

    private void run(Location location, Runnable task) {
        requireLocation(location);
        if (task == null) {
            throw new IllegalArgumentException("Particle task is required");
        }
        task.run();
    }

    private boolean valid(Location location) {
        return location != null && location.getWorld() != null;
    }

    private int boundedCount(int value, int minimum, String name) {
        if (value < minimum || value > MAX_PARTICLES_PER_ACTION) {
            throw new IllegalArgumentException("Particle " + name + " must be between " + minimum + " and " + MAX_PARTICLES_PER_ACTION);
        }
        return value;
    }

    private double nonNegative(double value, String name) {
        if (value < 0.0 || value > 1024.0) throw new IllegalArgumentException(name + " must be between 0 and 1024");
        return value;
    }

    private void requireParticleMotion(double offsetX, double offsetY, double offsetZ, double speed) {
        if (Math.abs(offsetX) > 1024.0 || Math.abs(offsetY) > 1024.0 || Math.abs(offsetZ) > 1024.0) {
            throw new IllegalArgumentException("Particle offsets cannot exceed 1024");
        }
        if (speed < 0.0 || speed > 100.0) throw new IllegalArgumentException("Particle speed must be between 0 and 100");
    }

    private void requireDirection(Vector direction, String name) {
        if (direction == null || !Double.isFinite(direction.getX()) || !Double.isFinite(direction.getY()) || !Double.isFinite(direction.getZ())
            || direction.lengthSquared() == 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-zero");
        }
    }

    private long axisSamples(double minimum, double maximum, double step) {
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || !Double.isFinite(step) || step <= 0.0) {
            throw new IllegalArgumentException("Particle area coordinates and step must be finite");
        }
        double samples = Math.floor((maximum - minimum) / step) + 1.0;
        if (!Double.isFinite(samples) || samples > MAX_PARTICLES_PER_ACTION) {
            throw new IllegalArgumentException("Particle area exceeds the action budget");
        }
        return Math.max(1L, (long) samples);
    }

    private void requireParticleBudget(long... dimensions) {
        long total = 1L;
        try {
            for (long dimension : dimensions) {
                total = Math.multiplyExact(total, dimension);
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Particle action budget overflow", exception);
        }
        if (total > MAX_PARTICLES_PER_ACTION) {
            throw new IllegalArgumentException("Particle action exceeds the " + MAX_PARTICLES_PER_ACTION + " particle limit");
        }
    }

    private void requireLocation(Location location) {
        if (!valid(location)) {
            throw new IllegalArgumentException("World location is required");
        }
    }

    private void requireSameWorld(Location first, Location second) {
        requireLocation(first);
        requireLocation(second);
        if (!first.getWorld().equals(second.getWorld())) {
            throw new IllegalArgumentException("Particle locations must be in the same world");
        }
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
            double numeric = number.doubleValue();
            if (!Double.isFinite(numeric) || numeric != Math.rint(numeric) || numeric < Integer.MIN_VALUE || numeric > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Particle input " + pin + " must be a whole number");
            }
            return (int) numeric;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                double numeric = Double.parseDouble(text);
                if (!Double.isFinite(numeric) || numeric != Math.rint(numeric) || numeric < Integer.MIN_VALUE || numeric > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("Particle input " + pin + " must be a whole number");
                }
                return (int) numeric;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Particle input " + pin + " must be a number", exception);
            }
        }
        if (value != null) {
            throw new IllegalArgumentException("Particle input " + pin + " must be a number");
        }
        return fallback;
    }

    private double decimal(FlowContext ctx, FlowNode node, String pin, double fallback) {
        Object value = ctx.getInputValue(node, pin);
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (!Double.isFinite(numeric)) {
                throw new IllegalArgumentException("Particle input " + pin + " must be finite");
            }
            return numeric;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                double numeric = Double.parseDouble(text);
                if (!Double.isFinite(numeric)) {
                    throw new IllegalArgumentException("Particle input " + pin + " must be finite");
                }
                return numeric;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Particle input " + pin + " must be a number", exception);
            }
        }
        if (value != null) {
            throw new IllegalArgumentException("Particle input " + pin + " must be a number");
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
        executeInline(ctx, node);
        ctx.triggerOutput("flow");
    }

    void executeInline(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op == null) {
            throw new IllegalArgumentException("Unknown particle operation: " + operation);
        }
        op.accept(ctx, node);
    }
}
