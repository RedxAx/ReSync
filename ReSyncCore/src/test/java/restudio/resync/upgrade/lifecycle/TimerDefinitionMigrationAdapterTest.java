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

class TimerDefinitionMigrationAdapterTest {
    @TempDir
    Path root;

    @Test
    void validatesTimerContractAndKeepsTheExactSecondRunStable() throws Exception {
        String raw = "{\n  \"resourceType\": \"timer_definition\",\n  \"id\": \"round_timer\",\n  \"assetRevision\": 4,\n  \"assetMutationId\": \"opaque-mutation-4\",\n  \"name\": \"Round Timer\",\n  \"scope\": \"server\",\n  \"persistent\": true,\n  \"defaultDuration\": 2.5,\n  \"defaultUnit\": \"seconds\",\n  \"tickInterval\": 0.5,\n  \"unknown\": [1, 2, 3]\n}\n";
        write("assets/Automation/Timers/round_timer.json", raw);
        TypedLifecycleMigrationAdapter.Input input = input(file("assets/Automation/Timers/round_timer.json", raw));

        TypedLifecycleMigrationAdapter.Adaptation first = new TimerDefinitionMigrationAdapter().adapt(input);
        TypedLifecycleMigrationAdapter.Adaptation second = new TimerDefinitionMigrationAdapter().adapt(input);

        assertEquals(List.of(new TypedLifecycleMigrationAdapter.Claim(
            "assets/Automation/Timers/round_timer.json", ProductionPersistenceOwners.FLOW_ASSETS)), first.claims());
        assertTrue(first.changes().isEmpty());
        assertTrue(first.quarantineRecords().isEmpty());
        assertTrue(second.changes().isEmpty());
        assertTrue(second.quarantineRecords().isEmpty());
        assertEquals(raw, Files.readString(root.resolve("assets/Automation/Timers/round_timer.json")));
    }

    @Test
    void rejectsConflictingAliasesUnsafeIdentityAndDeletedPayloads() throws Exception {
        String conflict = "{\"resourceType\":\"timer_definition\",\"id\":\"conflict\",\"revision\":1,\"assetRevision\":2,\"mutationId\":\"m\"}";
        String unsafe = "{\"resourceType\":\"timer_definition\",\"id\":\"unsafe/id\",\"revision\":1,\"mutationId\":\"m\"}";
        String deleted = "{\"resourceType\":\"timer_definition\",\"id\":\"deleted\",\"revision\":1,\"mutationId\":\"m\",\"deleted\":true}";
        write("assets/Automation/Timers/conflict.json", conflict);
        write("assets/Automation/Timers/unsafe-id.json", unsafe);
        write("assets/Automation/Timers/deleted.json", deleted);

        TypedLifecycleMigrationAdapter.Adaptation result = new TimerDefinitionMigrationAdapter().adapt(input(
            file("assets/Automation/Timers/conflict.json", conflict),
            file("assets/Automation/Timers/unsafe-id.json", unsafe),
            file("assets/Automation/Timers/deleted.json", deleted)));

        assertTrue(result.changes().isEmpty());
        assertEquals(3, result.claims().size());
        assertEquals(3, result.quarantineRecords().size());
        assertTrue(result.quarantineRecords().stream().allMatch(record -> record.sourceHash().length() == 64));
    }

    private TypedLifecycleMigrationAdapter.Input input(TypedLifecycleMigrationAdapter.SourceFile... files) {
        return new TypedLifecycleMigrationAdapter.Input(root, metadata(), "c".repeat(64), List.of(files));
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
        return new SnapshotMetadata(1, "timer-adapter", Instant.parse("2026-01-01T00:00:00Z"),
            "build", "d".repeat(64), Map.of());
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
