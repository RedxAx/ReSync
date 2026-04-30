package restudio.resync.worldgen.pipeline;

import org.bukkit.generator.WorldInfo;

public record EvalContext(float x, float y, float z, long seed, WorldInfo worldInfo) {
}
