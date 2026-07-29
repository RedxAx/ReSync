package restudio.resync.worldgen.pipeline;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureTerrainPolicyTest {
    @Test
    void rejectsStructureStartsAcrossUnsafeHeightDifferences() {
        PipelineNode height = (context, upstreams) -> context.x() < 0 ? 64f : 96f;
        TerrainPipeline pipeline = pipeline(height, new StructureTerrainPolicy(true, 48, 12));

        assertFalse(pipeline.isVanillaStructureTerrainSuitable(0, 0, 0, null));
    }

    @Test
    void acceptsFlatTerrainAndDisabledSafety() {
        PipelineNode flatHeight = (context, upstreams) -> 72f;
        PipelineNode steepHeight = (context, upstreams) -> context.z() < 0 ? 40f : 100f;

        assertTrue(pipeline(flatHeight, new StructureTerrainPolicy(true, 48, 12))
            .isVanillaStructureTerrainSuitable(0, 0, 0, null));
        assertTrue(pipeline(steepHeight, new StructureTerrainPolicy(false, 48, 12))
            .isVanillaStructureTerrainSuitable(0, 0, 0, null));
    }

    private TerrainPipeline pipeline(PipelineNode height, StructureTerrainPolicy policy) {
        return new TerrainPipeline(Map.of("height", height), Map.of(), List.of(height), height, null, null, null, null, null, null, null,
            null, null, null, null, null, null, new CompiledBiomePolicy(false, true, false, Map.of(), Map.of(), Map.of()), 63,
            Material.STONE, Material.WATER, List.of(), policy);
    }
}
