package restudio.resync.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.storage.AssetProjectMetadata;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.AssetTransactionCoordinator.AssetDelta;
import restudio.resync.storage.AssetTransactionCoordinator.AssetKey;
import restudio.resync.storage.AssetTransactionCoordinator.Live;
import restudio.resync.storage.AssetTransactionCoordinator.ProjectDelta;
import restudio.resync.storage.AssetTransactionCoordinator.Snapshot;
import restudio.resync.storage.ProjectMetadataLineage;
import restudio.resync.upgrade.AssetCoordinatorMigration;
import restudio.resync.upgrade.AssetCoordinatorMigration.FreshRootAuthority;
import restudio.resync.worldgen.WorldGenGeneratedRebuildRecipe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class FreshProjectMetadataBootstrap {
    private static final AssetKey LINEAGE_KEY = new AssetKey("project_metadata.lineage", "project");
    private static final String LINEAGE_PATH = ".durability/project-metadata-lineage.v1.json";
    private static final String RECIPE_PATH = ".durability/worldgen-generated-rebuild-recipe.v1";

    private FreshProjectMetadataBootstrap() {
    }

    static Admission preflight(Path coordinationRoot, Path activeRoot, AssetCoordinatorMigration.Result migration)
        throws IOException {
        Path requestedRoot = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        FreshRootAuthority fresh = Objects.requireNonNull(migration, "migration")
            .freshRootAuthority(coordinationRoot, requestedRoot).orElse(null);
        return preflight(requestedRoot, fresh);
    }

    static Admission preflight(Path activeRoot, FreshRootAuthority fresh) throws IOException {
        Path requestedRoot = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        Path root = requestedRoot.toRealPath();
        if (fresh != null && !fresh.activeRoot().equals(root)) {
            throw new IOException("Fresh project metadata authority is not bound to the active root");
        }
        return new Admission(root, fresh);
    }

    record Admission(Path activeRoot, FreshRootAuthority freshAuthority) {
        Admission {
            activeRoot = MigrationPaths.requirePath(activeRoot, "activeRoot");
        }

        void ensure(ServerIdentityStore identity, AssetTransactionCoordinator coordinator, Gson gson) throws IOException {
            ServerIdentityStore currentIdentity = Objects.requireNonNull(identity, "identity");
            AssetTransactionCoordinator currentCoordinator = Objects.requireNonNull(coordinator, "coordinator");
            Gson serializer = Objects.requireNonNull(gson, "gson");
            requireActiveBinding(activeRoot, currentIdentity, currentCoordinator);
            currentIdentity.healthCheck();

            Snapshot snapshot = currentCoordinator.read(current -> current);
            Path lineage = currentCoordinator.canonicalRoot().resolve(LINEAGE_PATH).toAbsolutePath().normalize();
            if (freshAuthority != null && initial(snapshot)) {
                if (Files.exists(lineage, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Fresh project metadata lineage has ambiguous existing evidence");
                }
                UUID mutationId = UUID.randomUUID();
                List<ProjectDelta> projectDeltas = new ArrayList<>(emptyIndexes());
                projectDeltas.add(ProjectDelta.set(List.of("serverId"),
                    new JsonPrimitive(currentIdentity.serverId().canonicalText())));
                AssetDelta lineageDelta = ProjectMetadataLineage.writer(currentCoordinator.canonicalRoot(), serializer)
                    .write(snapshot, projectDeltas, mutationId);
                if (lineageDelta == null) {
                    throw new IOException("Fresh project metadata lineage could not be created");
                }
                currentCoordinator.transact(new AssetTransactionCoordinator.TransactionRequest(
                    mutationId, snapshot.project(), List.of(lineageDelta), projectDeltas));
                snapshot = currentCoordinator.read(current -> current);
            }
            requireCanonicalPair(snapshot, currentIdentity.serverId().canonicalText(),
                currentCoordinator.canonicalRoot(), serializer);
            if (freshAuthority != null && emptyBootstrap(snapshot, currentCoordinator.canonicalRoot())) {
                UUID mutationId = UUID.randomUUID();
                List<ProjectDelta> projectDeltas = emptyIndexes();
                AssetDelta lineageDelta = ProjectMetadataLineage.writer(currentCoordinator.canonicalRoot(), serializer)
                    .write(snapshot, projectDeltas, mutationId);
                if (lineageDelta == null) throw new IOException("Fresh Project Index Lineage Could Not Be Created");
                currentCoordinator.transact(new AssetTransactionCoordinator.TransactionRequest(
                    mutationId, snapshot.project(), List.of(lineageDelta), projectDeltas), snapshot.rootSequence());
                requireCanonicalPair(currentCoordinator.read(current -> current), currentIdentity.serverId().canonicalText(),
                    currentCoordinator.canonicalRoot(), serializer);
            }
        }
    }

    private static List<ProjectDelta> emptyIndexes() {
        return List.of(ProjectDelta.set(List.of("resources"), new JsonArray()),
            ProjectDelta.set(List.of("folders"), new JsonArray()));
    }

    private static boolean emptyBootstrap(Snapshot snapshot, Path root) throws IOException {
        if (snapshot.project().revision() != 1L || !snapshot.blocked().isEmpty()
            || !snapshot.metadata().document().keySet().equals(Set.of("serverId"))) return false;
        Set<AssetKey> keys = snapshot.states().keySet();
        if ((!keys.equals(Set.of(LINEAGE_KEY)) && !keys.equals(Set.of(LINEAGE_KEY, WorldGenGeneratedRebuildRecipe.ASSET_KEY)))
            || snapshot.rootSequence() != keys.size()
            || snapshot.states().values().stream().anyMatch(state -> !(state instanceof Live live) || live.revision() != 1L)) {
            return false;
        }
        if (keys.contains(WorldGenGeneratedRebuildRecipe.ASSET_KEY)) {
            Path recipe = root.resolve(RECIPE_PATH);
            if (!snapshot.path(WorldGenGeneratedRebuildRecipe.ASSET_KEY).filter(recipe::equals).isPresent()
                || !WorldGenGeneratedRebuildRecipe.read(recipe).entries().isEmpty()) return false;
        }
        Set<Path> allowed = new HashSet<>(snapshot.paths().values());
        allowed.add(root.resolve("project.json"));
        allowed.add(root.resolve(".migrations/replacement-activation.record"));
        Set<Path> history = Set.of(root.resolve(".asset-coordinator"), root.resolve(".transactions"), root.resolve(".snapshots"));
        Set<Path> directories = Set.of(root, root.resolve(".durability"), root.resolve(".migrations"));
        class Inventory extends SimpleFileVisitor<Path> {
            private boolean empty = true;

            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (history.contains(directory)) return FileVisitResult.SKIP_SUBTREE;
                if (directories.contains(directory)) return FileVisitResult.CONTINUE;
                empty = false;
                return FileVisitResult.TERMINATE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && !attributes.isSymbolicLink() && allowed.contains(file)) {
                    return FileVisitResult.CONTINUE;
                }
                empty = false;
                return FileVisitResult.TERMINATE;
            }
        }
        Inventory inventory = new Inventory();
        Files.walkFileTree(root, inventory);
        return inventory.empty;
    }

    private static void requireActiveBinding(Path activeRoot, ServerIdentityStore identity,
                                             AssetTransactionCoordinator coordinator) throws IOException {
        Path identityRoot = Objects.requireNonNull(identity.path().getParent(), "identity root").toRealPath();
        Path assetsRoot = MigrationPaths.requireDirectory(activeRoot.resolve("assets"), "assetsRoot").toRealPath();
        if (!activeRoot.equals(identityRoot) || !assetsRoot.equals(coordinator.canonicalRoot())) {
            throw new IOException("Project metadata bootstrap is not bound to the active root and server identity");
        }
    }

    private static boolean initial(Snapshot snapshot) {
        return snapshot.rootSequence() == 0L && snapshot.project().revision() == 0L
            && snapshot.projectMutationId().isEmpty() && snapshot.metadata().canonicalJson().equals("{}")
            && snapshot.states().isEmpty() && snapshot.blocked().isEmpty();
    }

    private static void requireCanonicalPair(Snapshot snapshot, String serverId, Path assetsRoot, Gson gson)
        throws IOException {
        AssetProjectMetadata metadata = snapshot.metadata();
        if (snapshot.project().revision() < 1L || snapshot.projectMutationId().isEmpty()
            || !serverId.equals(serverId(metadata.document()))) {
            throw new IOException("Project metadata is not bound to the current server identity");
        }
        if (snapshot.state(LINEAGE_KEY).isEmpty()) {
            throw new IOException("Project metadata lineage is missing from the authoritative coordinator");
        }
        ProjectMetadataLineage.writer(assetsRoot, gson).write(snapshot, List.of(), UUID.randomUUID());
    }

    private static String serverId(JsonObject metadata) {
        JsonElement value = metadata.get("serverId");
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
            || !value.getAsJsonPrimitive().isString()) {
            return "";
        }
        return value.getAsString();
    }
}
