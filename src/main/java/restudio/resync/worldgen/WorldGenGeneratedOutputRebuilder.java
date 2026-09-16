package restudio.resync.worldgen;

import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.AssetTransactionCoordinator.AssetDelta;
import restudio.resync.storage.AssetTransactionCoordinator.ExpectedState;
import restudio.resync.storage.AssetTransactionCoordinator.Missing;
import restudio.resync.storage.AssetTransactionCoordinator.Snapshot;
import restudio.resync.storage.AssetTransactionCoordinator.TransactionRequest;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.storage.StorageSafety;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.data.WorldGenSerializer;
import restudio.resync.worldgen.datapack.WorldGenBuildRecipe;
import restudio.resync.worldgen.datapack.WorldGenDatapackCompiler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class WorldGenGeneratedOutputRebuilder implements WorldGenGeneratedOutputController.Rebuilder {
    public static final String RECIPE_FILE = "worldgen-generated-rebuild-recipe.v1";
    private final WorldGenProjectStorage storage;
    private final WorldGenDatapackCompiler compiler;
    private final BooleanSupplier freshRepairAuthorizer;
    private final Object recipeMonitor = new Object();
    private volatile List<RecipeEntry> lastRecipe = List.of();

    public WorldGenGeneratedOutputRebuilder(WorldGenProjectStorage storage, WorldGenDatapackCompiler compiler) {
        this(storage, compiler, storage.currentCoordinator());
    }

    public WorldGenGeneratedOutputRebuilder(WorldGenProjectStorage storage, WorldGenDatapackCompiler compiler,
                                            AssetTransactionCoordinator coordinator) {
        this(storage, compiler, coordinator, () -> false);
    }

    public WorldGenGeneratedOutputRebuilder(WorldGenProjectStorage storage, WorldGenDatapackCompiler compiler,
                                            AssetTransactionCoordinator coordinator,
                                            BooleanSupplier freshRepairAuthorizer) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        AssetTransactionCoordinator initialCoordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.freshRepairAuthorizer = Objects.requireNonNull(freshRepairAuthorizer, "freshRepairAuthorizer");
        if (this.storage.currentCoordinator() != initialCoordinator
            || !this.storage.getAssetsPath().equals(initialCoordinator.canonicalRoot())) {
            throw new IllegalArgumentException("WorldGen Rebuild Recipe Coordinator Root Does Not Match Asset Root");
        }
    }

    @Override
    public void rebuild(Path generatedRoot) throws IOException {
        Path root = MigrationPaths.requireDirectory(Objects.requireNonNull(generatedRoot, "generatedRoot"), "generatedRoot");
        withCurrentCoordinator(coordinator -> {
            List<RecipeEntry> recipe = captureRecipe();
            WorldGenGeneratedRebuildRecipe persisted = readRecipe(coordinator);
            if (!persisted.matches(recipe.stream().map(entry -> new WorldGenGeneratedRebuildRecipe.Entry(entry.projectId(), entry.recipe())).toList())) {
                throw new IOException("WorldGen Rebuild Recipe Does Not Match Authoritative Assets");
            }
            for (RecipeEntry entry : recipe) {
                WorldGenProject copy = WorldGenSerializer.deserializeProject(entry.serializedProject());
                if (copy == null) {
                    throw new IOException("WorldGen Authoritative Asset Is Invalid: " + entry.projectId());
                }
                compiler.compile(copy, root, entry.recipe());
            }
            lastRecipe = List.copyOf(recipe);
            return null;
        });
    }

    @Override
    public void healthCheck() throws IOException {
        if (!recipeMatchesCurrentAssets()) {
            throw new IOException("WorldGen Rebuild Recipe Does Not Match Authoritative Assets");
        }
    }

    public void ensureRecipe() throws IOException {
        ensureRecipe(false);
    }

    public void ensureRecipeForAuthorizedFreshRepair() throws IOException {
        if (!freshRepairAuthorizer.getAsBoolean()) {
            throw new IOException("WorldGen Fresh Derived Repair Is Not Authorized");
        }
        ensureRecipe(true);
    }

    private void ensureRecipe(boolean allowFreshRepair) throws IOException {
        withCurrentCoordinator(coordinator -> {
            List<RecipeEntry> recipe = captureRecipe();
            WorldGenGeneratedRebuildRecipe durable = WorldGenGeneratedRebuildRecipe.capture(
                recipe.stream().map(entry -> new WorldGenGeneratedRebuildRecipe.Entry(entry.projectId(), entry.recipe())).toList());
            Snapshot snapshot = coordinatorSnapshot(coordinator);
            ExpectedState state = snapshot.state(WorldGenGeneratedRebuildRecipe.ASSET_KEY).orElse(Missing.INSTANCE);
            if (state instanceof AssetTransactionCoordinator.Live) {
                WorldGenGeneratedRebuildRecipe persisted = readRecipe(snapshot);
                if (!persisted.matches(recipe.stream()
                    .map(entry -> new WorldGenGeneratedRebuildRecipe.Entry(entry.projectId(), entry.recipe())).toList())) {
                    if (!allowFreshRepair) {
                        throw new IOException("WorldGen Rebuild Recipe Does Not Match Authoritative Assets");
                    }
                    persistCurrentRecipe();
                    readRecipe(coordinator);
                }
            } else if (state instanceof Missing) {
                commitRecipe(coordinator, snapshot, durable, state);
                readRecipe(coordinator);
            } else {
                throw new IOException("WorldGen Rebuild Recipe Is Not Materializable From Its Coordinator State");
            }
            lastRecipe = List.copyOf(recipe);
            return null;
        });
    }

    public void persistCurrentRecipe() throws IOException {
        withCurrentCoordinator(coordinator -> {
            List<RecipeEntry> recipe = captureRecipe();
            List<WorldGenGeneratedRebuildRecipe.Entry> expected = recipe.stream()
                .map(entry -> new WorldGenGeneratedRebuildRecipe.Entry(entry.projectId(), entry.recipe()))
                .toList();
            WorldGenGeneratedRebuildRecipe durable = WorldGenGeneratedRebuildRecipe.capture(expected);
            Snapshot snapshot = coordinatorSnapshot(coordinator);
            ExpectedState state = snapshot.state(WorldGenGeneratedRebuildRecipe.ASSET_KEY).orElse(Missing.INSTANCE);
            if (state instanceof AssetTransactionCoordinator.Live) {
                WorldGenGeneratedRebuildRecipe persisted = readRecipe(snapshot);
                if (persisted.matches(expected)) {
                    lastRecipe = List.copyOf(recipe);
                    return null;
                }
            } else if (!(state instanceof Missing)) {
                throw new IOException("WorldGen Rebuild Recipe Is Not Updatable From Its Coordinator State");
            }
            commitRecipe(coordinator, snapshot, durable, state);
            readRecipe(coordinator);
            lastRecipe = List.copyOf(recipe);
            return null;
        });
    }

    public Path recipePath() {
        Path assets = storage.getAssetsPath();
        Path durability = assets.resolve(".durability").toAbsolutePath().normalize();
        return durability.resolve(RECIPE_FILE).toAbsolutePath().normalize();
    }

    public WorldGenGeneratedRebuildRecipe durableRecipe() throws IOException {
        return readRecipe();
    }

    public boolean recipeMatchesCurrentAssets() throws IOException {
        return withCurrentCoordinator(coordinator -> {
            List<RecipeEntry> current = captureRecipe();
            List<WorldGenGeneratedRebuildRecipe.Entry> expected = current.stream()
                .map(entry -> new WorldGenGeneratedRebuildRecipe.Entry(entry.projectId(), entry.recipe()))
                .toList();
            Snapshot snapshot = coordinatorSnapshot(coordinator);
            ExpectedState state = snapshot.state(WorldGenGeneratedRebuildRecipe.ASSET_KEY).orElse(Missing.INSTANCE);
            if (!(state instanceof AssetTransactionCoordinator.Live)) {
                return false;
            }
            return readRecipe(snapshot).matches(expected);
        });
    }

    public List<RecipeEntry> lastRecipe() {
        return lastRecipe;
    }

    private <T> T withCurrentCoordinator(WorldGenProjectStorage.CoordinatorOperation<T> operation) throws IOException {
        return storage.withCurrentCoordinator(coordinator -> {
            synchronized (recipeMonitor) {
                return operation.apply(coordinator);
            }
        });
    }

    private WorldGenGeneratedRebuildRecipe readRecipe() throws IOException {
        return withCurrentCoordinator(this::readRecipe);
    }

    private WorldGenGeneratedRebuildRecipe readRecipe(AssetTransactionCoordinator coordinator) throws IOException {
        try {
            return coordinator.read(this::readRecipeUnchecked);
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private WorldGenGeneratedRebuildRecipe readRecipeUnchecked(Snapshot snapshot) {
        try {
            return readRecipe(snapshot);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private WorldGenGeneratedRebuildRecipe readRecipe(Snapshot snapshot) throws IOException {
        ExpectedState state = snapshot.state(WorldGenGeneratedRebuildRecipe.ASSET_KEY).orElse(Missing.INSTANCE);
        if (!(state instanceof AssetTransactionCoordinator.Live live)) {
            throw new IOException("WorldGen Rebuild Recipe Is Not A Live Coordinated Asset");
        }
        Path path = snapshot.path(WorldGenGeneratedRebuildRecipe.ASSET_KEY)
            .orElseThrow(() -> new IOException("WorldGen Rebuild Recipe Coordinator Path Is Missing"));
        requireRecipePath(path);
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("WorldGen Rebuild Recipe Is Not A Regular File: " + path);
        }
        byte[] bytes = Files.readAllBytes(path);
        if (!live.hash().equals(StorageSafety.sha256(bytes))) {
            throw new IOException("WorldGen Rebuild Recipe Does Not Match Coordinator State: " + path);
        }
        return WorldGenGeneratedRebuildRecipe.readBytes(bytes, path);
    }

    private Snapshot coordinatorSnapshot(AssetTransactionCoordinator coordinator) {
        return coordinator.read(snapshot -> snapshot);
    }

    private void commitRecipe(AssetTransactionCoordinator coordinator, Snapshot snapshot,
                              WorldGenGeneratedRebuildRecipe durable,
                              ExpectedState expected) throws IOException {
        Path path = snapshot.path(WorldGenGeneratedRebuildRecipe.ASSET_KEY).orElseGet(this::recipePath);
        requireRecipePath(path);
        byte[] bytes = durable.encodedBytes();
        try {
            coordinator.transact(new TransactionRequest(UUID.randomUUID(), snapshot.project(),
                List.of(AssetDelta.write(WorldGenGeneratedRebuildRecipe.ASSET_KEY, path, expected, bytes)), List.of()));
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("WorldGen Rebuild Recipe Transaction Failed", exception);
        }
    }

    private void requireRecipePath(Path path) throws IOException {
        Path expected = recipePath();
        Path normalized = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (!expected.equals(normalized)) {
            throw new IOException("WorldGen Rebuild Recipe Coordinator Path Is Invalid: " + normalized);
        }
        MigrationPaths.requireNoSymlinkTraversal(storage.getAssetsPath(), normalized.getParent());
    }

    private List<RecipeEntry> captureRecipe() throws IOException {
        List<RecipeEntry> recipe = new ArrayList<>();
        for (String projectId : storage.listProjectIds().stream().sorted().toList()) {
            WorldGenProject stored = storage.getProject(projectId);
            if (stored == null) {
                throw new IOException("WorldGen Authoritative Asset Is Missing: " + projectId);
            }
            String serialized = WorldGenSerializer.serializeProject(stored);
            WorldGenProject copy = WorldGenSerializer.deserializeProject(serialized);
            if (copy == null) {
                throw new IOException("WorldGen Authoritative Asset Is Invalid: " + projectId);
            }
            recipe.add(new RecipeEntry(projectId, serialized, WorldGenBuildRecipe.capture(copy)));
        }
        return List.copyOf(recipe);
    }

    public record RecipeEntry(String projectId, String serializedProject, WorldGenBuildRecipe recipe) {
        public RecipeEntry {
            projectId = Objects.requireNonNull(projectId, "projectId");
            serializedProject = Objects.requireNonNull(serializedProject, "serializedProject");
            recipe = Objects.requireNonNull(recipe, "recipe");
            if (!recipe.selfHashValid()) {
                throw new IllegalArgumentException("WorldGen Build Recipe Self-Hash Does Not Match: " + projectId);
            }
        }
    }
}
