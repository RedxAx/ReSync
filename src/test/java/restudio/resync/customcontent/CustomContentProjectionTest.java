package restudio.resync.customcontent;

import com.google.gson.Gson;
import com.google.gson.JsonPrimitive;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.CustomContentGraphAdapter;
import restudio.resync.api.OptionCatalogCapture;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.api.RuntimeDataQuery;
import restudio.resync.api.RuntimeDataRecord;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.modules.flow.BuiltinOptionCatalogService;
import restudio.resync.runtime.data.CustomContentItemDataAdapter;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.AssetTransactionCoordinator.ProjectDelta;
import restudio.resync.storage.AssetTransactionCoordinator.TransactionRequest;
import restudio.resync.storage.CanonicalProjectMetadataFixture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomContentProjectionTest {
    @TempDir
    Path tempDir;

    @Test
    void runtimeDataCategoryProjectionTracksOnlyExactCustomContentState() throws Exception {
        AssetPersistenceGate gate = new AssetPersistenceGate(tempDir);
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(tempDir.resolve("assets"), new Gson())) {
            CanonicalProjectMetadataFixture.seed(coordinator);
            CustomContentStorage storage = new CustomContentStorage(tempDir.toFile(), gate, coordinator);
            storage.save(definition("category_item", "Category Item"));
            CustomContentStorage.CategoryProjection projection = storage.readRuntimeDataCategoryProjection();
            CustomContentItemDataAdapter adapter = new CustomContentItemDataAdapter(storage, null);
            RuntimeDataRecord record = adapter.records(RuntimeDataQuery.all()).getFirst();
            OptionCatalogProvider category = adapter.categoryCatalog();
            OptionCatalogQuery query = new OptionCatalogQuery(category.sourceId(), Map.of());
            OptionCatalogCapture capture = category.capture(query);
            OptionCatalogRegistry.PreparedCaptureProvider prepared = (OptionCatalogRegistry.PreparedCaptureProvider) category;

            assertEquals("category_item", record.id());
            assertEquals("resync:custom_items", record.adapterId());
            assertEquals(Set.of("custom", "item", "resync", "vanilla"), record.categories());
            assertEquals(Set.of("contentType", "provider", "material", "flowId", "externalId"), record.attributes().keySet());
            assertTrue(storage.isRuntimeDataCategoryProjectionCurrent(projection));
            assertEquals(OptionCatalogProvider.CaptureAffinity.IO, category.captureAffinity());
            assertTrue(capture.values().containsAll(List.of("custom", "item", "resync", "vanilla")));
            assertEquals(capture, prepared.preparedCapture(query));
            AssetTransactionCoordinator.Snapshot metadata = coordinator.read(snapshot -> snapshot);
            coordinator.transact(new TransactionRequest(UUID.randomUUID(), metadata.project(), List.of(),
                List.of(ProjectDelta.set(List.of("unrelated"), new JsonPrimitive("preserved")))));
            assertTrue(storage.isRuntimeDataCategoryProjectionCurrent(projection));
            assertEquals(capture, prepared.preparedCapture(query));

            storage.save(definition("category_item", "Changed Category Item"));
            assertFalse(storage.isRuntimeDataCategoryProjectionCurrent(projection));
            assertThrows(OptionCatalogRegistry.CaptureUnavailable.class, () -> prepared.preparedCapture(query));
            OptionCatalogCapture refreshed = category.capture(query);
            assertNotEquals(capture.revision(), refreshed.revision());
            assertEquals(refreshed, prepared.preparedCapture(query));

            gate.quiesce();
            storage.close();
        }
    }

    @Test
    void runtimeDataCategoryProjectionRejectsItsStoreAfterEqualSequenceRoundTripRebind() throws Exception {
        Path original = Files.createDirectory(tempDir.resolve("projection-original"));
        Path replacement = Files.createDirectory(tempDir.resolve("projection-replacement"));
        AssetPersistenceGate originalGate = new AssetPersistenceGate(original);
        AssetPersistenceGate replacementGate = new AssetPersistenceGate(replacement);
        try (AssetTransactionCoordinator originalCoordinator = AssetTransactionCoordinator.open(original.resolve("assets"), new Gson());
             AssetTransactionCoordinator replacementCoordinator = AssetTransactionCoordinator.open(replacement.resolve("assets"), new Gson())) {
            CanonicalProjectMetadataFixture.seed(originalCoordinator);
            CanonicalProjectMetadataFixture.seed(replacementCoordinator);
            CustomContentStorage storage = new CustomContentStorage(original.toFile(), originalGate, originalCoordinator);
            CustomContentStorage replacementStorage = new CustomContentStorage(replacement.toFile(), replacementGate,
                replacementCoordinator);
            try {
                storage.save(definition("category_item", "Original Category Item"));
                replacementStorage.save(definition("category_item", "Replacement Category Item"));
                assertEquals(originalCoordinator.read(AssetTransactionCoordinator.Snapshot::rootSequence),
                    replacementCoordinator.read(AssetTransactionCoordinator.Snapshot::rootSequence));
                CustomContentStorage.CategoryProjection projection = storage.readRuntimeDataCategoryProjection();

                replacementGate.quiesce();
                replacementStorage.close();
                originalGate.quiesce();
                storage.rebindPersistence(replacement, replacementCoordinator);
                originalGate.rebind(replacement);
                storage.resumePersistenceWhileQuiesced();
                originalGate.resume();
                CustomContentStorage.CategoryProjection rebound = storage.readRuntimeDataCategoryProjection();
                assertEquals("Replacement Category Item", rebound.definitions().getFirst().getDisplayName());

                originalGate.quiesce();
                storage.quiescePersistence();
                storage.rebindPersistence(original, originalCoordinator);
                originalGate.rebind(original);
                storage.resumePersistenceWhileQuiesced();
                originalGate.resume();
                assertFalse(storage.isRuntimeDataCategoryProjectionCurrent(projection));
                CustomContentStorage.CategoryProjection returned = storage.readRuntimeDataCategoryProjection();
                assertEquals("Original Category Item", returned.definitions().getFirst().getDisplayName());
                assertTrue(storage.isRuntimeDataCategoryProjectionCurrent(returned));
            } finally {
                if (replacementGate.isOpen()) {
                    replacementGate.quiesce();
                }
                replacementStorage.close();
                if (originalGate.isOpen()) {
                    originalGate.quiesce();
                }
                storage.close();
            }
        }
    }

    @Test
    void projectionReadsRespectSharedMutationFenceAndClosedLifecycle() throws Exception {
        AssetPersistenceGate gate = new AssetPersistenceGate(tempDir);
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(tempDir.resolve("assets"), new Gson())) {
            CanonicalProjectMetadataFixture.seed(coordinator);
            CustomContentStorage storage = new CustomContentStorage(tempDir.toFile(), gate, coordinator);
            storage.save(definition("projection_item", "Projection Item"));
            assertEquals(1, storage.readOptionCatalogProjection().size());
            assertEquals(1, storage.readShutdownCleanupProjection().size());

            gate.quiesce();

            IllegalStateException readFailure = assertThrows(IllegalStateException.class, storage::getAll);
            IllegalStateException optionFailure = assertThrows(IllegalStateException.class, storage::readOptionCatalogProjection);
            IllegalStateException cleanupFailure = assertThrows(IllegalStateException.class, storage::readShutdownCleanupProjection);
            IllegalStateException saveFailure = assertThrows(IllegalStateException.class,
                () -> storage.save(definition("blocked_item", "Blocked Item")));
            assertEquals("Shared asset persistence is QUIESCED; mutation rejected", readFailure.getMessage());
            assertEquals(readFailure.getMessage(), optionFailure.getMessage());
            assertEquals(readFailure.getMessage(), cleanupFailure.getMessage());
            assertEquals(readFailure.getMessage(), saveFailure.getMessage());

            storage.close();
            IllegalStateException closedOptionFailure = assertThrows(IllegalStateException.class,
                storage::readOptionCatalogProjection);
            IllegalStateException closedCleanupFailure = assertThrows(IllegalStateException.class,
                storage::readShutdownCleanupProjection);
            assertEquals("Custom content persistence is closed", closedOptionFailure.getMessage());
            assertEquals(closedOptionFailure.getMessage(), closedCleanupFailure.getMessage());
        }
    }

    @Test
    void recipeCatalogFingerprintRejectsQuiescedAndClosedStorageAfterOpenProjectionRead() throws Exception {
        MockBukkit.mock();
        AssetPersistenceGate gate = null;
        AssetTransactionCoordinator coordinator = null;
        CustomContentStorage storage = null;
        boolean storageClosed = false;
        try {
            JavaPlugin plugin = MockBukkit.createMockPlugin();
            Path scope = Files.createDirectory(tempDir.resolve("catalog-scope"));
            gate = new AssetPersistenceGate(scope);
            coordinator = AssetTransactionCoordinator.open(scope.resolve("assets"), new Gson());
            CanonicalProjectMetadataFixture.seed(coordinator);
            storage = new CustomContentStorage(plugin, scope, new ItemAttributeSchemaService(),
                LegacyRuntimeActivationGate.runtime(scope), gate, coordinator);
            storage.save(definition("catalog_item", "Catalog Item"));
            CustomContentService service = new CustomContentService(storage, null, null);
            BuiltinOptionCatalogService builtins = new BuiltinOptionCatalogService(() -> service,
                new ItemAttributeSchemaService());
            OptionCatalogRegistry registry = new OptionCatalogRegistry();
            builtins.registerProviders(registry);
            OptionCatalogProvider provider = registry.provider("server:custom_content:recipe_item");

            UnimplementedOperationException limitation = assertThrows(UnimplementedOperationException.class, provider::revision);
            assertTrue(Arrays.stream(limitation.getStackTrace())
                .noneMatch(frame -> frame.getClassName().equals(AssetPersistenceGate.class.getName())));
            assertEquals(1, storage.readOptionCatalogProjection().size());

            gate.quiesce();
            IllegalStateException quiescedFailure = assertThrows(IllegalStateException.class, provider::revision);
            assertEquals("Shared asset persistence is QUIESCED; mutation rejected", quiescedFailure.getMessage());

            storage.close();
            storageClosed = true;
            IllegalStateException closedFailure = assertThrows(IllegalStateException.class, provider::revision);
            assertEquals("Custom content persistence is closed", closedFailure.getMessage());
        } finally {
            if (storage != null && !storageClosed) {
                if (gate.isOpen()) {
                    gate.quiesce();
                }
                storage.close();
            }
            if (coordinator != null) {
                coordinator.close();
            }
            MockBukkit.unmock();
        }
    }

    private static CustomContentDefinition definition(String id, String name) {
        return CustomContentGraphAdapter.toDefinition(CustomContentGraphAdapter.createContentGraph(id, "item", name));
    }
}
