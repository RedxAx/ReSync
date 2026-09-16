package restudio.resync.upgrade.lifecycle;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.MigrationOperationType;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotService;
import restudio.resync.migration.StagedMigration;
import restudio.resync.upgrade.ReSyncTypedLifecycleUpgrade;
import restudio.resync.upgrade.ReplacementUpgrader;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter;
import restudio.resync.upgrade.UpgradeProposal;
import restudio.resync.upgrade.UpgradeSourceWindow;

class ManagedFileMigrationAdapterTest {
    @TempDir
    Path temporary;

    @Test
    void relocatesOpaqueBytesAndRecognizesTheCompletedTarget() throws IOException {
        byte[] opaque = {0, -1, 17, 0, 92, -128};
        Path sourceRoot = Files.createDirectory(temporary.resolve("source"));
        write(sourceRoot, "legacy/config.bin", opaque);
        ManagedFileMigrationAdapter adapter = adapter(ManagedFileMigrationAdapter.Policy.relocate(
            "resync.config", "legacy/config.bin", "config/config.bin"));

        TypedLifecycleMigrationAdapter.Adaptation first = adapter.adapt(input(sourceRoot,
            file("legacy/config.bin", opaque, "resync.config")));

        assertAll(
            () -> assertEquals(List.of(new TypedLifecycleMigrationAdapter.Claim("legacy/config.bin", "resync.config")), first.claims()),
            () -> assertEquals(1, first.changes().size()),
            () -> assertEquals("move", first.changes().getFirst().kind()),
            () -> assertEquals("config/config.bin", first.changes().getFirst().targetPath()),
            () -> assertArrayEquals(opaque, first.changes().stream().filter(change -> !change.targetPath().isEmpty()).findFirst().orElseThrow().targetBytes()),
            () -> assertEquals(sha256(opaque), first.changes().stream().filter(change -> !change.targetPath().isEmpty()).findFirst().orElseThrow().targetHash()),
            () -> assertTrue(first.quarantineRecords().isEmpty()));

        Path completedRoot = Files.createDirectory(temporary.resolve("completed"));
        write(completedRoot, "config/config.bin", opaque);
        TypedLifecycleMigrationAdapter.Adaptation second = adapter.adapt(input(completedRoot,
            file("config/config.bin", opaque, "resync.config")));

        assertAll(
            () -> assertEquals(List.of(new TypedLifecycleMigrationAdapter.Claim("config/config.bin", "resync.config")), second.claims()),
            () -> assertTrue(second.changes().isEmpty()),
            () -> assertTrue(second.quarantineRecords().isEmpty()));
    }

    @Test
    void claimsEveryConfiguredEntryAndRejectsManifestHashDrift() throws IOException {
        byte[] config = "config".getBytes(StandardCharsets.UTF_8);
        byte[] resources = "resources".getBytes(StandardCharsets.UTF_8);
        byte[] diagnostics = "diagnostics".getBytes(StandardCharsets.UTF_8);
        Path root = Files.createDirectory(temporary.resolve("coverage"));
        write(root, "config/server.yml", config);
        write(root, "resources/items.json", resources);
        write(root, "diagnostics/retained.bin", diagnostics);
        ManagedFileMigrationAdapter adapter = adapter(
            ManagedFileMigrationAdapter.Policy.preserve("resync.config", "config/server.yml"),
            ManagedFileMigrationAdapter.Policy.preserve("resync.resources", "resources/items.json"),
            ManagedFileMigrationAdapter.Policy.preserve("resync.diagnostics", "diagnostics/retained.bin"));

        TypedLifecycleMigrationAdapter.Input input = input(root,
            file("resources/items.json", resources, "resync.resources"),
            file("config/server.yml", config, "resync.config"),
            file("diagnostics/retained.bin", diagnostics, "resync.diagnostics"));
        TypedLifecycleMigrationAdapter.Adaptation result = adapter.adapt(input);

        assertAll(
            () -> assertEquals(3, result.claims().size()),
            () -> assertTrue(result.changes().isEmpty()),
            () -> assertTrue(result.quarantineRecords().isEmpty()),
            () -> assertArrayEquals(diagnostics, Files.readAllBytes(root.resolve("diagnostics/retained.bin"))));

        Files.writeString(root.resolve("resources/items.json"), "changed");
        assertThrows(IOException.class, () -> adapter.adapt(input));
    }

