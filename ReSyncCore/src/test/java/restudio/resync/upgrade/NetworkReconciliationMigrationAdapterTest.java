package restudio.resync.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.canonical.JsonValue.JsonArray;
import restudio.resync.contract.canonical.JsonValue.JsonObject;
import restudio.resync.contract.canonical.JsonValue.JsonString;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotService;

class NetworkReconciliationMigrationAdapterTest {
    private static final String EMPTY_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String PAYLOAD_HASH = "a".repeat(64);

    @TempDir
    Path temporary;

    @Test
    void migratesTypedMutationStateAndPreservesOpaqueFields() throws IOException {
        String manifest = """
            {
              "version": 1,
              "futureManifest": {"retained": true},
              "entries": {
                "flow\\u0000welcome": {
                  "type": "FLOW",
                  "resourceId": "welcome",
                  "revision": 4,
                  "payloadHash": "%s",
                  "deleted": false,
                  "updatedAt": 25,
                  "futureEntry": ["opaque"]
                }
              }
            }
            """.formatted(PAYLOAD_HASH);
        TypedLifecycleMigrationAdapter.Adaptation result = adapt(manifest);

        assertEquals(1, result.changes().size());
        assertTrue(result.quarantineRecords().isEmpty());
        JsonObject migrated = object(JsonValue.parse(result.changes().getFirst().targetBytes()));
        assertEquals(2L, number(migrated.value("version")));
        assertTrue(migrated.contains("futureManifest"));
        JsonObject resource = object(array(migrated.value("resources")).values().getFirst());
        JsonObject key = object(resource.value("key"));
        assertEquals("welcome", string(key.value("id")));
        JsonObject type = object(key.value("type"));
        assertEquals("resync", string(type.value("ownerId")));
        assertEquals("flow", string(type.value("localId")));
        assertEquals(4L, number(resource.value("revision")));
        assertTrue(string(resource.value("mutationId")).startsWith("migration-"));
        assertEquals(PAYLOAD_HASH, string(resource.value("payloadHash")));
        assertFalse(bool(resource.value("tombstone")));
        assertTrue(resource.contains("futureEntry"));
    }

    @Test
    void quarantinesEqualRevisionConflictsDeterministically() throws IOException {
        String manifest = """
            {
              "version": 1,
              "entries": {
                "remote": {"type":"flow","resourceId":"shared","revision":8,"mutationId":"remote-save","payloadHash":"%s","deleted":false,"updatedAt":10},
                "local": {"type":"flow","resourceId":"shared","revision":8,"mutationId":"local-delete","payloadHash":"%s","deleted":true,"updatedAt":11}
              }
            }
            """.formatted(PAYLOAD_HASH, EMPTY_HASH);
        TypedLifecycleMigrationAdapter.Adaptation first = adapt(manifest);
        TypedLifecycleMigrationAdapter.Adaptation second = adapt(manifest);

        assertEquals(2, first.quarantineRecords().size());
        assertEquals(first.quarantineRecords(), second.quarantineRecords());
        assertTrue(first.changes().isEmpty());
        assertTrue(first.quarantineRecords().stream().allMatch(record -> record.code().equals("NETWORK.EQUAL_REVISION_CONFLICT")));
    }

    @Test
    void quarantinesEqualRevisionCompleteStateDifferences() throws IOException {
        String manifest = """
            {
              "version": 1,
              "entries": [
                {"type":"flow","resourceId":"shared","revision":8,"mutationId":"same-save","payloadHash":"%s","deleted":false,"updatedAt":10,"future":"one"},
                {"type":"flow","resourceId":"shared","revision":8,"mutationId":"same-save","payloadHash":"%s","deleted":false,"updatedAt":11,"future":"two"}
              ]
            }
            """.formatted(PAYLOAD_HASH, PAYLOAD_HASH);
        TypedLifecycleMigrationAdapter.Adaptation result = adapt(manifest);

        assertEquals(2, result.quarantineRecords().size());
        assertTrue(result.changes().isEmpty());
        assertTrue(result.quarantineRecords().stream().allMatch(record -> record.code().equals("NETWORK.EQUAL_REVISION_CONFLICT")));
    }

    @Test
    void newerTombstoneWinsOverUnchangedRemotePayload() throws IOException {
        String manifest = """
            {
              "version": 1,
              "entries": [
                {"type":"function","resourceId":"removed","revision":12,"mutationId":"remote-save","payloadHash":"%s","deleted":false,"updatedAt":20,"source":"remote"},
                {"type":"function","resourceId":"removed","revision":13,"mutationId":"local-delete","payloadHash":"%s","deleted":true,"updatedAt":21,"source":"local"}
              ]
            }
            """.formatted(PAYLOAD_HASH, EMPTY_HASH);
        TypedLifecycleMigrationAdapter.Adaptation result = adapt(manifest);

        assertTrue(result.quarantineRecords().isEmpty());
        JsonObject resource = object(array(object(JsonValue.parse(result.changes().getFirst().targetBytes())).value("resources")).values().getFirst());
        assertEquals(13L, number(resource.value("revision")));
        assertEquals("local-delete", string(resource.value("mutationId")));
        assertTrue(bool(resource.value("tombstone")));
        assertEquals("local", string(resource.value("source")));
    }

