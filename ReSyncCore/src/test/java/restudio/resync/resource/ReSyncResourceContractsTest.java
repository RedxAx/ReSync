package restudio.resync.resource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReSyncResourceContractsTest {
    @Test
    void resourceKeyNormalizesTypeAndKeepsTypedIdentity() {
        ReSyncResourceKey flow = new ReSyncResourceKey(" Flow ", "shared");
        ReSyncResourceKey function = new ReSyncResourceKey("function", "shared");

        assertEquals("flow", flow.type());
        assertEquals("shared", flow.id());
        assertThrows(IllegalArgumentException.class, () -> new ReSyncResourceKey(" ", "shared"));
        assertNotEquals(flow, function);
    }

    @Test
    void recipeKindsAreSharedAcrossClientsAndRuntime() {
        assertEquals(RecipeSchema.Kind.SHAPED, RecipeSchema.kind("shaped"));
        assertEquals(RecipeSchema.Kind.LIST, RecipeSchema.kind("campfire_cooking"));
        assertEquals(RecipeSchema.Kind.SMITHING, RecipeSchema.kind("smithing_transform"));
        assertEquals(RecipeSchema.Kind.UNKNOWN, RecipeSchema.kind("custom"));
    }
}