    @Test
    void leavesOwnerMismatchForTheAggregateClaimValidator() throws IOException {
        byte[] bytes = "config".getBytes(StandardCharsets.UTF_8);
        Path root = Files.createDirectory(temporary.resolve("owner"));
        write(root, "config/server.yml", bytes);
        ManagedFileMigrationAdapter adapter = adapter(
            ManagedFileMigrationAdapter.Policy.preserve("resync.config", "config/server.yml"));

        TypedLifecycleMigrationAdapter.Adaptation result = adapter.adapt(input(root,
            file("config/server.yml", bytes, "resync.resources")));

        assertAll(
            () -> assertEquals(List.of(new TypedLifecycleMigrationAdapter.Claim("config/server.yml", "resync.config")), result.claims()),
            () -> assertTrue(result.changes().isEmpty()),
            () -> assertTrue(result.quarantineRecords().isEmpty()));
    }

    @Test
    void quarantinesAnExistingRelocationTargetWithoutReadingOrOverwritingIt() throws IOException {
        byte[] source = "source".getBytes(StandardCharsets.UTF_8);
        byte[] target = "target".getBytes(StandardCharsets.UTF_8);
        Path root = Files.createDirectory(temporary.resolve("collision"));
        write(root, "legacy/value.bin", source);
        write(root, "canonical/value.bin", target);
        ManagedFileMigrationAdapter adapter = adapter(ManagedFileMigrationAdapter.Policy.relocate(
            "resync.config", "legacy/value.bin", "canonical/value.bin"));

        TypedLifecycleMigrationAdapter.Adaptation result = adapter.adapt(input(root,
            file("legacy/value.bin", source, "resync.config"),
            file("canonical/value.bin", target, "resync.config")));

        assertAll(
            () -> assertEquals(2, result.claims().size()),
            () -> assertTrue(result.changes().isEmpty()),
            () -> assertEquals(1, result.quarantineRecords().size()),
            () -> assertEquals(ManagedFileMigrationAdapter.CODE_TARGET_COLLISION, result.quarantineRecords().getFirst().code()),
            () -> assertArrayEquals(source, Files.readAllBytes(root.resolve("legacy/value.bin"))),
            () -> assertArrayEquals(target, Files.readAllBytes(root.resolve("canonical/value.bin"))));
    }

    @Test
    void quarantinesPhysicalTargetsMissingFromTheVerifiedFileManifest() throws IOException {
        byte[] source = "source".getBytes(StandardCharsets.UTF_8);
        Path directoryRoot = Files.createDirectory(temporary.resolve("directory-target"));
        write(directoryRoot, "legacy/value.bin", source);
        Files.createDirectories(directoryRoot.resolve("canonical/value.bin"));
        ManagedFileMigrationAdapter adapter = adapter(ManagedFileMigrationAdapter.Policy.relocate(
            "resync.config", "legacy/value.bin", "canonical/value.bin"));

        TypedLifecycleMigrationAdapter.Adaptation directory = adapter.adapt(input(directoryRoot,
            file("legacy/value.bin", source, "resync.config")));

        Path fileRoot = Files.createDirectory(temporary.resolve("unverified-target"));
        write(fileRoot, "legacy/value.bin", source);
        write(fileRoot, "canonical/value.bin", "unverified".getBytes(StandardCharsets.UTF_8));
        TypedLifecycleMigrationAdapter.Adaptation unverified = adapter.adapt(input(fileRoot,
            file("legacy/value.bin", source, "resync.config")));

        Path ancestorRoot = Files.createDirectory(temporary.resolve("unverified-ancestor"));
        write(ancestorRoot, "legacy/value.bin", source);
        write(ancestorRoot, "canonical", "blocking-file".getBytes(StandardCharsets.UTF_8));
        TypedLifecycleMigrationAdapter.Adaptation ancestor = adapter.adapt(input(ancestorRoot,
            file("legacy/value.bin", source, "resync.config")));

        assertAll(
            () -> assertTrue(directory.changes().isEmpty()),
            () -> assertEquals(ManagedFileMigrationAdapter.CODE_TARGET_COLLISION, directory.quarantineRecords().getFirst().code()),
            () -> assertTrue(unverified.changes().isEmpty()),
            () -> assertEquals(ManagedFileMigrationAdapter.CODE_TARGET_COLLISION, unverified.quarantineRecords().getFirst().code()),
            () -> assertTrue(ancestor.changes().isEmpty()),
            () -> assertEquals(ManagedFileMigrationAdapter.CODE_TARGET_COLLISION, ancestor.quarantineRecords().getFirst().code()));
    }

