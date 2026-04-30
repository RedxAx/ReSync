package restudio.resync.worldgen.runtime;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Biome;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;
import restudio.resync.worldgen.data.WorldGenSpawnRule;
import restudio.resync.worldgen.generator.WorldGenFeaturePopulator;
import restudio.resync.worldgen.pipeline.TerrainPipeline;
import restudio.resync.worldgen.pipeline.TerrainPipelineHolder;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class WorldGenRuntimeListener implements Listener {
    private final Plugin plugin;

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
    public void onChunkLoad(ChunkLoadEvent event) {
        TerrainPipelineHolder holder = WorldGenRuntimeRegistry.holder(event.getWorld());
        if (holder == null || holder.get() == null || !event.isNewChunk()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> new WorldGenFeaturePopulator(holder).placeFeatures(event.getWorld(), ThreadLocalRandom.current(), event.getChunk()));
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
        WorldGenSpawnRule rule = selectRule(pipeline.getSpawnRules(), biome, event.getLocation());
        if (rule == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> spawnRule(pipeline, rule, event.getLocation()));
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
        String biomeId = "minecraft:" + biome.name().toLowerCase(java.util.Locale.ROOT);
        return rule.getBiomeFilters().stream().anyMatch(filter -> filter != null && filter.equalsIgnoreCase(biomeId));
    }

    private void spawnRule(TerrainPipeline pipeline, WorldGenSpawnRule rule, Location location) {
        EntityType type = pipeline.entityType(rule.getEntityType(), EntityType.ZOMBIE);
        int count = ThreadLocalRandom.current().nextInt(Math.max(1, rule.getMinGroup()), Math.max(Math.max(1, rule.getMinGroup()), rule.getMaxGroup()) + 1);
        for (int i = 0; i < count; i++) {
            Location spawnLocation = location.clone().add(ThreadLocalRandom.current().nextDouble(-2, 2), 0, ThreadLocalRandom.current().nextDouble(-2, 2));
            if (spawnLocation.getWorld() != null && type.isSpawnable()) {
                spawnLocation.getWorld().spawnEntity(spawnLocation, type);
            }
        }
    }
}
