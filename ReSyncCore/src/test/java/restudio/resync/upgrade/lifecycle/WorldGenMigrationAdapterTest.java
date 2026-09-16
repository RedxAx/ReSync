package restudio.resync.upgrade.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter;

class WorldGenMigrationAdapterTest {
    @TempDir
    Path root;

    @Test
    void validatesTypedWorldGenBytesWithoutRewritingUnknownFields() throws Exception {
        String raw = "{\n  \"resourceType\": \"worldgen\",\n  \"id\": \"overworld\",\n  \"resourceRevision\": 7,\n  \"resourceMutationId\": \"legacy-mutation-7\",\n  \"futureField\": {\"keep\": true},\n  \"deleted\": false\n}\n";
        write("assets/WorldGen/overworld.json", raw);
        TypedLifecycleMigrationAdapter.Input input = input(file("assets/WorldGen/overworld.json", raw));

        TypedLifecycleMigrationAdapter.Adaptation first = new WorldGenMigrationAdapter().adapt(input);
        TypedLifecycleMigrationAdapter.Adaptation second = new WorldGenMigrationAdapter().adapt(input);

        assertEquals(List.of(new TypedLifecycleMigrationAdapter.Claim(
            "assets/WorldGen/overworld.json", ProductionPersistenceOwners.FLOW_ASSETS)), first.claims());
        assertTrue(first.changes().isEmpty());
        assertTrue(first.quarantineRecords().isEmpty());
        assertTrue(second.changes().isEmpty());
        assertTrue(second.quarantineRecords().isEmpty());
        assertEquals(raw, Files.readString(root.resolve("assets/WorldGen/overworld.json")));
    }

    @Test
    void rejectsIdentityAliasesCaseCollisionsAndDeletedLivePayloads() throws Exception {
        String conflict = "{\"resourceType\":\"worldgen\",\"id\":\"conflict\",\"assetRevision\":1,\"resourceRevision\":2,\"mutationId\":\"m\"}";
        String deleted = "{\"resourceType\":\"worldgen\",\"id\":\"deleted\",\"revision\":1,\"mutationId\":\"m\",\"deleted\":true}";
        String upper = "{\"resourceType\":\"worldgen\",\"id\":\"World\",\"revision\":1,\"mutationId\":\"m\"}";
        String lower = "{\"resourceType\":\"worldgen\",\"id\":\"world\",\"revision\":1,\"mutationId\":\"m\"}";
        write("assets/WorldGen/conflict.json", conflict);
        write("assets/WorldGen/deleted.json", deleted);
        write("assets/WorldGen/World.json", upper);
        write("assets/WorldGen/world.json", lower);

        TypedLifecycleMigrationAdapter.Adaptation result = new WorldGenMigrationAdapter().adapt(input(
            file("assets/WorldGen/conflict.json", conflict),
            file("assets/WorldGen/deleted.json", deleted),
            file("assets/WorldGen/World.json", upper),
            file("assets/WorldGen/world.json", lower)));

        assertTrue(result.changes().isEmpty());
        assertEquals(4, result.claims().size());
        assertEquals(4, result.quarantineRecords().size());
        assertTrue(result.quarantineRecords().stream().anyMatch(record -> record.code().equals(WorldGenMigrationAdapter.ID_COLLISION_CODE)));
        assertTrue(result.quarantineRecords().stream().anyMatch(record -> record.code().equals(WorldGenMigrationAdapter.INVALID_CODE)));
    }

    private TypedLifecycleMigrationAdapter.Input input(TypedLifecycleMigrationAdapter.SourceFile... files) {
        return new TypedLifecycleMigrationAdapter.Input(root, metadata(), "a".repeat(64), List.of(files));
    }

    private TypedLifecycleMigrationAdapter.SourceFile file(String path, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new TypedLifecycleMigrationAdapter.SourceFile(path, bytes.length, sha256(bytes), ProductionPersistenceOwners.FLOW_ASSETS);
    }

    private void write(String path, String text) throws Exception {
        Path target = root.resolve(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, text, StandardCharsets.UTF_8);
    }

    private SnapshotMetadata metadata() {
        return new SnapshotMetadata(1, "worldgen-adapter", Instant.parse("2026-01-01T00:00:00Z"),
            "build", "b".repeat(64), Map.of());
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
