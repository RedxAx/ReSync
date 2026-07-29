package restudio.resync.modules.flow;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.resync.flow.workspace.WorkspacePatch;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowWorkspaceServiceTest {
    private final FlowWorkspaceService service = new FlowWorkspaceService(null, null, null);

    @Test
    void appliesEntityAndConnectionOperations() {
        JsonObject document = object("""
            {"nodes":{"first":{"x":10,"y":20}},"connections":[]}
            """);
        JsonObject connection = object("""
            {"sourceNodeId":"first","sourcePin":"out","targetNodeId":"second","targetPin":"in"}
            """);

        service.apply(document, new WorkspacePatch<>("set", "/nodes/first/x", JsonParser.parseString("80")));
        service.apply(document, new WorkspacePatch<>("set", "/nodes/second", object("""
            {"x":140,"y":20}
            """)));
        service.apply(document, new WorkspacePatch<>("array_add", "/connections", connection));
        service.apply(document, new WorkspacePatch<>("array_add", "/connections", connection));

        assertEquals(80, document.getAsJsonObject("nodes").getAsJsonObject("first").get("x").getAsInt());
        assertEquals(2, document.getAsJsonObject("nodes").size());
        assertEquals(1, document.getAsJsonArray("connections").size());

        service.apply(document, new WorkspacePatch<>("array_remove", "/connections", connection));
        service.apply(document, new WorkspacePatch<>("remove", "/nodes/first", null));

        assertEquals(0, document.getAsJsonArray("connections").size());
        assertFalse(document.getAsJsonObject("nodes").has("first"));
    }

    @Test
    void rejectsClientChangesToServerOwnedIdentity() {
        assertFalse(service.validClientPatch(new WorkspacePatch<>("set", "/id", JsonParser.parseString("\"other\""))));
        assertFalse(service.validClientPatch(new WorkspacePatch<>("set", "/resourceRevision", JsonParser.parseString("30"))));
        assertTrue(service.validClientPatch(new WorkspacePatch<>("set", "/nodes/first/x", JsonParser.parseString("80"))));
    }

    @Test
    void rejectsPathsOutsideTheDocumentShape() {
        JsonObject document = object("""
            {"nodes":{},"connections":[]}
            """);

        assertThrows(IllegalArgumentException.class,
            () -> service.apply(document, new WorkspacePatch<>("set", "nodes/first", object("{}"))));
    }

    @Test
    void rebasesAcceptedPatchesOntoNewerResourceContent() {
        JsonObject latest = object("""
            {"resourceRevision":2,"nodes":{"first":{"x":10,"y":90}},"connections":[]}
            """);
        List<WorkspacePatch<JsonElement>> patches = List.of(
            new WorkspacePatch<>("set", "/nodes/first/x", JsonParser.parseString("80")),
            new WorkspacePatch<>("set", "/nodes/second", object("""
                {"x":140,"y":20}
                """)));

        JsonObject rebased = service.rebase(latest, patches);

        assertEquals(2, rebased.get("resourceRevision").getAsInt());
        assertEquals(80, rebased.getAsJsonObject("nodes").getAsJsonObject("first").get("x").getAsInt());
        assertEquals(90, rebased.getAsJsonObject("nodes").getAsJsonObject("first").get("y").getAsInt());
        assertTrue(rebased.getAsJsonObject("nodes").has("second"));
    }

    private JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