    @Test
    void quarantinesContradictoryDeletionAliasesWithoutChanges() throws IOException {
        String manifest = """
            {"version":1,"entries":[{"type":"flow","resourceId":"contradictory","revision":3,"mutationId":"mutation","payloadHash":"%s","tombstone":true,"deleted":false,"updatedAt":1}]}
            """.formatted(EMPTY_HASH);
        TypedLifecycleMigrationAdapter.Adaptation result = adapt(manifest);

        assertTrue(result.changes().isEmpty());
        assertEquals(1, result.quarantineRecords().size());
        assertEquals(NetworkReconciliationMigrationAdapter.MANIFEST_PATH, result.quarantineRecords().getFirst().sourceLocation());
        assertEquals("NETWORK.MANIFEST_INVALID", result.quarantineRecords().getFirst().code());
    }

    @Test
    void quarantinesTombstoneWithNonEmptyPayloadHash() throws IOException {
        String manifest = """
            {"version":1,"entries":[{"type":"flow","resourceId":"deleted","revision":3,"mutationId":"delete","payloadHash":"%s","deleted":true,"updatedAt":1}]}
            """.formatted(PAYLOAD_HASH);
        TypedLifecycleMigrationAdapter.Adaptation result = adapt(manifest);

        assertTrue(result.changes().isEmpty());
        assertEquals(1, result.quarantineRecords().size());
        assertTrue(result.quarantineRecords().getFirst().reason().contains("canonical empty payload"));
    }

    @Test
    void preservesUnknownFutureStateWithoutRewriting() throws IOException {
        String manifest = """
            {"version":7,"future":{"wire":"opaque"},"entries":[{"shape":"unknown"}]}
            """;
        TypedLifecycleMigrationAdapter.Adaptation result = adapt(manifest);

        assertEquals(1, result.claims().size());
        assertTrue(result.changes().isEmpty());
        assertTrue(result.quarantineRecords().isEmpty());
        assertEquals(manifest, Files.readString(temporary.resolve(NetworkReconciliationMigrationAdapter.MANIFEST_PATH)));
    }

    @Test
    void secondRunProducesZeroChanges() throws IOException {
        String manifest = """
            {"version":1,"entries":{"flow\\u0000stable":{"type":"flow","resourceId":"stable","revision":3,"payloadHash":"%s","deleted":false,"updatedAt":1}}}
            """.formatted(PAYLOAD_HASH);
        TypedLifecycleMigrationAdapter.Adaptation first = adapt(manifest);
        Files.write(temporary.resolve(NetworkReconciliationMigrationAdapter.MANIFEST_PATH), first.changes().getFirst().targetBytes());
        TypedLifecycleMigrationAdapter.Adaptation second = adapter().adapt(input());

        assertTrue(second.changes().isEmpty());
        assertTrue(second.quarantineRecords().isEmpty());
    }

