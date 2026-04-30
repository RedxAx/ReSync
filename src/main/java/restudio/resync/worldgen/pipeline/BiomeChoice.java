package restudio.resync.worldgen.pipeline;

public record BiomeChoice(String biomeId, boolean keepVanillaFeatures, boolean keepVanillaStructures, boolean keepVanillaSpawns) {
}
