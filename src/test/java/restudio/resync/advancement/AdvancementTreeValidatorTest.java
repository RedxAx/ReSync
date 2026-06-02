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
}
