package restudio.resync.worldgen.runtime;

import org.bukkit.World;
import restudio.resync.worldgen.pipeline.TerrainPipeline;
import restudio.resync.worldgen.pipeline.TerrainPipelineHolder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class WorldGenRuntimeRegistry {
    private static final Map<String, TerrainPipelineHolder> PIPELINES = new ConcurrentHashMap<>();

    private WorldGenRuntimeRegistry() {
    }

    public static void register(World world, TerrainPipeline pipeline) {
        if (world != null && pipeline != null) {
            PIPELINES.put(world.getName(), new TerrainPipelineHolder(pipeline));
        }
    }

    public static void register(World world, TerrainPipelineHolder pipelineHolder) {
        if (world != null && pipelineHolder != null) {
            PIPELINES.put(world.getName(), pipelineHolder);
        }
    }

    public static void unregister(String worldName) {
        if (worldName != null) {
            PIPELINES.remove(worldName);
        }
    }

    public static TerrainPipeline get(World world) {
        TerrainPipelineHolder holder = world == null ? null : PIPELINES.get(world.getName());
        return holder == null ? null : holder.get();
    }

    public static TerrainPipelineHolder holder(World world) {
        return world == null ? null : PIPELINES.get(world.getName());
    }
}
