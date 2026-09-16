package restudio.resync.storage;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetTransactionCoordinatorMutationHistoryTest {
    @TempDir
    Path directory;

    @Test
    void historicalProjectSnapshotStaysBoundToItsCommittedResult() throws Exception {
        Path root = directory.resolve("assets");
        UUID first = UUID.randomUUID();
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, new Gson())) {
            AssetTransactionCoordinator.TransactionResult result = setTitle(coordinator, first, "First");
            setTitle(coordinator, UUID.randomUUID(), "Second");
            AssetTransactionCoordinator.MutationView history = coordinator.mutation(first).orElseThrow();
            assertEquals(result, history.result());
            assertEquals(result.project().hash(), history.projectAfter().hash());
            assertEquals("First", history.projectAfter().document().get("title").getAsString());
            JsonObject copy = history.projectAfter().document();
            copy.addProperty("title", "Changed");
            assertEquals("First", history.projectAfter().document().get("title").getAsString());
        }
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, new Gson())) {
            assertEquals("First", coordinator.mutation(first).orElseThrow().projectAfter().document().get("title").getAsString());
        }
    }

    @Test
    void alteredProjectSnapshotCannotBeReadAsCommittedHistory() throws Exception {
        Path root = directory.resolve("tampered-assets");
        UUID mutation = UUID.randomUUID();
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, new Gson())) {
            setTitle(coordinator, mutation, "First");
            Path binding = root.resolve(".asset-coordinator/bindings/" + mutation + ".json");
            JsonObject document = JsonParser.parseString(Files.readString(binding)).getAsJsonObject();
            document.getAsJsonObject("projectAfter").addProperty("title", "Changed");
            Files.writeString(binding, document.toString());
            assertThrows(IOException.class, () -> coordinator.mutation(mutation));
        }
    }

    @Test
    void unchangedFormattedProjectDoesNotInvalidateExistingMutationHistory() throws Exception {
        Path root = directory.resolve("formatted-assets");
        Files.createDirectories(root);
        String project = "{\n  \"title\": \"Existing\"\n}\n";
        Files.writeString(root.resolve("project.json"), project);
        UUID mutation = UUID.randomUUID();
        AssetTransactionCoordinator.AdoptionInventory inventory = new AssetTransactionCoordinator.AdoptionInventory(
            "replacement-manifest-v1", project, List.of(), List.of());
        AssetTransactionCoordinator.AdoptionBinding binding = new AssetTransactionCoordinator.AdoptionBinding(
            StorageSafety.sha256("artifact"), StorageSafety.sha256("manifest"));
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.openOrAdopt(root, new Gson(), inventory, binding)) {
            AssetTransactionCoordinator.TransactionResult result = coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(
                mutation, coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                List.of(AssetTransactionCoordinator.AssetDelta.write(new AssetTransactionCoordinator.AssetKey("test", "one"),
                    Path.of("one.json"), AssetTransactionCoordinator.Missing.INSTANCE, "{}".getBytes(StandardCharsets.UTF_8))), List.of()));
            AssetTransactionCoordinator.MutationView history = coordinator.mutation(mutation).orElseThrow();
            assertEquals(result, history.result());
            assertEquals("Existing", history.projectAfter().document().get("title").getAsString());
        }
    }

    private static AssetTransactionCoordinator.TransactionResult setTitle(AssetTransactionCoordinator coordinator,
                                                                          UUID mutation, String title) throws IOException {
        return coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(mutation,
            coordinator.read(AssetTransactionCoordinator.Snapshot::project), List.of(),
            List.of(AssetTransactionCoordinator.ProjectDelta.set(List.of("title"), new JsonPrimitive(title)))));
    }
}
