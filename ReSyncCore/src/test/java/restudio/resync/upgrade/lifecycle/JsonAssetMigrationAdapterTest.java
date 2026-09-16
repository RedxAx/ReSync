package restudio.resync.upgrade.lifecycle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter;

class JsonAssetMigrationAdapterTest {
    private static final String MANIFEST = "a".repeat(64);
    private static final String MUTATION = "opaque-mutation-7";

    @TempDir
    Path temporary;

    @Test
    void derivesTheTenJsonFamiliesFromTheAuthoritativeProtocolFolders() throws IOException {
        List<String> paths = List.of(
            "assets/Customization/Chat/chat.json",
            "assets/Customization/MOTDs/motd.json",
            "assets/Customization/Messages/message.json",
            "assets/Content/Recipes/recipe.json",
            "assets/Text/Templates/template.json",
            "assets/Content/Advancements/advancement.json",
            "assets/Content/Dialogs/dialog.json",
            "assets/Content/Trades/trade.json",
            "assets/Content/NPCs/npc.json",
            "assets/Content/Loot Tables/loot.json");
        List<TypedLifecycleMigrationAdapter.SourceFile> files = new ArrayList<>();
        for (String path : paths) {
            String type = switch (path) {
                case "assets/Customization/Chat/chat.json" -> "chat";
                case "assets/Customization/MOTDs/motd.json" -> "motd_profile";
                case "assets/Customization/Messages/message.json" -> "message_rule";
                case "assets/Content/Recipes/recipe.json" -> "recipe_definition";
                case "assets/Text/Templates/template.json" -> "text_template";
                case "assets/Content/Advancements/advancement.json" -> "advancement_tree";
                case "assets/Content/Dialogs/dialog.json" -> "dialog";
                case "assets/Content/Trades/trade.json" -> "trade_profile";
                case "assets/Content/NPCs/npc.json" -> "npc_definition";
                case "assets/Content/Loot Tables/loot.json" -> "loot_table";
                default -> throw new AssertionError(path);
            };
            byte[] bytes = payload(type, path.substring(path.lastIndexOf('/') + 1, path.length() - 5),
                path.substring("assets/".length(), path.lastIndexOf('/')));
            write(path, bytes);
            files.add(source(path, bytes));
        }

        TypedLifecycleMigrationAdapter.Adaptation result = new JsonAssetMigrationAdapter().adapt(input(files));

        assertEquals(paths.stream().sorted().toList(), result.claims().stream().map(TypedLifecycleMigrationAdapter.Claim::relativePath).toList());
        assertTrue(result.claims().stream().allMatch(claim -> claim.owner().equals(ProductionPersistenceOwners.FLOW_ASSETS)));
        assertTrue(result.changes().isEmpty());
        assertTrue(result.quarantineRecords().isEmpty());
    }

    @Test
    void relocatesLegacyBytesWithoutRewritingUnknownFieldsAndIsZeroChangeAfterward() throws IOException {
        String legacyPath = "recipes/legacy.json";
        String folder = "Content/Recipes/Custom";
        byte[] bytes = payload("recipe_definition", "legacy", folder);
        write(legacyPath, bytes);

        JsonAssetMigrationAdapter adapter = new JsonAssetMigrationAdapter();
        TypedLifecycleMigrationAdapter.Adaptation first = adapter.adapt(input(List.of(source(legacyPath, bytes))));

        assertEquals(1, first.changes().size());
        TypedLifecycleMigrationAdapter.Change change = first.changes().getFirst();
        assertEquals("assets/Content/Recipes/Custom/legacy.json", change.targetPath());
        assertArrayEquals(bytes, change.targetBytes());
        assertTrue(first.quarantineRecords().isEmpty());

        write(change.targetPath(), change.targetBytes());
        TypedLifecycleMigrationAdapter.Adaptation second = adapter.adapt(input(List.of(source(change.targetPath(), bytes))));

        assertTrue(second.changes().isEmpty());
        assertTrue(second.quarantineRecords().isEmpty());
        assertEquals(first.claims().getFirst().owner(), second.claims().getFirst().owner());
    }

