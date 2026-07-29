package restudio.resync.worldgen.pipeline;

public record StructureTerrainPolicy(boolean enabled, int sampleRadius, int maxHeightDelta) {
    public static final StructureTerrainPolicy DEFAULT = new StructureTerrainPolicy(true, 48, 12);

    public StructureTerrainPolicy {
        sampleRadius = Math.clamp(sampleRadius, 0, 128);
        maxHeightDelta = Math.max(0, maxHeightDelta);
    }
}
