package restudio.resync.worldgen.preview;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import restudio.resync.worldgen.data.WorldGenGraph;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.generator.NodeGraphBiomeProvider;
import restudio.resync.worldgen.generator.NodeGraphChunkGenerator;
import restudio.resync.worldgen.generator.WorldGenFeaturePopulator;
import restudio.resync.worldgen.pipeline.PipelineCompiler;
import restudio.resync.worldgen.pipeline.TerrainPipeline;
import restudio.resync.worldgen.pipeline.TerrainPipelineHolder;
import restudio.resync.worldgen.runtime.WorldGenRuntimeRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class WorldGenPreviewManager {
    private final Plugin plugin;
    private final Map<String, PreviewWorld> activePreviews = new ConcurrentHashMap<>();
    private final AtomicLong previewRevision = new AtomicLong();

    public WorldGenPreviewManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public void createPreview(String previewId, String playerUuid, WorldGenGraph graph, World.Environment environment, long seed, Consumer<PreviewWorld> onSuccess, Consumer<Throwable> onError) {
        TerrainPipeline pipeline = PipelineCompiler.compile(graph);
        createPreview(previewId, playerUuid, pipeline, environment, seed, onSuccess, onError);
    }

    public void createPreview(String previewId, String playerUuid, WorldGenProject project, World.Environment environment, long seed, Consumer<PreviewWorld> onSuccess, Consumer<Throwable> onError) {
        TerrainPipeline pipeline = PipelineCompiler.compileProject(project);
        createPreview(previewId, playerUuid, pipeline, environment, seed, onSuccess, onError);
    }

    private void createPreview(String previewId, String playerUuid, TerrainPipeline pipeline, World.Environment environment, long seed, Consumer<PreviewWorld> onSuccess, Consumer<Throwable> onError) {
        String normalizedPreviewId = normalizePreviewId(previewId);
        PreviewWorld previous = activePreviews.remove(normalizedPreviewId);
        String worldName = "resync_preview_" + normalizedPreviewId + "_" + previewRevision.incrementAndGet();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                Map<String, Location> previousLocations = capturePreviewPlayerLocations(previous);
                PreviewWorld previewWorld = createWorldSync(normalizedPreviewId, playerUuid, pipeline, worldName, environment, seed, previousLocations);
                if (previous != null) {
                    deletePreviewWorld(previous.worldName());
                }
                if (onSuccess != null) {
                    onSuccess.accept(previewWorld);
                }
            } catch (Throwable throwable) {
                if (onError != null) {
                    onError.accept(throwable);
                }
            }
        });
    }

    public void updatePreview(String previewId, WorldGenGraph graph, Consumer<PreviewWorld> onSuccess, Consumer<Throwable> onError) {
        PreviewWorld current = activePreviews.get(normalizePreviewId(previewId));
        if (current == null) throw new IllegalArgumentException("Preview Missing");
        createPreview(previewId, current.creatorPlayerUuid(), graph, current.world().getEnvironment(), current.world().getSeed(), onSuccess, onError);
    }

    public void stopPreview(String previewId, Runnable onComplete, Consumer<Throwable> onError) {
        PreviewWorld current = activePreviews.remove(normalizePreviewId(previewId));
        if (current == null) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                deletePreviewWorld(current.worldName());
                if (onComplete != null) {
                    onComplete.run();
                }
            } catch (Throwable throwable) {
                if (onError != null) {
                    onError.accept(throwable);
                }
            }
        });
    }

    private PreviewWorld createWorldSync(String previewId, String playerUuid, TerrainPipeline pipeline, String worldName, World.Environment environment, long seed, Map<String, Location> previousLocations) {
        TerrainPipelineHolder pipelineHolder = new TerrainPipelineHolder(pipeline);
        WorldCreator creator = new WorldCreator(worldName);
        creator.generator(new NodeGraphChunkGenerator(pipelineHolder));
        creator.biomeProvider(new NodeGraphBiomeProvider(pipelineHolder));
        creator.environment(environment);
        creator.seed(seed);
        creator.type(WorldType.NORMAL);
        creator.generateStructures(pipeline.hasAnyVanillaStructuresEnabled());
        World world = creator.createWorld();
        if (world == null) throw new IllegalStateException("Preview World Failed");
        WorldGenRuntimeRegistry.register(world, pipelineHolder);
        Player player = resolvePreviewPlayer(playerUuid);
        restorePreviewPlayers(world, previousLocations, player);
        if (player != null && !previousLocations.containsKey(player.getUniqueId().toString())) {
            player.teleport(world.getSpawnLocation());
        }
        schedulePreviewFeaturePass(world, pipelineHolder);
        PreviewWorld previewWorld = new PreviewWorld(worldName, player != null ? player.getUniqueId().toString() : playerUuid, pipelineHolder, world);
        activePreviews.put(previewId, previewWorld);
        return previewWorld;
    }

    private void placeLoadedPreviewFeatures(World world, TerrainPipelineHolder pipelineHolder) {
        if (world == null || pipelineHolder == null) {
            return;
        }
        WorldGenFeaturePopulator populator = new WorldGenFeaturePopulator(pipelineHolder);
        for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
            populator.placeFeatures(world, new java.util.Random(chunk.getChunkKey() ^ world.getSeed()), chunk);
        }
    }

    private void schedulePreviewFeaturePass(World world, TerrainPipelineHolder pipelineHolder) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> placeLoadedPreviewFeatures(world, pipelineHolder), 20L);
    }

    private String normalizePreviewId(String previewId) {
        return "worldgen";
    }

    private Map<String, Location> capturePreviewPlayerLocations(PreviewWorld previous) {
        Map<String, Location> locations = new ConcurrentHashMap<>();
        if (previous == null || previous.world() == null) {
            return locations;
        }
        for (Player player : previous.world().getPlayers()) {
            locations.put(player.getUniqueId().toString(), player.getLocation().clone());
        }
        return locations;
    }

    private void restorePreviewPlayers(World world, Map<String, Location> previousLocations, Player fallbackPlayer) {
        for (Map.Entry<String, Location> entry : previousLocations.entrySet()) {
            try {
                Player player = Bukkit.getPlayer(UUID.fromString(entry.getKey()));
                if (player == null) {
                    continue;
                }
                Location previousLocation = entry.getValue();
                Location target = new Location(world, previousLocation.getX(), previousLocation.getY(), previousLocation.getZ(), previousLocation.getYaw(), previousLocation.getPitch());
                player.teleport(target);
            } catch (Exception ignored) {
            }
        }
        if (previousLocations.isEmpty() && fallbackPlayer != null) {
            fallbackPlayer.teleport(world.getSpawnLocation());
        }
    }

    private Player resolvePreviewPlayer(String playerUuid) {
        try {
            if (playerUuid != null && !playerUuid.isBlank()) {
                Player player = Bukkit.getPlayer(UUID.fromString(playerUuid));
                if (player != null) {
                    return player;
                }
            }
        } catch (Exception ignored) {
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            return player;
        }
        return null;
    }

    private void unloadWorldSync(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            Bukkit.unloadWorld(world, true);
        }
    }

    private void deletePreviewWorld(String worldName) {
        unloadWorldSync(worldName);
        Path root = resolveWorldRoot();
        Path folder = resolveWorldFolder(root, worldName);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> deleteWorldFolder(root, folder));
    }

    private Path resolveWorldRoot() {
        return Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
    }

    private Path resolveWorldFolder(Path root, String worldName) {
        return root.resolve(worldName).normalize();
    }

    private void deleteWorldFolder(Path root, Path folder) {
        if (!folder.startsWith(root) || !Files.exists(folder)) return;
        try {
            Files.walk(folder).sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    public record PreviewWorld(String worldName, String creatorPlayerUuid, TerrainPipelineHolder pipelineHolder, World world) {
    }
}