    @Test
    void rejectsVerifiedFileHierarchyCollisionsBeforeStaging() throws IOException {
        byte[] source = "source".getBytes(StandardCharsets.UTF_8);
        byte[] hierarchy = "hierarchy".getBytes(StandardCharsets.UTF_8);
        Path root = Files.createDirectory(temporary.resolve("hierarchy"));
        write(root, "old/value.bin", source);
        write(root, "legacy", hierarchy);
        ManagedFileMigrationAdapter adapter = adapter(ManagedFileMigrationAdapter.Policy.relocate(
            "resync.config", "old/value.bin", "legacy/value.bin"));

        TypedLifecycleMigrationAdapter.Adaptation result = adapter.adapt(input(root,
            file("old/value.bin", source, "resync.config"),
            file("legacy", hierarchy, "resync.other")));

        Path ownRoot = Files.createDirectory(temporary.resolve("own-hierarchy"));
        write(ownRoot, "legacy", source);
        ManagedFileMigrationAdapter ownAdapter = adapter(ManagedFileMigrationAdapter.Policy.relocate(
            "resync.config", "legacy", "legacy/value.bin"));
        TypedLifecycleMigrationAdapter.Adaptation own = ownAdapter.adapt(input(ownRoot,
            file("legacy", source, "resync.config")));

        assertAll(
            () -> assertEquals(1, result.claims().size()),
            () -> assertTrue(result.changes().isEmpty()),
            () -> assertEquals(1, result.quarantineRecords().size()),
            () -> assertEquals("old/value.bin", result.quarantineRecords().getFirst().sourceLocation()),
            () -> assertEquals(sha256(source), result.quarantineRecords().getFirst().sourceHash()),
            () -> assertTrue(own.changes().isEmpty()),
            () -> assertEquals("legacy", own.quarantineRecords().getFirst().sourceLocation()),
            () -> assertEquals(sha256(source), own.quarantineRecords().getFirst().sourceHash()));
    }

    @Test
    void stagesOneMoveAndProducesNoSecondRunChanges() throws IOException {
        byte[] opaque = {0, 10, -1, 42};
        Path source = Files.createDirectory(temporary.resolve("stage-source"));
        write(source, "legacy/config.bin", opaque);
        Snapshot snapshot = snapshot(source, 1, "managed-source", "legacy-build", temporary.resolve("managed-source-snapshot"));
        ManagedFileMigrationAdapter adapter = new ManagedFileMigrationAdapter(
            "resync.managed-files/v1",
            List.of(ManagedFileMigrationAdapter.Policy.relocate("core", "legacy/config.bin", "config/config.bin")));
        ReSyncTypedLifecycleUpgrade upgrade = new ReSyncTypedLifecycleUpgrade(List.of(adapter));

        UpgradeProposal proposal = upgrade.plan(snapshot, window(1, "legacy-build", 2, "replacement-build"));

        assertAll(
            () -> assertEquals(1, proposal.plan().operations().size()),
            () -> assertEquals(MigrationOperationType.MOVE, proposal.plan().operations().getFirst().operationType()),
            () -> assertEquals(sha256(opaque), proposal.plan().operations().getFirst().sourceHash()),
            () -> assertEquals(sha256(opaque), proposal.plan().operations().getFirst().targetHash()),
            () -> assertTrue(proposal.quarantineReport().records().isEmpty()));

        StagedMigration staged = upgrade.stage(snapshot.root(), temporary.resolve("managed-replacement"), proposal.plan());

        assertAll(
            () -> assertTrue(Files.notExists(staged.root().resolve("legacy/config.bin"))),
            () -> assertArrayEquals(opaque, Files.readAllBytes(staged.root().resolve("config/config.bin"))));

        Snapshot canonical = snapshot(staged.root(), 2, "managed-canonical", "replacement-build", temporary.resolve("managed-canonical-snapshot"));
        UpgradeProposal repeated = upgrade.plan(canonical, window(2, "replacement-build", 3, "next-build"));

        assertAll(
            () -> assertTrue(repeated.plan().operations().isEmpty()),
            () -> assertTrue(repeated.quarantineReport().records().isEmpty()));
    }

