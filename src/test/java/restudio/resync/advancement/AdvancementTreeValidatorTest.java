package restudio.resync.advancement;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdvancementTreeValidatorTest {
    private final AdvancementTreeValidator validator = new AdvancementTreeValidator();

    @Test
    void acceptsOneRootAndNamedCriterion() {
        assertDoesNotThrow(() -> validator.validate(Map.of("main", JsonParser.parseString("""
            {"id":"main","enabled":true,"nodes":{"root":{"enabled":true,"parent":"","display":{"icon":"minecraft:stone","frame":"task"},"criteria":{"stone":{"trigger":"obtain_item"}}}}}
            """).getAsJsonObject())));
    }

    @Test
    void rejectsUppercaseTreeIdsWithMinecraftRule() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> validator.validate(Map.of("ReSync", JsonParser.parseString("""
                {"id":"ReSync","nodes":{"root":{"parent":"","display":{"icon":"minecraft:stone"}}}}
                """).getAsJsonObject())));
        assertEquals("Minecraft requires lowercase advancement tree IDs. Use a-z, 0-9, dots, dashes, or underscores. For example, resync.",
            failure.getMessage());
    }

    @Test
    void rejectsUppercaseNodeIdsWithMinecraftRule() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> validator.validate(Map.of("resync", JsonParser.parseString("""
                {"id":"resync","nodes":{"Root":{"parent":"","display":{"icon":"minecraft:stone"}}}}
                """).getAsJsonObject())));
        assertEquals("Minecraft requires lowercase advancement node IDs. Use a-z, 0-9, dots, dashes, or underscores. For example, resync.",
            failure.getMessage());
    }

    @Test
    void rejectsCycles() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(Map.of("main", JsonParser.parseString("""
            {"id":"main","enabled":true,"nodes":{"root":{"enabled":true,"parent":"","display":{"icon":"minecraft:stone"}},"a":{"enabled":true,"parent":"b","display":{"icon":"minecraft:stone"}},"b":{"enabled":true,"parent":"a","display":{"icon":"minecraft:stone"}}}}
            """).getAsJsonObject())));
    }

    @Test
    void publishesEveryDocumentedTrigger() {
        assertEquals(41, AdvancementTriggerDescriptors.IDS.size());
    }

    @Test
    void rejectsUnknownRequirementCriterion() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(Map.of("main", JsonParser.parseString("""
            {"id":"main","enabled":true,"nodes":{"root":{"enabled":true,"parent":"","display":{"icon":"minecraft:stone"},"criteria":{"stone":{"trigger":"obtain_item"}},"requirements":[["missing"]]}}}
            """).getAsJsonObject())));
    }

    @Test
    void rejectsResourceAndTreeIdMismatch() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(Map.of("main", JsonParser.parseString("""
            {"id":"other","enabled":true,"nodes":{"root":{"enabled":true,"parent":"","display":{"icon":"minecraft:stone"}}}}
            """).getAsJsonObject())));
    }

    @Test
    void rejectsAParentInADisabledTree() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(Map.of(
            "main", JsonParser.parseString("""
                {"id":"main","nodes":{"root":{"display":{"icon":"minecraft:stone"}},"child":{"parent":"other/root","display":{"icon":"minecraft:stone"}}}}
                """).getAsJsonObject(),
            "other", JsonParser.parseString("""
                {"id":"other","enabled":false,"nodes":{"root":{"display":{"icon":"minecraft:stone"}}}}
                """).getAsJsonObject())));
    }

    @Test
    void namespacedReSyncParentsUseTheSameInventoryAndCycleValidation() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(Map.of("main", JsonParser.parseString("""
            {"id":"main","nodes":{"root":{"display":{"icon":"minecraft:stone"}},"child":{"parent":"resync:missing/root","display":{"icon":"minecraft:stone"}}}}
            """).getAsJsonObject())));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(Map.of("main", JsonParser.parseString("""
            {"id":"main","nodes":{"root":{"display":{"icon":"minecraft:stone"}},"a":{"parent":"resync:main/b","display":{"icon":"minecraft:stone"}},"b":{"parent":"resync:main/a","display":{"icon":"minecraft:stone"}}}}
            """).getAsJsonObject())));
    }

    @Test
    void aConfiguredCriterionCannotBeAutoAwardedAsRootVisibility() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(Map.of("main", JsonParser.parseString("""
            {"id":"main","nodes":{"root":{"display":{"icon":"minecraft:stone"},"criteria":{"__resync_root":{"trigger":"held_item","conditions":{"heldItem":"minecraft:diamond"}}}}}}
            """).getAsJsonObject())));
    }
}
