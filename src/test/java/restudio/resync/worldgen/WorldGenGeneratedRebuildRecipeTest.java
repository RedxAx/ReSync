package restudio.resync.worldgen;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.AssetTransactionCoordinator.AssetDelta;
import restudio.resync.storage.AssetTransactionCoordinator.Missing;
import restudio.resync.storage.AssetTransactionCoordinator.Snapshot;
import restudio.resync.storage.AssetTransactionCoordinator.TransactionRequest;
import restudio.resync.storage.StorageSafety;
import restudio.resync.worldgen.datapack.WorldGenBuildRecipe;
import restudio.resync.worldgen.datapack.WorldGenDatapackCompiler;
import restudio.resync.worldgen.data.WorldGenProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenGeneratedRebuildRecipeTest {
    @TempDir
    Path temporary;

    @Test
    void recipeSurvivesRestartWithCanonicalSelfHash() throws Exception {
        WorldGenBuildRecipe entryRecipe = recipe("a", 11L);
        WorldGenGeneratedRebuildRecipe.Entry entry = new WorldGenGeneratedRebuildRecipe.Entry("project-a", entryRecipe);
        WorldGenGeneratedRebuildRecipe recipe = WorldGenGeneratedRebuildRecipe.capture(List.of(entry));
        Path assets = temporary.resolve("assets");
        Path file = assets.resolve(".durability").resolve(WorldGenGeneratedOutputRebuilder.RECIPE_FILE);

        try (AssetTransactionCoordinator coordinator = coordinator(assets)) {
            materialize(coordinator, recipe);
        }

        try (AssetTransactionCoordinator coordinator = coordinator(assets)) {
            boolean recipeRegistered = coordinator.read(
                snapshot -> snapshot.state(WorldGenGeneratedRebuildRecipe.ASSET_KEY).isPresent());
            assertTrue(recipeRegistered);
            assertEquals(recipe, WorldGenGeneratedRebuildRecipe.readBytes(Files.readAllBytes(file), file));
        }
    }

    @Test
    void tamperedRecipeIsRejectedBeforeRebuild() throws Exception {
        WorldGenBuildRecipe entryRecipe = recipe("b", 12L);
        WorldGenGeneratedRebuildRecipe recipe = WorldGenGeneratedRebuildRecipe.capture(List.of(
            new WorldGenGeneratedRebuildRecipe.Entry("project-b", entryRecipe)));
        Path assets = temporary.resolve("assets");
        Path file = assets.resolve(".durability").resolve(WorldGenGeneratedOutputRebuilder.RECIPE_FILE);

        try (AssetTransactionCoordinator coordinator = coordinator(assets)) {
            materialize(coordinator, recipe);
            Files.writeString(file, Files.readString(file).replace("selfHash=" + recipe.selfHash(),
                "selfHash=" + "0".repeat(64)));
            byte[] tampered = Files.readAllBytes(file);

            assertThrows(IOException.class, coordinator::healthCheck);
            assertArrayEquals(tampered, Files.readAllBytes(file));
        }
    }

    @Test
    void missingTrackedRecipeIsRejectedWithoutMaterialization() throws Exception {
        WorldGenGeneratedRebuildRecipe recipe = WorldGenGeneratedRebuildRecipe.capture(List.of(
            new WorldGenGeneratedRebuildRecipe.Entry("project-c", recipe("c", 13L))));
        Path assets = temporary.resolve("assets");
        Path file = assets.resolve(".durability").resolve(WorldGenGeneratedOutputRebuilder.RECIPE_FILE);
        try (AssetTransactionCoordinator coordinator = coordinator(assets)) {
            materialize(coordinator, recipe);
            Files.delete(file);

            assertThrows(IOException.class, coordinator::healthCheck);
            assertFalse(Files.exists(file));
        }
    }

    @Test
    void malformedUntrackedRecipeIsPreservedAndRejected() throws Exception {
        WorldGenGeneratedRebuildRecipe recipe = WorldGenGeneratedRebuildRecipe.capture(List.of());
        Path assets = temporary.resolve("assets");
        Path file = assets.resolve(".durability").resolve(WorldGenGeneratedOutputRebuilder.RECIPE_FILE);

        try (AssetTransactionCoordinator coordinator = coordinator(assets)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "malformed\n");
            byte[] before = Files.readAllBytes(file);
            assertThrows(IOException.class, () -> materialize(coordinator, recipe));
            assertArrayEquals(before, Files.readAllBytes(file));
        }
    }

    @Test
    void authorizedFreshRepairPersistsAValidSemanticallyStaleRecipe() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("fresh-repair"));
        try (AssetTransactionCoordinator coordinator = coordinator(root.resolve("assets"))) {
            WorldGenProjectStorage storage = new WorldGenProjectStorage(root.toFile(),
                LegacyRuntimeActivationGate.runtime(root), new AssetPersistenceGate(root), coordinator);
            WorldGenProject project = new WorldGenProject();
            project.setId("project");
            project.getSettings().setTargetVersion("26.2");
            storage.saveProject(project, UUID.randomUUID(), 0L);
            WorldGenBuildRecipe staleEntry = recipe("stale", 14L);
            WorldGenGeneratedRebuildRecipe stale = WorldGenGeneratedRebuildRecipe.capture(List.of(
                new WorldGenGeneratedRebuildRecipe.Entry("project", staleEntry)));
            materialize(coordinator, stale);

            WorldGenGeneratedOutputRebuilder rebuilder = new WorldGenGeneratedOutputRebuilder(storage,
                new WorldGenDatapackCompiler(null, true), coordinator, () -> true);

            assertThrows(IOException.class, rebuilder::ensureRecipe);
            rebuilder.ensureRecipeForAuthorizedFreshRepair();

            WorldGenGeneratedOutputRebuilder unauthorized = new WorldGenGeneratedOutputRebuilder(storage,
                new WorldGenDatapackCompiler(null, true), coordinator);
            assertThrows(IOException.class, unauthorized::ensureRecipeForAuthorizedFreshRepair);

            WorldGenGeneratedRebuildRecipe repaired = rebuilder.durableRecipe();
            assertFalse(repaired.equals(stale));
            WorldGenProject stored = storage.getProject("project");
            assertTrue(repaired.matches(List.of(new WorldGenGeneratedRebuildRecipe.Entry("project",
                WorldGenBuildRecipe.capture(stored)))));
            storage.closePersistence();
        }
    }

    @Test
    void nonRegularUntrackedRecipeIsRejectedWithoutReplacement() throws Exception {
        WorldGenGeneratedRebuildRecipe recipe = WorldGenGeneratedRebuildRecipe.capture(List.of());
        Path assets = temporary.resolve("assets");
        Path file = assets.resolve(".durability").resolve(WorldGenGeneratedOutputRebuilder.RECIPE_FILE);

        try (AssetTransactionCoordinator coordinator = coordinator(assets)) {
            Files.createDirectories(file);
            assertThrows(IOException.class, () -> materialize(coordinator, recipe));
            assertTrue(Files.isDirectory(file));
        }
    }

    private AssetTransactionCoordinator coordinator(Path assets) throws IOException {
        Files.createDirectories(assets);
        return AssetTransactionCoordinator.open(assets, new Gson());
    }

    private void materialize(AssetTransactionCoordinator coordinator, WorldGenGeneratedRebuildRecipe recipe)
        throws IOException {
        Snapshot snapshot = coordinator.read(current -> current);
        Path path = coordinator.canonicalRoot().resolve(".durability").resolve(WorldGenGeneratedOutputRebuilder.RECIPE_FILE);
        coordinator.transact(new TransactionRequest(UUID.randomUUID(), snapshot.project(), List.of(
            AssetDelta.write(WorldGenGeneratedRebuildRecipe.ASSET_KEY, path, Missing.INSTANCE, recipe.encodedBytes())), List.of()));
    }

    private WorldGenBuildRecipe recipe(String checksumSeed, long revision) {
        String assetChecksum = StorageSafety.sha256(checksumSeed + "-asset");
        String fingerprint = StorageSafety.sha256(checksumSeed + "-runtime");
        WorldGenBuildRecipe unsigned = new WorldGenBuildRecipe(revision, "26.2", "a".repeat(40), assetChecksum, fingerprint, "");
        return new WorldGenBuildRecipe(unsigned.revision(), unsigned.minecraftVersion(), unsigned.catalogSha1(),
            unsigned.assetGraphChecksum(), unsigned.catalogRuntimeFingerprint(), StorageSafety.sha256(unsigned.canonicalText()));
    }
}
