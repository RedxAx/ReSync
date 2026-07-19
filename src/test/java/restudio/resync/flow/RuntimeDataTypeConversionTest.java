package restudio.resync.flow;

import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeDataTypeConversionTest {
    @Test
    void runtimeDataIdsConvertToTheirExactFlowRuntimeTypes() {
        TypeAdapterRegistry adapters = new TypeAdapterRegistry();

        assertEquals(Material.DIAMOND_SWORD, adapters.adapt("minecraft:diamond_sword", Material.class));
        assertEquals(EntityType.ZOMBIE, adapters.adapt("zombie", EntityType.class));
        assertEquals(GameMode.ADVENTURE, adapters.adapt("adventure", GameMode.class));
        assertEquals(Difficulty.HARD, adapters.adapt("hard", Difficulty.class));
    }
}
