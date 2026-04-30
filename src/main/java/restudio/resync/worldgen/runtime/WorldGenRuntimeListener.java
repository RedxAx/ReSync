package restudio.resync.worldgen.runtime;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;
import restudio.resync.worldgen.data.WorldGenSpawnRule;
import restudio.resync.worldgen.pipeline.TerrainPipeline;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class WorldGenRuntimeListener implements Listener {
    private static final int MAX_WORLDGEN_ENTITY_TOTAL = 96;
    private static final int MAX_WORLDGEN_ENTITY_NEARBY = 10;
    private static final long SPAWN_COOLDOWN_MILLIS = 1500L;
    private final Plugin plugin;
    private final Map<String, Long> spawnCooldowns = new ConcurrentHashMap<>();

    public WorldGenRuntimeListener(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        WorldGenRuntimeRegistry.unregister(event.getWorld().getName());
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        TerrainPipeline pipeline = WorldGenRuntimeRegistry.get(event.getLocation().getWorld());
        if (pipeline == null || event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }
        Biome biome = event.getLocation().getBlock().getBiome();
        if (pipeline.isVanillaSpawnsEnabled(biome)) {
            return;
        }
        event.setCancelled(true);
        if (pipeline.getSpawnRules().isEmpty() || !canAttemptSpawn(event.getLocation())) {
            return;
        }
        WorldGenSpawnRule rule = selectRule(pipeline.getSpawnRules(), biome, event.getLocation());
        if (rule == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> spawnRule(pipeline, rule, event.getLocation()));
    }

    private boolean canAttemptSpawn(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        String key = location.getWorld().getName() + ":" + (location.getBlockX() >> 4) + ":" + (location.getBlockZ() >> 4);
        long now = System.currentTimeMillis();
        Long previous = spawnCooldowns.put(key, now);
        return previous == null || now - previous >= SPAWN_COOLDOWN_MILLIS;
    }

    private WorldGenSpawnRule selectRule(List<WorldGenSpawnRule> rules, Biome biome, Location location) {
        if (rules == null || rules.isEmpty() || location == null) {
            return null;
        }
        int total = 0;
        for (WorldGenSpawnRule rule : rules) {
            if (matches(rule, biome, location)) {
                total += Math.max(1, rule.getWeight());
            }
        }
        if (total <= 0) {
            return null;
        }
        int pick = ThreadLocalRandom.current().nextInt(total);
        for (WorldGenSpawnRule rule : rules) {
            if (!matches(rule, biome, location)) {
                continue;
            }
            pick -= Math.max(1, rule.getWeight());
            if (pick < 0) {
                return rule;
            }
        }
        return null;
    }

    private boolean matches(WorldGenSpawnRule rule, Biome biome, Location location) {
        if (rule == null || location == null || location.getY() < rule.getMinY() || location.getY() > rule.getMaxY()) {
            return false;
        }
        if (location.getBlock().getLightLevel() < rule.getMinLight() || location.getBlock().getLightLevel() > rule.getMaxLight()) {
            return false;
        }
        if (rule.getBiomeFilters() == null || rule.getBiomeFilters().isEmpty()) {
            return true;
        }
        String biomeId = "minecraft:" + biome.name().toLowerCase(Locale.ROOT);
        return rule.getBiomeFilters().stream().anyMatch(filter -> filter != null && filter.equalsIgnoreCase(biomeId));
    }

    private void spawnRule(TerrainPipeline pipeline, WorldGenSpawnRule rule, Location location) {
        EntityType type = pipeline.entityType(rule.getEntityType(), EntityType.ZOMBIE);
        if (!canSpawnMore(location, type)) {
            return;
        }
        int minGroup = Math.max(1, Math.min(4, rule.getMinGroup()));
        int maxGroup = Math.max(minGroup, Math.min(4, rule.getMaxGroup()));
        int count = ThreadLocalRandom.current().nextInt(minGroup, maxGroup + 1);
        for (int i = 0; i < count; i++) {
            if (!canSpawnMore(location, type)) {
                return;
            }
            Location spawnLocation = location.clone().add(ThreadLocalRandom.current().nextDouble(-2, 2), 0, ThreadLocalRandom.current().nextDouble(-2, 2));
            if (spawnLocation.getWorld() != null && type.isSpawnable()) {
                spawnLocation.getWorld().spawnEntity(spawnLocation, type);
            }
        }
    }

    private boolean canSpawnMore(Location location, EntityType type) {
        if (location == null || location.getWorld() == null || type == null) {
            return false;
        }
        World world = location.getWorld();
        long total = world.getLivingEntities().stream().filter(entity -> entity.getType() == type).count();
        if (total >= MAX_WORLDGEN_ENTITY_TOTAL) {
            return false;
        }
        long nearby = world.getNearbyEntities(location, 32, 16, 32).stream()
            .filter(LivingEntity.class::isInstance)
            .filter(entity -> entity.getType() == type)
            .count();
        return nearby < MAX_WORLDGEN_ENTITY_NEARBY;
    }
}
