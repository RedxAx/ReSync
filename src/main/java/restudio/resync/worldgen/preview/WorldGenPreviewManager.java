package restudio.resync.worldgen.preview;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import restudio.resync.worldgen.data.WorldGenGraph;
import restudio.resync.worldgen.generator.NodeGraphBiomeProvider;
import restudio.resync.worldgen.generator.NodeGraphChunkGenerator;
import restudio.resync.worldgen.pipeline.PipelineCompiler;
import restudio.resync.worldgen.pipeline.TerrainPipeline;

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
        PreviewWorld previous = activePreviews.remove(previewId);
        String worldName = "resync_preview_" + previewId.replaceAll("[^A-Za-z0-9_-]", "_") + "_" + previewRevision.incrementAndGet();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (previous != null) {
                    unloadWorldSync(previous.worldName());
                    deleteWorldFolderLater(previous.worldName(), 200L);
                }
                PreviewWorld previewWorld = createWorldSync(previewId, playerUuid, pipeline, worldName, environment, seed);
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
        PreviewWorld current = activePreviews.get(previewId);
        if (current == null) throw new IllegalArgumentException("Preview Missing");
        createPreview(previewId, current.creatorPlayerUuid(), graph, current.world().getEnvironment(), current.world().getSeed(), onSuccess, onError);
    }

    public void stopPreview(String previewId, Runnable onComplete, Consumer<Throwable> onError) {
        PreviewWorld current = activePreviews.remove(previewId);
        if (current == null) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                unloadWorldSync(current.worldName());
                deleteWorldFolderLater(current.worldName(), 200L);
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

    private PreviewWorld createWorldSync(String previewId, String playerUuid, TerrainPipeline pipeline, String worldName, World.Environment environment, long seed) {
        WorldCreator creator = new WorldCreator(worldName);
        creator.generator(new NodeGraphChunkGenerator(pipeline));
        creator.biomeProvider(new NodeGraphBiomeProvider(pipeline));
        creator.environment(environment);
        creator.seed(seed);
        creator.type(WorldType.NORMAL);
        World world = creator.createWorld();
        if (world == null) throw new IllegalStateException("Preview World Failed");
        Player player = resolvePreviewPlayer(playerUuid);
        if (player != null) {
            player.teleport(world.getSpawnLocation());
        }
        PreviewWorld previewWorld = new PreviewWorld(worldName, player != null ? player.getUniqueId().toString() : playerUuid, pipeline, world);
        activePreviews.put(previewId, previewWorld);
        return previewWorld;
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

    private void deleteWorldFolderLater(String worldName, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Path root = resolveWorldRoot();
            Path folder = resolveWorldFolder(root, worldName);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> deleteWorldFolder(root, folder));
        }, delayTicks);
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

    public record PreviewWorld(String worldName, String creatorPlayerUuid, TerrainPipeline pipeline, World world) {
    }
}
