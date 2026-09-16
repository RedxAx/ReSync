package restudio.resync.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagedFlowFileMigrationContractTest {
    private static final ManagedFlowFileMigrationContract.SourceIdentity SOURCE =
        new ManagedFlowFileMigrationContract.SourceIdentity("snapshot-1", "install-1");

    @Test
    void zeroEntryManifestIsCanonicalAndRoundTrips() {
        ManagedFlowFileLegacyOwnershipManifest manifest =
            new ManagedFlowFileLegacyOwnershipManifest(SOURCE, List.of());

        ManagedFlowFileLegacyOwnershipManifest decoded =
            ManagedFlowFileLegacyOwnershipManifest.read(manifest.canonicalBytes());

        assertEquals(0, decoded.entryCount());
        assertEquals(manifest.manifestHash(), decoded.manifestHash());
        assertEquals(manifest.canonicalText(), decoded.canonicalText());
    }

    @Test
    void rejectsPathNormalizationCollisionsAndReservedEntries() {
        assertThrows(IllegalArgumentException.class,
            () -> new ManagedFlowFileMigrationContract.LegacyEntry("a/../b",
                ManagedFlowFileMigrationContract.EntryKind.FILE, 1, digest("b")));
        assertThrows(IllegalArgumentException.class,
            () -> new ManagedFlowFileLegacyOwnershipManifest(SOURCE, List.of(
                entry("A.txt", "a"), entry("a.TXT", "b"))));
        assertThrows(IllegalArgumentException.class,
            () -> new ManagedFlowFileLegacyOwnershipManifest(SOURCE, List.of(
                entry("folder", "a"), entry("folder/value", "b"))));
        assertThrows(IllegalArgumentException.class,
            () -> new ManagedFlowFileMigrationContract.LegacyEntry("managed-files.db",
                ManagedFlowFileMigrationContract.EntryKind.FILE, 1, digest("b")));
    }

    @Test
    void rejectsTamperedAndNonCanonicalManifestBytes() {
        ManagedFlowFileLegacyOwnershipManifest manifest =
            new ManagedFlowFileLegacyOwnershipManifest(SOURCE, List.of(entry("value.txt", "value")));
        byte[] tampered = manifest.canonicalBytes().clone();
        tampered[tampered.length - 2] = (byte) (tampered[tampered.length - 2] == 'a' ? 'b' : 'a');
        assertThrows(IllegalArgumentException.class, () -> ManagedFlowFileLegacyOwnershipManifest.read(tampered));
        String nonCanonical = "{\"entries\":[],\"formatVersion\":1,\"owner\":\"resync.flow.files\","
            + "\"schema\":\"resync-managed-flow-files-legacy-ownership\",\"sourceInstallId\":\"install-1\","
            + "\"sourceSnapshotId\":\"snapshot-1\",\"manifestHash\":\""
            + manifest.manifestHash() + "\"}";
        assertThrows(IllegalArgumentException.class, () -> ManagedFlowFileLegacyOwnershipManifest.read(nonCanonical));
    }

    @Test
    void completionBindsManifestDatabaseAndContentIdentity() {
        ManagedFlowFileLegacyOwnershipManifest manifest =
            new ManagedFlowFileLegacyOwnershipManifest(SOURCE, List.of(entry("value.txt", "value")));
        ManagedFlowFileMigrationContract.DatabaseIdentity database =
            ManagedFlowFileMigrationContract.DatabaseIdentity.current();
        ManagedFlowFileMigrationCompletion completion = new ManagedFlowFileMigrationCompletion(
            1, "migration-1", manifest.manifestHash(), database, 1,
            ManagedFlowFileMigrationContract.canonicalLogicalContentHash(List.of(
                new ManagedFlowFileMigrationContract.LogicalEntry("value.txt",
                    ManagedFlowFileMigrationContract.EntryKind.FILE, "value".getBytes(StandardCharsets.UTF_8)))),
            digest("database"), SOURCE, "archive-1", "target-install");
        ManagedFlowFileMigrationCompletion decoded =
            ManagedFlowFileMigrationCompletion.read(completion.canonicalBytes());
        ManagedFlowFileMigrationContract.StoreMetadata metadata = ManagedFlowFileMigrationContract.StoreMetadata.migratedPending(
            "target-install", SOURCE, "archive-1", "migration-1");

        assertEquals(completion.completionHash(), decoded.completionHash());
        assertEquals(completion.canonicalText(), decoded.canonicalText());
        assertFalse(completion.matches(manifest, ManagedFlowFileMigrationContract.StoreMetadata.fresh("target-install")));
        assertEquals(true, completion.matchesPending(manifest, metadata));
        ManagedFlowFileMigrationContract.StoreMetadata admitted = ManagedFlowFileMigrationContract.StoreMetadata.migratedAdmitted(
            "target-install", SOURCE, "archive-1", "migration-1", completion.completionAuthorityHash(),
            completion.completionHash());
        assertEquals(true, completion.matchesAdmitted(manifest, admitted));
    }

    @Test
    void logicalContentHashIsOrderIndependentButContentSensitive() {
        ManagedFlowFileMigrationContract.LogicalEntry first = new ManagedFlowFileMigrationContract.LogicalEntry(
            "a.txt", ManagedFlowFileMigrationContract.EntryKind.FILE, "a".getBytes(StandardCharsets.UTF_8));
        ManagedFlowFileMigrationContract.LogicalEntry second = new ManagedFlowFileMigrationContract.LogicalEntry(
            "b.txt", ManagedFlowFileMigrationContract.EntryKind.FILE, "b".getBytes(StandardCharsets.UTF_8));

        String ordered = ManagedFlowFileMigrationContract.canonicalLogicalContentHash(List.of(first, second));
        String reversed = ManagedFlowFileMigrationContract.canonicalLogicalContentHash(List.of(second, first));
        String changed = ManagedFlowFileMigrationContract.canonicalLogicalContentHash(List.of(first,
            new ManagedFlowFileMigrationContract.LogicalEntry("b.txt", ManagedFlowFileMigrationContract.EntryKind.FILE,
                "changed".getBytes(StandardCharsets.UTF_8))));

        assertEquals(ordered, reversed);
        assertFalse(ordered.equals(changed));
    }

    private static ManagedFlowFileMigrationContract.LegacyEntry entry(String path, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new ManagedFlowFileMigrationContract.LegacyEntry(path,
            ManagedFlowFileMigrationContract.EntryKind.FILE, bytes.length,
            ManagedFlowFileMigrationContract.sha256(bytes));
    }

    private static String digest(String value) {
        return ManagedFlowFileMigrationContract.sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}
