package restudio.resync.upgrade.lifecycle;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.storage.CoreGraphAssetCodec;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotService;
import restudio.resync.migration.StagedMigration;
import restudio.resync.upgrade.ReSyncTypedLifecycleUpgrade;
import restudio.resync.upgrade.ReplacementUpgrader;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter.Adaptation;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter.Change;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter.Input;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter.SourceFile;
import restudio.resync.upgrade.UpgradeProposal;
import restudio.resync.upgrade.UpgradeSourceWindow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TombstoneMigrationAdapterTest {
    private static final String MANIFEST_HASH = "1111111111111111111111111111111111111111111111111111111111111111";
    private final TombstoneMigrationAdapter adapter = new TombstoneMigrationAdapter();

    @TempDir
    Path root;

    @Test
    void canonicalizesTypedTombstoneAndPreservesUnknownData() throws IOException {
        SourceFile source = source("assets/.tombstones/flow/deleted-flow.json",
            "{\"future\":{\"mode\":\"opaque\"},\"mutationId\":\"mutation-delete-5\",\"revision\":5,\"id\":\"deleted-flow\",\"type\":\"flow\"}");

        Adaptation result = adapter.adapt(input(List.of(source)));

        assertEquals(List.of(source.relativePath()), result.claims().stream().map(claim -> claim.relativePath()).toList());
        assertEquals(1, result.changes().size());
        Map<String, Object> canonical = object(CanonicalJson.parse(result.changes().getFirst().targetBytes()));
        assertEquals(Boolean.TRUE, canonical.get("deleted"));
        assertEquals("opaque", object(canonical.get("future")).get("mode"));
        assertEquals("mutation-delete-5", canonical.get("mutationId"));
        assertEquals("assets/.tombstones/flow/deleted-flow.json", result.changes().getFirst().targetPath());
        assertTrue(result.quarantineRecords().isEmpty());

        byte[] applied = result.changes().getFirst().targetBytes();
        Files.write(root.resolve(source.relativePath()), applied);
        SourceFile migrated = new SourceFile(source.relativePath(), applied.length, sha256(applied), source.owner());
        assertTrue(adapter.adapt(input(List.of(migrated))).changes().isEmpty());
    }

    @Test
    void selectsNewestDuplicateDeterministicallyAndQuarantinesTheLoser() throws IOException {
        SourceFile old = source("legacy/assets/.tombstones/flow/shared.json",
            "{\"type\":\"flow\",\"id\":\"shared\",\"revision\":4,\"mutationId\":\"mutation-4\",\"deleted\":true}");
        SourceFile newest = source("assets/.tombstones/flow/shared.json",
            "{\"deleted\":true,\"future\":7,\"id\":\"shared\",\"mutationId\":\"mutation-6\",\"revision\":6,\"type\":\"flow\"}");

        Adaptation result = adapter.adapt(input(List.of(old, newest)));

        assertEquals(2, result.claims().size());
        assertTrue(result.changes().isEmpty());
        assertEquals("MIGRATION.TOMBSTONE_DUPLICATE", result.quarantineRecords().getFirst().code());
    }

    @Test
    void quarantinesPathIdentityAndMutationInvariantFailures() throws IOException {
        SourceFile mismatch = source("assets/.tombstones/function/shared.json",
            "{\"type\":\"flow\",\"id\":\"shared\",\"revision\":3,\"mutationId\":\"mutation-3\",\"deleted\":true}");
        SourceFile invalid = source("assets/.tombstones/flow/invalid.json",
            "{\"type\":\"flow\",\"id\":\"invalid\",\"revision\":0,\"mutationId\":\"\",\"deleted\":false}");
        SourceFile malformedPath = source("assets/.tombstones/flow/nested/invalid.json",
            "{\"type\":\"flow\",\"id\":\"invalid\",\"revision\":1,\"mutationId\":\"mutation-1\",\"deleted\":true}");

        Adaptation result = adapter.adapt(input(List.of(mismatch, invalid, malformedPath)));

        assertEquals(3, result.claims().size());
        assertTrue(result.changes().isEmpty());
        assertEquals(List.of("MIGRATION.TOMBSTONE_INVALID", "MIGRATION.TOMBSTONE_INVALID", "MIGRATION.TOMBSTONE_PATH_INVALID"),
            result.quarantineRecords().stream().map(record -> record.code()).sorted().toList());
    }

    @Test
    void authoritativeTombstoneCannotBeDefeatedByUnchangedPayloadAndSecondRunMakesNoChange() throws IOException {
        String tombstoneValue = "{\"deleted\":true,\"id\":\"guarded\",\"mutationId\":\"delete-8\",\"revision\":8,\"type\":\"flow\"}";
        SourceFile tombstone = source("assets/.tombstones/flow/guarded.json", tombstoneValue);
        SourceFile payload = source("assets/Blueprints/Flows/guarded.json",
            "{\"type\":\"flow\",\"id\":\"guarded\",\"revision\":8,\"mutationId\":\"save-8\",\"nodes\":[]}");

        Adaptation first = adapter.adapt(input(List.of(tombstone, payload)));
        assertTrue(first.changes().isEmpty());
        assertTrue(first.claims().stream().anyMatch(claim -> claim.relativePath().equals(payload.relativePath())));
        assertEquals(List.of("MIGRATION.TOMBSTONE_BLOCKS_STALE_PAYLOAD"), first.quarantineRecords().stream().map(record -> record.code()).toList());
        Adaptation repeatedInput = adapter.adapt(input(List.of(tombstone, payload)));
        assertEquals(first.quarantineRecords().getFirst().recordId(), repeatedInput.quarantineRecords().getFirst().recordId());

        byte[] original = Files.readAllBytes(root.resolve(tombstone.relativePath()));
        Files.delete(root.resolve(payload.relativePath()));
        Adaptation second = adapter.adapt(input(List.of(tombstone)));
        assertTrue(second.changes().isEmpty());
        assertTrue(second.quarantineRecords().isEmpty());
        assertArrayEquals(original, Files.readAllBytes(root.resolve(tombstone.relativePath())));
    }

    @Test
    void newerPayloadRemainsLiveAndOutsideTombstoneOwnership() throws IOException {
        SourceFile tombstone = source("assets/.tombstones/command/revived.json",
            "{\"deleted\":true,\"id\":\"revived\",\"mutationId\":\"delete-2\",\"revision\":2,\"type\":\"command\"}");
        SourceFile payload = source("assets/Blueprints/Commands/revived.json",
            "{\"type\":\"command\",\"id\":\"revived\",\"revision\":3,\"mutationId\":\"save-3\"}");

        Adaptation result = adapter.adapt(input(List.of(tombstone, payload)));

        assertTrue(result.changes().isEmpty());
        assertTrue(result.quarantineRecords().isEmpty());
        assertFalse(result.claims().stream().anyMatch(claim -> claim.relativePath().equals(payload.relativePath())));
    }

    @Test
    void legacyAndInvalidRevisionPayloadsCannotBypassProtection() throws IOException {
        SourceFile tombstone = source("legacy/assets/.tombstones/flow/guarded.json",
            "{\"deleted\":true,\"id\":\"guarded\",\"mutationId\":\"delete-9\",\"revision\":9,\"type\":\"flow\"}");
        SourceFile payload = source("legacy/assets/Blueprints/Flows/guarded.json",
            "{\"type\":\"flow\",\"id\":\"guarded\",\"revision\":\"unknown\",\"mutationId\":\"save-unknown\"}");

        Adaptation result = adapter.adapt(input(List.of(tombstone, payload)));

        assertEquals("MIGRATION.TOMBSTONE_PAYLOAD_REVISION_INVALID", result.quarantineRecords().getFirst().code());
        assertEquals(1, result.changes().size());
        assertEquals("replace", result.changes().getFirst().kind());
        assertEquals(tombstone.relativePath(), result.changes().getFirst().sourcePath());
        assertEquals("assets/.tombstones/flow/guarded.json", result.changes().getFirst().targetPath());
        assertFalse(result.changes().stream().anyMatch(change -> change.sourcePath().equals(payload.relativePath())));
    }

    @Test
    void foreignOwnedPayloadIsReportedWithoutBeingClaimedOrDeleted() throws IOException {
        SourceFile tombstone = source("assets/.tombstones/flow/foreign.json",
            "{\"deleted\":true,\"id\":\"foreign\",\"mutationId\":\"delete-5\",\"revision\":5,\"type\":\"flow\"}");
        String payloadPath = "assets/foreign/foreign.json";
        byte[] payloadBytes = "{\"type\":\"flow\",\"id\":\"foreign\",\"revision\":5}".getBytes(StandardCharsets.UTF_8);
        write(root, payloadPath, payloadBytes);
        SourceFile payload = new SourceFile(payloadPath, payloadBytes.length, sha256(payloadBytes), "foreign.assets");

        Adaptation result = adapter.adapt(input(List.of(tombstone, payload)));

        assertTrue(result.quarantineRecords().isEmpty());
        assertFalse(result.claims().stream().anyMatch(claim -> claim.relativePath().equals(payloadPath)));
        assertFalse(result.changes().stream().anyMatch(change -> change.sourcePath().equals(payloadPath)));
    }

    @Test
    void acceptsCurrentCoreGraphTombstoneWithNestedTypedIdentityAndIntegrityHashes() throws IOException {
        CoreGraphAssetCodec codec = new CoreGraphAssetCodec();
        ServerResourceLocator resource = new ServerResourceLocator(ServerId.deterministic("tombstone-test"),
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("flow")), "current-flow");
        byte[] bytes = codec.encodeTombstone(resource, 11, UUID.fromString("00000000-0000-4000-8000-000000000011"), new ContentHash("a".repeat(64)));
        SourceFile source = source("assets/.tombstones/flow/current-flow.json", bytes);

        Adaptation result = adapter.adapt(input(List.of(source)));

        assertTrue(result.changes().isEmpty());
        assertTrue(result.quarantineRecords().isEmpty());
        assertEquals(TombstoneMigrationAdapter.OWNER, result.claims().getFirst().owner());
    }

    @Test
    void composedPlanAndStageRemoveStalePayloadAndRemainIdempotent() throws IOException {
        Path sourceRoot = Files.createDirectory(root.resolve("composed-source"));
        write(sourceRoot, "assets/.tombstones/flow/staged.json",
            "{\"deleted\":true,\"id\":\"staged\",\"mutationId\":\"delete-4\",\"revision\":4,\"type\":\"flow\"}".getBytes(StandardCharsets.UTF_8));
        write(sourceRoot, "assets/Blueprints/Flows/staged.json",
            "{\"type\":\"flow\",\"id\":\"staged\",\"revision\":4,\"mutationId\":\"save-4\"}".getBytes(StandardCharsets.UTF_8));
        Snapshot snapshot = snapshot(sourceRoot, metadata(1, "tombstone-source", "legacy-build"), root.resolve("composed-snapshot"));
        ReSyncTypedLifecycleUpgrade upgrade = new ReSyncTypedLifecycleUpgrade(List.of(adapter));

        UpgradeProposal proposal = upgrade.plan(snapshot, window(1, "legacy-build", 2, "replacement-build"));
        assertTrue(proposal.plan().operations().isEmpty());
        assertEquals("MIGRATION.TOMBSTONE_BLOCKS_STALE_PAYLOAD", proposal.quarantineReport().records().getFirst().code());

        StagedMigration staged = upgrade.stage(snapshot.root(), root.resolve("composed-output"), proposal.plan(), proposal.quarantineReport(),
            proposal.quarantineReport().accept("tombstone-test", Instant.EPOCH));
        assertTrue(Files.exists(staged.root().resolve("assets/.tombstones/flow/staged.json")));
        assertFalse(Files.exists(staged.root().resolve("assets/Blueprints/Flows/staged.json")));
        try (var paths = Files.walk(staged.root().resolve(".quarantine/migration"))) {
            assertTrue(paths.anyMatch(path -> path.endsWith("assets/Blueprints/Flows/staged.json")));
        }

        Snapshot repeated = snapshot(staged.root(), metadata(2, "tombstone-repeated", "replacement-build"), root.resolve("repeated-snapshot"));
        UpgradeProposal second = upgrade.plan(repeated, window(2, "replacement-build", 3, "next-build"));
        assertTrue(second.plan().operations().isEmpty());
    }

    @Test
    void composedStageReplacesCanonicalOccupantThroughItsOwnBoundSource() throws IOException {
        Path sourceRoot = Files.createDirectory(root.resolve("duplicate-source"));
        String canonicalPath = "assets/.tombstones/flow/shared.json";
        String winnerPath = "legacy/assets/.tombstones/flow/shared.json";
        write(sourceRoot, canonicalPath,
            "{\"deleted\":true,\"id\":\"shared\",\"mutationId\":\"delete-4\",\"revision\":4,\"type\":\"flow\"}".getBytes(StandardCharsets.UTF_8));
        write(sourceRoot, winnerPath,
            "{\"deleted\":true,\"id\":\"shared\",\"mutationId\":\"delete-6\",\"revision\":6,\"type\":\"flow\"}".getBytes(StandardCharsets.UTF_8));
        Snapshot snapshot = snapshot(sourceRoot, metadata(1, "duplicate-source", "legacy-build"), root.resolve("duplicate-snapshot"));
        ReSyncTypedLifecycleUpgrade upgrade = new ReSyncTypedLifecycleUpgrade(List.of(adapter));

        UpgradeProposal proposal = upgrade.plan(snapshot, window(1, "legacy-build", 2, "replacement-build"));

        assertEquals(2, proposal.plan().operations().size());
        assertTrue(proposal.plan().operations().stream().anyMatch(operation -> operation.sourcePath().equals(canonicalPath)
            && operation.targetPath().equals(canonicalPath)));
        assertTrue(proposal.plan().operations().stream().anyMatch(operation -> operation.sourcePath().equals(winnerPath)
            && operation.targetPath().isEmpty()));
        assertFalse(proposal.quarantineReport().records().stream().anyMatch(record -> record.code().startsWith("MIGRATION.LIFECYCLE_TARGET")));

        StagedMigration staged = upgrade.stage(snapshot.root(), root.resolve("duplicate-output"), proposal.plan());
        Map<String, Object> migrated = object(CanonicalJson.parse(Files.readAllBytes(staged.root().resolve(canonicalPath))));
        assertEquals(new BigDecimal("6"), migrated.get("revision"));
        assertFalse(Files.exists(staged.root().resolve(winnerPath)));
    }

    @Test
    void composedStageUsesOneReplaceForCanonicalAbsentLegacyWinnerAndSecondPlanIsEmpty() throws IOException {
        Path sourceRoot = Files.createDirectory(root.resolve("legacy-replace-source"));
        String legacyPath = "legacy/assets/.tombstones/flow/legacy-only.json";
        String canonicalPath = "assets/.tombstones/flow/legacy-only.json";
        write(sourceRoot, legacyPath,
            "{\"deleted\":true,\"id\":\"legacy-only\",\"mutationId\":\"delete-7\",\"revision\":7,\"type\":\"flow\"}".getBytes(StandardCharsets.UTF_8));
        Snapshot snapshot = snapshot(sourceRoot, metadata(1, "legacy-replace-source", "legacy-build"), root.resolve("legacy-replace-snapshot"));
        ReSyncTypedLifecycleUpgrade upgrade = new ReSyncTypedLifecycleUpgrade(List.of(adapter));

        UpgradeProposal proposal = upgrade.plan(snapshot, window(1, "legacy-build", 2, "replacement-build"));

        assertEquals(1, proposal.plan().operations().size());
        assertEquals("replace", proposal.plan().operations().getFirst().kind());
        assertEquals(legacyPath, proposal.plan().operations().getFirst().sourcePath());
        assertEquals(canonicalPath, proposal.plan().operations().getFirst().targetPath());
        assertTrue(proposal.quarantineReport().records().isEmpty());

        StagedMigration staged = upgrade.stage(snapshot.root(), root.resolve("legacy-replace-output"), proposal.plan());
        assertFalse(Files.exists(staged.root().resolve(legacyPath)));
        assertTrue(Files.exists(staged.root().resolve(canonicalPath)));

        Snapshot repeated = snapshot(staged.root(), metadata(2, "legacy-replace-repeated", "replacement-build"), root.resolve("legacy-replace-resnapshot"));
        UpgradeProposal second = upgrade.plan(repeated, window(2, "replacement-build", 3, "next-build"));
        assertTrue(second.plan().operations().isEmpty());
        assertTrue(second.quarantineReport().records().isEmpty());
    }

    @Test
    void stalePayloadQuarantineDoesNotSuppressCanonicalTombstoneReplacement() throws IOException {
        Path sourceRoot = Files.createDirectory(root.resolve("independent-quarantine-source"));
        String legacyPath = "legacy/assets/.tombstones/flow/independent.json";
        String canonicalPath = "assets/.tombstones/flow/independent.json";
        String payloadPath = "assets/Blueprints/Flows/independent.json";
        write(sourceRoot, legacyPath,
            "{\"deleted\":true,\"id\":\"independent\",\"mutationId\":\"delete-8\",\"revision\":8,\"type\":\"flow\"}".getBytes(StandardCharsets.UTF_8));
        write(sourceRoot, payloadPath,
            "{\"type\":\"flow\",\"id\":\"independent\",\"revision\":8,\"mutationId\":\"save-8\"}".getBytes(StandardCharsets.UTF_8));
        Snapshot snapshot = snapshot(sourceRoot, metadata(1, "independent-quarantine", "legacy-build"), root.resolve("independent-quarantine-snapshot"));
        ReSyncTypedLifecycleUpgrade upgrade = new ReSyncTypedLifecycleUpgrade(List.of(adapter));

        UpgradeProposal proposal = upgrade.plan(snapshot, window(1, "legacy-build", 2, "replacement-build"));

        assertEquals(1, proposal.plan().operations().size());
        assertEquals("replace", proposal.plan().operations().getFirst().kind());
        assertEquals(legacyPath, proposal.plan().operations().getFirst().sourcePath());
        assertEquals(canonicalPath, proposal.plan().operations().getFirst().targetPath());
        assertEquals(1, proposal.quarantineReport().records().size());
        assertEquals(payloadPath, proposal.quarantineReport().records().getFirst().sourceLocation());

        StagedMigration staged = upgrade.stage(snapshot.root(), root.resolve("independent-quarantine-output"), proposal.plan(), proposal.quarantineReport(),
            proposal.quarantineReport().accept("tombstone-test", Instant.EPOCH));
        assertTrue(Files.exists(staged.root().resolve(canonicalPath)));
        assertFalse(Files.exists(staged.root().resolve(legacyPath)));
        assertFalse(Files.exists(staged.root().resolve(payloadPath)));
    }

    @Test
    void duplicateTombstoneQuarantineDoesNotCaptureUnrelatedClaimedTombstone() throws IOException {
        Path sourceRoot = Files.createDirectory(root.resolve("unrelated-claim-source"));
        String winnerPath = "assets/.tombstones/flow/shared.json";
        String duplicatePath = "legacy/assets/.tombstones/flow/shared.json";
        String unrelatedPath = "assets/.tombstones/flow/unrelated.json";
        write(sourceRoot, winnerPath,
            "{\"deleted\":true,\"id\":\"shared\",\"mutationId\":\"delete-6\",\"revision\":6,\"type\":\"flow\"}".getBytes(StandardCharsets.UTF_8));
        write(sourceRoot, duplicatePath,
            "{\"deleted\":true,\"id\":\"shared\",\"mutationId\":\"delete-4\",\"revision\":4,\"type\":\"flow\"}".getBytes(StandardCharsets.UTF_8));
        write(sourceRoot, unrelatedPath,
            "{\"deleted\":true,\"id\":\"unrelated\",\"mutationId\":\"delete-3\",\"revision\":3,\"type\":\"flow\"}".getBytes(StandardCharsets.UTF_8));
        Snapshot snapshot = snapshot(sourceRoot, metadata(1, "unrelated-claim", "legacy-build"), root.resolve("unrelated-claim-snapshot"));
        ReSyncTypedLifecycleUpgrade upgrade = new ReSyncTypedLifecycleUpgrade(List.of(adapter));

        UpgradeProposal proposal = upgrade.plan(snapshot, window(1, "legacy-build", 2, "replacement-build"));

        assertTrue(proposal.plan().operations().isEmpty());
        assertEquals(1, proposal.quarantineReport().records().size());
        assertEquals(duplicatePath, proposal.quarantineReport().records().getFirst().sourceLocation());

        StagedMigration staged = upgrade.stage(snapshot.root(), root.resolve("unrelated-claim-output"), proposal.plan(), proposal.quarantineReport(),
            proposal.quarantineReport().accept("tombstone-test", Instant.EPOCH));
        assertTrue(Files.exists(staged.root().resolve(winnerPath)));
        assertTrue(Files.exists(staged.root().resolve(unrelatedPath)));
        assertFalse(Files.exists(staged.root().resolve(duplicatePath)));
    }

    @Test
    void productionCompositionQuarantinesCommandTombstoneGraphAndTriggerRegistryAsOneRawDependency() throws IOException {
        Path sourceRoot = Files.createDirectory(root.resolve("command-dependency-source"));
        String tombstonePath = "assets/.tombstones/command/catalog-command.json";
        String commandPath = "assets/Blueprints/Commands/catalog-command.json";
        String otherCommandPath = "assets/Blueprints/Commands/other-command.json";
        byte[] tombstone = "{\n  \"type\": \"command\", \"id\": \"catalog-command\", \"revision\": 9, \"mutationId\": \"delete-9\"\n}\n"
            .getBytes(StandardCharsets.UTF_8);
        byte[] command = CanonicalJson.canonicalBytes(Map.of(
            "connections", List.of(),
            "id", "catalog-command",
            "mutationId", "save-9",
            "nodes", Map.of("variable", Map.of(
                "handlerConfig", Map.of("operation", "variable_access"),
                "inputValues", Map.of("mode", "set", "name", "Catalog", "persist", true, "scope", "global", "value", true),
                "type", "variable.access",
                "version", 1)),
            "resourceRevision", 9,
            "resourceType", "command"));
        byte[] otherCommand = CanonicalJson.canonicalBytes(Map.of(
            "connections", List.of(),
            "id", "other-command",
            "mutationId", "other-4",
            "nodes", Map.of(),
            "resourceRevision", 4,
            "resourceType", "command"));
        byte[] triggers = "[\n  {\"type\":\"event\",\"flowId\":\"event-flow\",\"context\":\"join\"},\n"
            .concat("  {\"type\":\"command\",\"flowId\":\"catalog-command\",\"context\":\"catalog\",\"resourceRevision\":9,\"mutationId\":\"save-9\"},\n")
            .concat("  {\"type\":\"system\",\"flowId\":\"system-flow\",\"context\":\"startup\"},\n")
            .concat("  {\"type\":\"command\",\"flowId\":\"other-command\",\"context\":\"other\",\"resourceRevision\":4,\"mutationId\":\"other-4\"}\n]\n")
            .getBytes(StandardCharsets.UTF_8);
        write(sourceRoot, tombstonePath, tombstone);
        write(sourceRoot, commandPath, command);
        write(sourceRoot, otherCommandPath, otherCommand);
        write(sourceRoot, "triggers.json", triggers);
        Snapshot snapshot = snapshot(sourceRoot, metadata(1, "command-dependency", "legacy-build"),
            root.resolve("command-dependency-snapshot"), productionParticipants(sourceRoot));
        ReSyncTypedLifecycleUpgrade upgrade = ReSyncTypedLifecycleUpgrade.production();

        UpgradeProposal proposal = upgrade.plan(snapshot, window(1, "legacy-build", 2, "replacement-build"));
        UpgradeProposal repeated = upgrade.plan(snapshot, window(1, "legacy-build", 2, "replacement-build"));

        assertAll(
            () -> assertTrue(proposal.plan().operations().isEmpty()),
            () -> assertTrue(proposal.quarantineReport().records().stream().map(record -> record.sourceLocation()).toList()
                .containsAll(List.of(tombstonePath, commandPath, "triggers.json"))),
            () -> assertEquals(proposal.quarantineReport().records().stream().map(record -> record.recordId()).sorted().toList(),
                repeated.quarantineReport().records().stream().map(record -> record.recordId()).sorted().toList()));
        StagedMigration staged = upgrade.stage(snapshot.root(), root.resolve("command-dependency-output"), proposal.plan(),
            proposal.quarantineReport(), proposal.quarantineReport().accept("command-dependency-review", Instant.EPOCH));
        assertAll(
            () -> assertFalse(Files.exists(staged.root().resolve(tombstonePath))),
            () -> assertFalse(Files.exists(staged.root().resolve(commandPath))),
            () -> assertFalse(Files.exists(staged.root().resolve("triggers.json"))),
            () -> assertTrue(Files.exists(staged.root().resolve(otherCommandPath))),
            () -> assertEquals(sha256(tombstone), quarantinedHash(staged, proposal, tombstonePath)),
            () -> assertEquals(sha256(command), quarantinedHash(staged, proposal, commandPath)),
            () -> assertEquals(sha256(triggers), quarantinedHash(staged, proposal, "triggers.json")));
    }

    @Test
    void legacyPayloadAliasesMustAgreeBeforeACommandTombstoneCanApply() throws IOException {
        SourceFile tombstone = source("assets/.tombstones/command/aliased.json",
            "{\"deleted\":true,\"id\":\"aliased\",\"mutationId\":\"delete-8\",\"revision\":8,\"type\":\"command\"}");
        SourceFile matching = source("assets/Blueprints/Commands/aliased.json",
            "{\"resourceType\":\"command\",\"id\":\"aliased\",\"resourceRevision\":8,\"mutationId\":\"save-8\"}");
        Adaptation protectedResult = adapter.adapt(input(List.of(tombstone, matching)));
        assertEquals("MIGRATION.TOMBSTONE_BLOCKS_STALE_PAYLOAD", protectedResult.quarantineRecords().getFirst().code());

        SourceFile conflicting = source("assets/Blueprints/Commands/aliased.json",
            "{\"type\":\"flow\",\"resourceType\":\"command\",\"id\":\"aliased\",\"revision\":7,\"resourceRevision\":8,\"mutationId\":\"save-8\"}");
        Adaptation conflict = adapter.adapt(input(List.of(tombstone, conflicting)));
        assertAll(
            () -> assertTrue(conflict.changes().isEmpty()),
            () -> assertEquals("MIGRATION.TOMBSTONE_PAYLOAD_ALIAS_CONFLICT", conflict.quarantineRecords().getFirst().code()));
    }

    @Test
    void malformedTriggerRegistryFailsClosedForBlockedCommandDependency() throws IOException {
        SourceFile tombstone = source("assets/.tombstones/command/malformed-trigger.json",
            "{\"deleted\":true,\"id\":\"malformed-trigger\",\"mutationId\":\"delete-8\",\"revision\":8,\"type\":\"command\"}");
        SourceFile command = source("assets/Blueprints/Commands/malformed-trigger.json",
            "{\"resourceType\":\"command\",\"id\":\"malformed-trigger\",\"resourceRevision\":8,\"mutationId\":\"save-8\"}");
        byte[] triggerBytes = "{\"commands\":\"invalid\"}".getBytes(StandardCharsets.UTF_8);
        write(root, "triggers.json", triggerBytes);
        SourceFile triggers = new SourceFile("triggers.json", triggerBytes.length, sha256(triggerBytes), ProductionPersistenceOwners.TRIGGERS);

        Adaptation result = adapter.adapt(input(List.of(tombstone, command, triggers)));

        assertAll(
            () -> assertTrue(result.changes().isEmpty()),
            () -> assertTrue(result.claims().stream().anyMatch(claim -> claim.relativePath().equals("triggers.json"))),
            () -> assertTrue(result.quarantineRecords().stream()
                .anyMatch(record -> "MIGRATION.TOMBSTONE_COMMAND_BINDING_INVALID".equals(record.code()))));
    }

    private Input input(List<SourceFile> files) throws IOException {
        return new Input(root, new SnapshotMetadata(1, "snapshot", Instant.EPOCH, "test", MANIFEST_HASH, Map.of()), MANIFEST_HASH, files);
    }

    private SourceFile source(String relativePath, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        Path path = root.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
        return new SourceFile(relativePath, bytes.length, sha256(bytes), TombstoneMigrationAdapter.OWNER);
    }

    private SourceFile source(String relativePath, byte[] bytes) throws IOException {
        write(root, relativePath, bytes);
        return new SourceFile(relativePath, bytes.length, sha256(bytes), TombstoneMigrationAdapter.OWNER);
    }

    private Snapshot snapshot(Path source, SnapshotMetadata metadata, Path staging) throws IOException {
        return new SnapshotService(new MigrationFence()).create(source, staging, metadata, participants(source));
    }

    private Snapshot snapshot(Path source, SnapshotMetadata metadata, Path staging,
                              PersistenceParticipantRegistry participants) throws IOException {
        return new SnapshotService(new MigrationFence()).create(source, staging, metadata, participants);
    }

    private PersistenceParticipantRegistry participants(Path participantRoot) {
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return TombstoneMigrationAdapter.OWNER;
            }

            @Override
            public Path root() {
                return participantRoot;
            }
        });
        return participants;
    }

    private PersistenceParticipantRegistry productionParticipants(Path sourceRoot) {
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(participant(ProductionPersistenceOwners.FLOW_ASSETS, sourceRoot.resolve("assets"), false));
        participants.register(participant(ProductionPersistenceOwners.TRIGGERS, sourceRoot.resolve("triggers.json"), false));
        return participants;
    }

    private PersistenceParticipant participant(String owner, Path participantRoot, boolean mayBeAbsent) {
        return new PersistenceParticipant() {
            @Override
            public String owner() {
                return owner;
            }

            @Override
            public Path root() {
                return participantRoot;
            }

            @Override
            public boolean rootMayBeAbsent() {
                return mayBeAbsent;
            }
        };
    }

    private static String quarantinedHash(StagedMigration staged, UpgradeProposal proposal, String sourcePath) throws IOException {
        String recordId = proposal.quarantineReport().records().stream()
            .filter(record -> record.sourceLocation().equals(sourcePath))
            .findFirst()
            .orElseThrow()
            .recordId();
        return sha256(Files.readAllBytes(staged.root().resolve(".quarantine/migration").resolve(recordId).resolve(sourcePath)));
    }

    private SnapshotMetadata metadata(int format, String snapshotId, String build) {
        return new SnapshotMetadata(format, snapshotId, Instant.parse("2026-01-01T00:00:00Z"), build, MANIFEST_HASH, Map.of());
    }

    private UpgradeSourceWindow window(int sourceFormat, String sourceBuild, int targetFormat, String targetBuild) {
        return new UpgradeSourceWindow(ReplacementUpgrader.VERSION, sourceFormat, sourceBuild, targetFormat, targetBuild);
    }

    private static void write(Path base, String relativePath, byte[] bytes) throws IOException {
        Path path = base.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, Object> object(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        ((Map<?, ?>) value).forEach((key, item) -> result.put((String) key, item));
        return result;
    }
}
