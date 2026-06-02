package restudio.resync.advancement;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AdvancementIconJsonTest {
    @Test
    void fromReferenceAddsMinecraftNamespace() {
        JsonObject icon = AdvancementIconJson.fromReference("stone");
        assertEquals("minecraft:stone", icon.get("id").getAsString());
        assertFalse(icon.has("components"));
    }

    @Test
    void fromReferencePreservesNamespacedId() {
        JsonObject icon = AdvancementIconJson.fromReference("minecraft:nether_star");
        assertEquals("minecraft:nether_star", icon.get("id").getAsString());
    }
}
