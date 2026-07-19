package restudio.resync.flow.validation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgrammabilityCompatibilityFixtureTest {
    @Test
    void manifestOwnsParseableFixturesWithStableResourceIdentities() throws Exception {
        Path root = Path.of("src", "test", "resources", "fixtures", "programmability");
        JsonObject manifest = JsonParser.parseString(Files.readString(root.resolve("manifest.json"))).getAsJsonObject();
        Set<String> resourceTypes = new HashSet<>();

        for (JsonElement element : manifest.getAsJsonArray("fixtures")) {
            JsonObject fixture = element.getAsJsonObject();
            Path file = root.resolve(fixture.get("file").getAsString());
            JsonObject resource = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            assertTrue(resource.has("id") && !resource.get("id").getAsString().isBlank());
            assertTrue(fixture.has("requirements") && !fixture.getAsJsonArray("requirements").isEmpty());
            resourceTypes.add(fixture.get("resourceType").getAsString());
        }

        assertEquals(Set.of("flow", "loot_table", "trade_profile", "npc_definition"), resourceTypes);
    }
}
