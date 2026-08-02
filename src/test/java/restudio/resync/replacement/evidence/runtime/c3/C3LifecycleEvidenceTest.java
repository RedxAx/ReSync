package restudio.resync.replacement.evidence.runtime.c3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.flow.automation.AutomationReferences;
import restudio.resync.flow.automation.AutomationTaskService;
import restudio.resync.flow.triggers.TriggerBinding;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.network.NetworkResourceMutation;
import restudio.resync.resource.ReSyncResourceKey;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class C3LifecycleEvidenceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void triggerRegistryRestoresBindingsUsingRawFlowIdentity() throws IOException {
        Path file = temporaryDirectory.resolve("triggers.json");
        try (InputStream source = getClass().getResourceAsStream("/fixtures/node-replacement/runtime/c3/trigger-bindings.json")) {
            assertNotNull(source, "Missing trigger fixture");
            Files.copy(source, file);
        }

        TriggerRegistry registry = new TriggerRegistry(file.toFile());
        TriggerRegistry reloaded = new TriggerRegistry(file.toFile());

        assertEquals(2, reloaded.getBindings().size());
        assertEquals("shared", reloaded.getBindings().stream().map(TriggerBinding::getFlowId).distinct().findFirst().orElseThrow());
        assertEquals(Set.of("event-primary", "system-primary"), registry.getBindings().stream().map(TriggerBinding::getId).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void automationReferenceIdentityReducesToItsRawId() {
        FlowResourceReference reference = new FlowResourceReference("timer_definition", "shared", "server", true, Map.of());

        assertEquals("shared", AutomationReferences.id(reference));
    }

    @Test
    void sameIdTypedKeysRemainDistinctInTheCurrentLocalKey() {
        ReSyncResourceKey flow = new ReSyncResourceKey("flow", "shared");
        ReSyncResourceKey command = new ReSyncResourceKey("command", "shared");

        assertNotEquals(flow, command);
        assertNotEquals(flow.token(), command.token());
    }

    @Test
    void persistentTaskPersistsRawDefinitionAndOwnerWithoutRevisionOrPlanBinding() throws IOException {
        AutomationTaskService.PersistentTask task = fixture("automation-persistent-task.json", AutomationTaskService.PersistentTask.class);
        String serialized = new Gson().toJson(task);
        String[] components = Arrays.stream(AutomationTaskService.PersistentTask.class.getRecordComponents()).map(RecordComponent::getName).toArray(String[]::new);

        assertEquals("shared", task.definitionId());
        assertEquals("server-owner", task.ownerId());
        assertEquals(serialized, new Gson().toJson(new Gson().fromJson(serialized, AutomationTaskService.PersistentTask.class)));
        assertFalse(Arrays.asList(components).contains("definitionRevision"));
        assertFalse(Arrays.asList(components).contains("catalogGeneration"));
        assertFalse(Arrays.asList(components).contains("planChecksum"));
    }

    @Test
    void networkMutationsKeepTypeAndRevisionButHaveNoServerOrMutationIdentity() throws IOException {
        NetworkResourceMutation save = NetworkResourceMutation.save("flow", "shared", 7L, "save".getBytes(StandardCharsets.UTF_8));
        NetworkResourceMutation delete = NetworkResourceMutation.delete("command", "shared", 8L);
        JsonArray fixture = JsonParser.parseString(resource("network-resource-mutations.json")).getAsJsonArray();
        String[] components = Arrays.stream(NetworkResourceMutation.class.getRecordComponents()).map(RecordComponent::getName).toArray(String[]::new);

        assertNotEquals(save.type(), delete.type());
        assertEquals(save.resourceId(), delete.resourceId());
        assertEquals(7L, save.expectedRevision());
        assertEquals(8L, delete.expectedRevision());
        assertFalse(save.deleted());
        assertEquals("save", new String(save.payload(), StandardCharsets.UTF_8));
        assertTrue(delete.deleted());
        assertEquals(0, delete.payload().length);
        assertEquals(2, fixture.size());
        JsonObject saveFixture = fixture.get(0).getAsJsonObject();
        JsonObject deleteFixture = fixture.get(1).getAsJsonObject();
        assertEquals(save.type(), saveFixture.get("type").getAsString());
        assertEquals(save.resourceId(), saveFixture.get("resourceId").getAsString());
        assertEquals(save.expectedRevision(), saveFixture.get("expectedRevision").getAsLong());
        assertEquals("c2F2ZQ==", saveFixture.get("payload").getAsString());
        assertEquals(save.deleted(), saveFixture.get("deleted").getAsBoolean());
        assertEquals(delete.type(), deleteFixture.get("type").getAsString());
        assertEquals(delete.resourceId(), deleteFixture.get("resourceId").getAsString());
        assertEquals(delete.expectedRevision(), deleteFixture.get("expectedRevision").getAsLong());
        assertEquals("", deleteFixture.get("payload").getAsString());
        assertEquals(delete.deleted(), deleteFixture.get("deleted").getAsBoolean());
        assertFalse(Arrays.asList(components).contains("mutationId"));
        assertFalse(Arrays.asList(components).contains("serverId"));
    }

    private <T> T fixture(String name, Class<T> type) throws IOException {
        return new Gson().fromJson(resource(name), type);
    }

    private String resource(String name) throws IOException {
        try (InputStream source = getClass().getResourceAsStream("/fixtures/node-replacement/runtime/c3/" + name)) {
            assertNotNull(source, () -> "Missing fixture: " + name);
            return new String(source.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
