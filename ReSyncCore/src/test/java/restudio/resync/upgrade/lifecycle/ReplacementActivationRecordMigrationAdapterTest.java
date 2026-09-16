package restudio.resync.upgrade.lifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.ReplacementActivationRecord;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.upgrade.ReSyncTypedLifecycleUpgrade;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplacementActivationRecordMigrationAdapterTest {
    @TempDir
    Path temporary;

    @Test
    void claimsAndValidatesTheCanonicalControlRecord() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        ReplacementActivationRecord.write(root, values());
        byte[] bytes = Files.readAllBytes(ReplacementActivationRecord.path(root));
        ReplacementActivationRecordMigrationAdapter adapter = new ReplacementActivationRecordMigrationAdapter();

        TypedLifecycleMigrationAdapter.Adaptation result = adapter.adapt(input(root, bytes));

        assertEquals(List.of(new TypedLifecycleMigrationAdapter.Claim(
            ReplacementActivationRecord.RECORD_FILE, ProductionPersistenceOwners.FLOW_ASSETS)), result.claims());
        assertTrue(result.changes().isEmpty());
        assertTrue(result.quarantineRecords().isEmpty());
    }

    @Test
    void failsClosedWhenTheControlRecordIsInvalid() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("invalid"));
        ReplacementActivationRecord.write(root, values());
        Path record = ReplacementActivationRecord.path(root);
        Files.writeString(record, Files.readString(record).replace("state=ACTIVE", "state=INVALID"));

        assertThrows(MigrationException.class, () -> new ReplacementActivationRecordMigrationAdapter().adapt(input(root,
            Files.readAllBytes(record))));
    }

    @Test
    void productionRegistersTheControlRecordAuthority() {
        assertTrue(ReSyncTypedLifecycleUpgrade.production().adapters().stream()
            .anyMatch(adapter -> adapter instanceof ReplacementActivationRecordMigrationAdapter));
    }

    private TypedLifecycleMigrationAdapter.Input input(Path root, byte[] bytes) {
        return new TypedLifecycleMigrationAdapter.Input(root,
            new SnapshotMetadata(1, "replacement-record-test", Instant.EPOCH, "test-build", "a".repeat(64), Map.of()),
            "b".repeat(64), List.of(new TypedLifecycleMigrationAdapter.SourceFile(
                ReplacementActivationRecord.RECORD_FILE, bytes.length, sha256(bytes), ProductionPersistenceOwners.FLOW_ASSETS)));
    }

    private ReplacementActivationRecord.Values values() {
        return new ReplacementActivationRecord.Values(7, "c".repeat(64), 3, "d".repeat(64), 2, "e".repeat(64));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
