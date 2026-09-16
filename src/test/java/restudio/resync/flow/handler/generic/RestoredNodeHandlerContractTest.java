package restudio.resync.flow.handler.generic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RestoredNodeHandlerContractTest {
    private static final List<Path> RESTORED_CATALOGS = List.of(
        Path.of("src", "main", "resources", "nodes", "entity_restored.json"),
        Path.of("src", "main", "resources", "nodes", "player_query_restored.json")
    );
    private static final Set<String> QUERY_IDS = Set.of(
        "entity_get_passengers",
        "entity_get_vehicle",
        "entity_has_tag",
        "entity_has_any_tag",
        "entity_has_all_tags",
        "entity_get_tags"
    );

    @Test
    void handlerDeclaresEveryRestoredCatalogOperation() throws Exception {
        Set<String> catalogIds = new HashSet<>();
        for (Path catalog : RESTORED_CATALOGS) {
            for (JsonElement element : read(catalog)) {
                catalogIds.add(element.getAsJsonObject().get("id").getAsString());
            }
        }

        assertEquals(catalogIds, new RestoredNodeHandler().getSupportedOperations());
    }

    @Test
    void onlyQueryLikeEntityDefinitionsUseQueryKind() throws Exception {
        Set<String> queryIds = new HashSet<>();
        for (JsonElement element : read(RESTORED_CATALOGS.getFirst())) {
            JsonObject definition = element.getAsJsonObject();
            String id = definition.get("id").getAsString();
            if ("QUERY".equals(definition.get("kind").getAsString())) {
                queryIds.add(id);
            }
            if (QUERY_IDS.contains(id)) {
                assertEquals("QUERY", definition.get("kind").getAsString(), id);
            }
        }

        assertEquals(QUERY_IDS, queryIds);
    }

    private JsonArray read(Path path) throws Exception {
        return JsonParser.parseString(Files.readString(path)).getAsJsonArray();
    }
}