    @Test
    void rejectsUnsafeAndAmbiguousPolicyTables() {
        assertAll(
            () -> assertThrows(IllegalArgumentException.class,
                () -> ManagedFileMigrationAdapter.Policy.preserve("resync.config", "../server.yml")),
            () -> assertThrows(IllegalArgumentException.class,
                () -> ManagedFileMigrationAdapter.Policy.relocate("resync.config", "config/server.yml", "C:/server.yml")),
            () -> assertThrows(IllegalArgumentException.class,
                () -> ManagedFileMigrationAdapter.Policy.preserve("resync.config", "config/server.yml.")),
            () -> assertThrows(IllegalArgumentException.class,
                () -> ManagedFileMigrationAdapter.Policy.preserve("resync.config", "config/con.json")),
            () -> assertThrows(IllegalArgumentException.class,
                () -> ManagedFileMigrationAdapter.Policy.preserve("resync.config", ".quarantine/retained.json")),
            () -> assertThrows(IllegalArgumentException.class,
                () -> ManagedFileMigrationAdapter.Policy.preserve("resync.config", "config/cafe\u0301.json")),
            () -> assertThrows(IllegalArgumentException.class,
                () -> adapter(
                    ManagedFileMigrationAdapter.Policy.preserve("resync.config", "config/server.yml"),
                    ManagedFileMigrationAdapter.Policy.preserve("resync.resources", "config/server.yml"))),
            () -> assertThrows(IllegalArgumentException.class,
                () -> adapter(
                    ManagedFileMigrationAdapter.Policy.relocate("resync.config", "legacy/a", "canonical/value"),
                    ManagedFileMigrationAdapter.Policy.relocate("resync.config", "legacy/b", "canonical/value"))));
    }

    @Test
    void rejectsSymbolicLinkSources() throws IOException {
        byte[] bytes = "outside".getBytes(StandardCharsets.UTF_8);
        Path root = Files.createDirectory(temporary.resolve("symlink"));
        Path outside = temporary.resolve("outside.bin");
        Files.write(outside, bytes);
        Path link = root.resolve("managed.bin");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable in this environment");
            return;
        }
        ManagedFileMigrationAdapter adapter = adapter(
            ManagedFileMigrationAdapter.Policy.preserve("resync.config", "managed.bin"));

        assertThrows(IllegalArgumentException.class, () -> adapter.adapt(input(root,
            file("managed.bin", bytes, "resync.config"))));
    }

    private ManagedFileMigrationAdapter adapter(ManagedFileMigrationAdapter.Policy... policies) {
        return new ManagedFileMigrationAdapter("resync.managed-files/v1", List.of(policies));
    }

    private TypedLifecycleMigrationAdapter.Input input(Path root, TypedLifecycleMigrationAdapter.SourceFile... files) throws IOException {
        return new TypedLifecycleMigrationAdapter.Input(root, metadata(), "f".repeat(64), List.of(files));
    }

    private TypedLifecycleMigrationAdapter.SourceFile file(String path, byte[] bytes, String owner) {
        return new TypedLifecycleMigrationAdapter.SourceFile(path, bytes.length, sha256(bytes), owner);
    }

    private void write(Path root, String relativePath, byte[] bytes) throws IOException {
        Path target = root.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private SnapshotMetadata metadata() {
        return new SnapshotMetadata(1, "managed-file-snapshot", Instant.parse("2026-01-01T00:00:00Z"), "legacy-build", "e".repeat(64), Map.of());
    }

    private Snapshot snapshot(Path source, int format, String snapshotId, String build, Path staging) throws IOException {
        return new SnapshotService(new MigrationFence()).create(
            source,
            staging,
            new SnapshotMetadata(format, snapshotId, Instant.parse("2026-01-01T00:00:00Z"), build, "e".repeat(64), Map.of()),
            participants(source));
    }

    private PersistenceParticipantRegistry participants(Path root) {
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return "core";
            }

            @Override
            public Path root() {
                return root;
            }
        });
        return participants;
    }

    private UpgradeSourceWindow window(int sourceFormat, String sourceBuild, int targetFormat, String targetBuild) {
        return new UpgradeSourceWindow(ReplacementUpgrader.VERSION, sourceFormat, sourceBuild, targetFormat, targetBuild);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
