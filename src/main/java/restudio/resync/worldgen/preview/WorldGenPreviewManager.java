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
import restudio.resync.worldgen.datapack.WorldGenDatapackBuild;
import restudio.resync.worldgen.datapack.WorldGenDatapackCompiler;
import restudio.resync.worldgen.generator.NodeGraphBiomeProvider;
import restudio.resync.worldgen.generator.NodeGraphChunkGenerator;
import restudio.resync.worldgen.pipeline.PipelineCompiler;
import restudio.resync.worldgen.pipeline.TerrainPipeline;
import restudio.resync.worldgen.pipeline.TerrainPipelineHolder;
import restudio.resync.worldgen.runtime.WorldGenRuntimeRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class WorldGenPreviewManager {
    private final Plugin plugin;
    private final Map<String, PreviewWorld> activePreviews = new ConcurrentHashMap<>();
    private final Map<String, Long> activeRequests = new ConcurrentHashMap<>();
    private final AtomicLong previewRevision = new AtomicLong();
    private final WorldGenDatapackCompiler datapackCompiler;

    public WorldGenPreviewManager(Plugin plugin) {
        this.plugin = plugin;
        this.datapackCompiler = new WorldGenDatapackCompiler(plugin);
    }

    public void createPreview(String previewId, String playerUuid, WorldGenGraph graph, World.Environment environment, long seed, Consumer<PreviewWorld> onSuccess, Consumer<Throwable> onError) {
        TerrainPipeline pipeline = PipelineCompiler.compile(graph);
        createPreview(previewId, playerUuid, pipeline, environment, seed, onSuccess, onError);
    }

    public void createPreview(String previewId, String playerUuid, WorldGenProject project, World.Environment environment, long seed, Consumer<PreviewWorld> onSuccess, Consumer<Throwable> onError) {
        TerrainPipeline pipeline = PipelineCompiler.compileProject(project);
        createPreview(previewId, playerUuid, project, pipeline, environment, seed, onSuccess, onError);
    }

    private void createPreview(String previewId, String playerUuid, TerrainPipeline pipeline, World.Environment environment, long seed, Consumer<PreviewWorld> onSuccess, Consumer<Throwable> onError) {
        createPreview(previewId, playerUuid, null, pipeline, environment, seed, onSuccess, onError);
    }

    private void createPreview(String previewId, String playerUuid, WorldGenProject project, TerrainPipeline pipeline, World.Environment environment, long seed, Consumer<PreviewWorld> onSuccess, Consumer<Throwable> onError) {
        String normalizedPreviewId = normalizePreviewId(previewId);
        long revision = previewRevision.incrementAndGet();
        activeRequests.put(normalizedPreviewId, revision);
        PreviewWorld previous = activePreviews.get(normalizedPreviewId);
        String worldName = "resync_preview_" + normalizedPreviewId + "_" + revision;
        WorldGenDatapackBuild datapackBuild = project == null ? null : datapackCompiler.compile(project, datapackCompiler.generatedRoot(), revision);
        Bukkit.getScheduler().runTask(plugin, () -> {
            PreviewWorld previewWorld = null;
            try {
                Map<String, Location> previousLocations = capturePreviewPlayerLocations(previous);
                previewWorld = createWorldSync(playerUuid, pipeline, datapackBuild, worldName, environment, seed, previousLocations);
                if (!Long.valueOf(revision).equals(activeRequests.get(normalizedPreviewId))) {
                    deletePreviewWorld(previewWorld.worldName());
                    return;
                }
                PreviewWorld replaced = activePreviews.put(normalizedPreviewId, previewWorld);
                if (replaced != null && !replaced.worldName().equals(previewWorld.worldName())) {
                    deletePreviewWorld(replaced.worldName());
                }
                if (onSuccess != null) {
                    onSuccess.accept(previewWorld);
                }
            } catch (Throwable throwable) {
                if (previewWorld != null) {
                    deletePreviewWorld(previewWorld.worldName());
                } else {
                    deletePreviewWorld(worldName);
                }
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
        String normalizedPreviewId = normalizePreviewId(previewId);
        activeRequests.remove(normalizedPreviewId);
        PreviewWorld current = activePreviews.remove(normalizedPreviewId);
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

    public void cleanupOrphanedPreviews() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (World world : new ArrayList<>(Bukkit.getWorlds())) {
                if (isPreviewWorldName(world.getName())) {
                    deletePreviewWorld(world.getName());
                }
            }
            Path root = resolveWorldRoot();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try (var stream = Files.list(root)) {
                    stream
                        .filter(Files::isDirectory)
                        .filter(path -> isPreviewWorldName(path.getFileName().toString()))
                        .forEach(path -> deleteWorldFolder(root, path.toAbsolutePath().normalize()));
                } catch (IOException ignored) {
                }
            });
        });
    }

    public void stopAllPreviews() {
        activeRequests.clear();
        List<PreviewWorld> previews = new ArrayList<>(activePreviews.values());
        activePreviews.clear();
        Runnable cleanup = () -> previews.forEach(preview -> deletePreviewWorld(preview.worldName()));
        if (Bukkit.isPrimaryThread() || !plugin.isEnabled()) {
            cleanup.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, cleanup);
        }
    }

    private PreviewWorld createWorldSync(String playerUuid, TerrainPipeline pipeline, WorldGenDatapackBuild datapackBuild, String worldName, World.Environment environment, long seed, Map<String, Location> previousLocations) {
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
        configurePreviewWorld(world);
        WorldGenRuntimeRegistry.register(world, pipelineHolder);
        Player player = resolvePreviewPlayer(playerUuid);
        restorePreviewPlayers(world, previousLocations, player);
        if (player != null && !previousLocations.containsKey(player.getUniqueId().toString())) {
            player.teleport(world.getSpawnLocation());
        }
        return new PreviewWorld(worldName, player != null ? player.getUniqueId().toString() : playerUuid, pipelineHolder, world, datapackBuild);
    }

    private void configurePreviewWorld(World world) {
        world.setAutoSave(false);
        world.setKeepSpawnInMemory(false);
        world.setViewDistance(4);
        world.setSimulationDistance(4);
    }

    private String normalizePreviewId(String previewId) {
        String value = previewId == null || previewId.isBlank() ? "worldgen" : previewId.trim().toLowerCase(Locale.ROOT);
        value = value.replaceAll("[^a-z0-9_\\-]+", "_");
        return value.isBlank() ? "worldgen" : value;
    }

    private boolean isPreviewWorldName(String worldName) {
        return worldName != null && worldName.startsWith("resync_preview_");
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
            World fallbackWorld = findFallbackWorld(worldName);
            if (fallbackWorld != null) {
                Location fallbackLocation = fallbackWorld.getSpawnLocation();
                for (Player player : new ArrayList<>(world.getPlayers())) {
                    player.teleport(fallbackLocation);
                }
            }
            Bukkit.unloadWorld(world, true);
        }
    }

    private World findFallbackWorld(String excludedWorldName) {
        for (World world : Bukkit.getWorlds()) {
            if (!world.getName().equals(excludedWorldName) && !isPreviewWorldName(world.getName())) {
                return world;
            }
        }
        return null;
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

    public record PreviewWorld(String worldName, String creatorPlayerUuid, TerrainPipelineHolder pipelineHolder, World world, WorldGenDatapackBuild datapackBuild) {
    }
}
