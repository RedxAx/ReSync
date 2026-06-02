package restudio.resync.advancement;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancementDisplayJsonTest {
    @Test
    void normalizesLegacyBackgroundTexturePath() {
        assertEquals(
            "minecraft:gui/advancements/backgrounds/adventure",
            AdvancementDisplayJson.background("minecraft:textures/gui/advancements/backgrounds/adventure.png")
        );
    }

    @Test
    void wrapsPlainTitleInTextComponent() {
        var component = AdvancementDisplayJson.textComponent(JsonParser.parseString("\"Hello\""));
        assertTrue(component.isJsonObject());
        assertEquals("Hello", component.getAsJsonObject().get("text").getAsString());
    }
}
