package restudio.resync.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class StructureNodeCatalogTest {
    @Test
    void keepsCreationFreeformAndMakesDeleteAnExactCatalogReference() {
        JsonArray nodes;
        try (InputStreamReader reader = new InputStreamReader(
            StructureNodeCatalogTest.class.getResourceAsStream("/nodes/structure.json"), StandardCharsets.UTF_8)) {
            nodes = JsonParser.parseReader(reader).getAsJsonArray();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }

        JsonObject save = node(nodes, "structure_save");
        JsonObject saveId = pin(save, "structure_id");
        assertEquals("string", saveId.get("dataType").getAsString());
        assertFalse(saveId.has("optionsSource"));

        JsonObject delete = node(nodes, "structure_delete");
        JsonObject deleteTarget = pin(delete, "structure_id");
        assertEquals("typed-reference", delete.get("selectorIntent").getAsString());
        assertEquals("resource_reference<restudio.resync:structure>", deleteTarget.get("dataType").getAsString());
        assertEquals("SEARCHABLE_LIST", deleteTarget.get("widget").getAsString());
        assertEquals("server:resync:structure", deleteTarget.get("optionsSource").getAsString());
    }

    private JsonObject node(JsonArray nodes, String id) {
        for (var value : nodes) {
            JsonObject node = value.getAsJsonObject();
            if (id.equals(node.get("id").getAsString())) {
                return node;
            }
        }
        throw new AssertionError("Missing node: " + id);
    }

    private JsonObject pin(JsonObject node, String id) {
        for (var value : node.getAsJsonArray("inputs")) {
            JsonObject pin = value.getAsJsonObject();
            if (id.equals(pin.get("id").getAsString())) {
                return pin;
            }
        }
        throw new AssertionError("Missing pin: " + id);
    }
}