    @Test
    void lifecyclePlanStagesBothOwnedNetworkSourcesAndResnapshotsWithZeroChanges() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("lifecycle-source"));
        Path network = Files.createDirectory(source.resolve("network"));
        String manifest = """
            {
              "version": 1,
              "entries": [
                {"type":"flow","resourceId":"stable","revision":3,"mutationId":"same-mutation","payloadHash":"%s","deleted":false,"updatedAt":1,"future":"retained"},
                {"type":"flow","resourceId":"stable","revision":3,"mutationId":"same-mutation","payloadHash":"%s","deleted":false,"updatedAt":1,"future":"retained"}
              ]
            }
            """.formatted(PAYLOAD_HASH, PAYLOAD_HASH);
        String networkState = "{\"version\":9,\"futureState\":{\"retained\":true}}";
        Files.writeString(network.resolve("resource-manifest.json"), manifest);
        Files.writeString(network.resolve("network-state.json"), networkState);
        Snapshot snapshot = snapshot(source, "network-source", 1, "legacy-build", temporary.resolve("network-snapshot"));
        ReSyncTypedLifecycleUpgrade upgrade = new ReSyncTypedLifecycleUpgrade(List.of(adapter()));

        UpgradeProposal proposal = upgrade.plan(snapshot, window(1, "legacy-build", 2, "replacement-build"));

        assertTrue(proposal.quarantineReport().records().isEmpty());
        assertEquals(1, proposal.plan().operations().size());
        assertEquals(NetworkReconciliationMigrationAdapter.MANIFEST_PATH, proposal.plan().operations().getFirst().sourcePath());
        TypedLifecycleMigrationAdapter competing = new TypedLifecycleMigrationAdapter() {
            @Override
            public String adapterId() {
                return "network-state-competitor";
            }

            @Override
            public Adaptation adapt(Input input) {
                return input.file(NetworkReconciliationMigrationAdapter.NETWORK_STATE_PATH)
                    .map(file -> Adaptation.claimed(List.of(new Claim(file.relativePath(), ProductionPersistenceOwners.NETWORK))))
                    .orElseGet(() -> Adaptation.claimed(List.of()));
            }
        };
        UpgradeProposal ambiguous = new ReSyncTypedLifecycleUpgrade(List.of(adapter(), competing))
            .plan(snapshot, window(1, "legacy-build", 2, "replacement-build"));
        assertTrue(ambiguous.plan().operations().isEmpty());
        assertTrue(ambiguous.quarantineReport().records().stream().anyMatch(record -> record.sourceLocation().equals(NetworkReconciliationMigrationAdapter.NETWORK_STATE_PATH) && record.code().equals("MIGRATION.LIFECYCLE_OWNER_AMBIGUOUS")));
        var staged = upgrade.stage(snapshot.root(), temporary.resolve("network-replacement"), proposal.plan());
        assertEquals(networkState, Files.readString(staged.root().resolve(NetworkReconciliationMigrationAdapter.NETWORK_STATE_PATH)));
        JsonObject migrated = object(JsonValue.parse(Files.readAllBytes(staged.root().resolve(NetworkReconciliationMigrationAdapter.MANIFEST_PATH))));
        assertEquals(1, array(migrated.value("resources")).values().size());
        Snapshot canonical = snapshot(staged.root(), "network-canonical", 2, "replacement-build", temporary.resolve("network-canonical-snapshot"));

        UpgradeProposal second = upgrade.plan(canonical, window(2, "replacement-build", 3, "next-build"));

        assertTrue(second.plan().operations().isEmpty());
        assertTrue(second.quarantineReport().records().isEmpty());
    }

    private TypedLifecycleMigrationAdapter.Adaptation adapt(String manifest) throws IOException {
        Path file = temporary.resolve(NetworkReconciliationMigrationAdapter.MANIFEST_PATH);
        Files.createDirectories(file.getParent());
        Files.writeString(file, manifest);
        return adapter().adapt(input());
    }

    private NetworkReconciliationMigrationAdapter adapter() {
        return new NetworkReconciliationMigrationAdapter();
    }

    private TypedLifecycleMigrationAdapter.Input input() throws IOException {
        Path file = temporary.resolve(NetworkReconciliationMigrationAdapter.MANIFEST_PATH);
        byte[] bytes = Files.readAllBytes(file);
        TypedLifecycleMigrationAdapter.SourceFile source = new TypedLifecycleMigrationAdapter.SourceFile(NetworkReconciliationMigrationAdapter.MANIFEST_PATH, bytes.length, sha256(bytes), ProductionPersistenceOwners.NETWORK);
        return new TypedLifecycleMigrationAdapter.Input(temporary, new SnapshotMetadata(1, "network-test", Instant.EPOCH, "legacy", "b".repeat(64), Map.of()), "c".repeat(64), List.of(source));
    }

    private Snapshot snapshot(Path source, String snapshotId, int format, String build, Path staging) throws IOException {
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return ProductionPersistenceOwners.NETWORK;
            }

            @Override
            public Path root() {
                return source;
            }
        });
        SnapshotMetadata metadata = new SnapshotMetadata(format, snapshotId, Instant.parse("2026-01-01T00:00:00Z"), build, "b".repeat(64), Map.of());
        return new SnapshotService(new MigrationFence()).create(source, staging, metadata, participants);
    }

    private UpgradeSourceWindow window(int sourceFormat, String sourceBuild, int targetFormat, String targetBuild) {
        return new UpgradeSourceWindow(ReplacementUpgrader.VERSION, sourceFormat, sourceBuild, targetFormat, targetBuild);
    }

    private JsonObject object(JsonValue value) {
        return (JsonObject) value;
    }

    private JsonArray array(JsonValue value) {
        return (JsonArray) value;
    }

    private String string(JsonValue value) {
        return ((JsonString) value).value();
    }

    private long number(JsonValue value) {
        return ((JsonValue.JsonNumber) value).value().longValueExact();
    }

    private boolean bool(JsonValue value) {
        return ((JsonValue.JsonBoolean) value).value();
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