    @Test
    void rejectsCaseAliasesAndCanonicalPathConflicts() throws IOException {
        String firstPath = "assets/Content/Recipes/Alpha.json";
        String secondPath = "assets/content/recipes/alpha.json";
        byte[] first = payload("recipe_definition", "Alpha", "Content/Recipes");
        byte[] second = payload("recipe_definition", "alpha", "Content/Recipes");
        write(firstPath, first);
        write(secondPath, second);

        TypedLifecycleMigrationAdapter.Adaptation result = new JsonAssetMigrationAdapter()
            .adapt(input(List.of(source(firstPath, first), source(secondPath, second))));

        assertTrue(result.changes().isEmpty());
        assertEquals(2, result.quarantineRecords().size());
        assertTrue(result.quarantineRecords().stream().anyMatch(record -> record.code().equals(JsonAssetMigrationAdapter.CONFLICT_CODE)));
        assertTrue(result.quarantineRecords().stream().anyMatch(record -> record.code().equals(JsonAssetMigrationAdapter.INVALID_CODE)));
    }

    @Test
    void rejectsContradictoryLineageWithoutDroppingOpaquePayloadFields() throws IOException {
        String path = "assets/Content/Dialogs/dialog.json";
        byte[] bytes = "{\"resourceType\":\"dialog\",\"id\":\"dialog\",\"assetRevision\":2,\"revision\":3,\"assetMutationId\":\"opaque\",\"future\":{\"value\":true}}"
            .getBytes(StandardCharsets.UTF_8);
        write(path, bytes);

        TypedLifecycleMigrationAdapter.Adaptation result = new JsonAssetMigrationAdapter()
            .adapt(input(List.of(source(path, bytes))));

        assertTrue(result.changes().isEmpty());
        assertEquals(1, result.quarantineRecords().size());
        assertEquals(JsonAssetMigrationAdapter.INVALID_CODE, result.quarantineRecords().getFirst().code());
        assertEquals(sha256(bytes), result.quarantineRecords().getFirst().sourceHash());
    }

    @Test
    void keepsAnEqualOrNewerTombstoneAuthoritative() throws IOException {
        String livePath = "assets/Content/NPCs/guide.json";
        String tombstonePath = "assets/.tombstones/npc_definition/guide.json";
        byte[] live = payload("npc_definition", "guide", "Content/NPCs");
        byte[] tombstone = "{\"resourceType\":\"npc_definition\",\"id\":\"guide\",\"assetRevision\":8,\"assetMutationId\":\"delete-8\",\"deleted\":true}"
            .getBytes(StandardCharsets.UTF_8);
        write(livePath, live);
        write(tombstonePath, tombstone);

        TypedLifecycleMigrationAdapter.Adaptation result = new JsonAssetMigrationAdapter()
            .adapt(input(List.of(source(livePath, live), source(tombstonePath, tombstone))));

        assertEquals(List.of(livePath), result.claims().stream().map(TypedLifecycleMigrationAdapter.Claim::relativePath).toList());
        assertTrue(result.changes().isEmpty());
        assertEquals(1, result.quarantineRecords().size());
        assertEquals(JsonAssetMigrationAdapter.TOMBSTONE_CONFLICT_CODE, result.quarantineRecords().getFirst().code());
    }

    private TypedLifecycleMigrationAdapter.Input input(List<TypedLifecycleMigrationAdapter.SourceFile> files) {
        return new TypedLifecycleMigrationAdapter.Input(temporary, SnapshotMetadata.preflight(), MANIFEST, files);
    }

    private TypedLifecycleMigrationAdapter.SourceFile source(String relativePath, byte[] bytes) {
        return new TypedLifecycleMigrationAdapter.SourceFile(relativePath, bytes.length, sha256(bytes),
            ProductionPersistenceOwners.FLOW_ASSETS);
    }

    private void write(String relativePath, byte[] bytes) throws IOException {
        Path target = temporary.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private static byte[] payload(String type, String id, String folder) {
        return ("{\"resourceType\":\"" + type + "\",\"id\":\"" + id + "\",\"folder\":\"" + folder
            + "\",\"assetRevision\":7,\"assetMutationId\":\"" + MUTATION + "\",\"unknown\":{\"keep\":true}}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
