package restudio.resync.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerifiedSnapshotExporterTest {
    @TempDir
    Path temporary;

    @Test
    void materializesVerifiedDerivedSnapshotWithExplicitOwnersAndIsIdempotent() throws Exception {
        Fixture fixture = fixture("first");
        Snapshot sourceSnapshot = fixture.sourceAdmission().snapshot();
        List<VerifiedSnapshotExporter.SourceOwnerBinding> sourceOwners = sourceSnapshot.manifest().entries().stream()
            .map(entry -> new VerifiedSnapshotExporter.SourceOwnerBinding(entry.relativePath(), "source-owner"))
            .toList();
        SnapshotMetadata targetMetadata = new SnapshotMetadata(1, "derived", Instant.parse("2026-08-24T00:00:00Z"),
            "replacement-build", "b".repeat(64), Map.of());

        VerifiedSnapshotAdmission first = VerifiedSnapshotExporter.export(fixture.staged(), fixture.sourceAdmission(),
            targetMetadata, sourceOwners, List.of(new VerifiedSnapshotExporter.ChangeTargetOwner(
                "generated/value.json", "generated-owner")), fixture.exportRoot());
        VerifiedSnapshotAdmission second = VerifiedSnapshotExporter.export(fixture.staged(), fixture.sourceAdmission(),
            targetMetadata, sourceOwners, List.of(new VerifiedSnapshotExporter.ChangeTargetOwner(
                "generated/value.json", "generated-owner")), fixture.exportRoot());

        assertEquals(first.manifestHash(), second.manifestHash());
        assertEquals("source-owner", first.snapshot().manifest().entries().stream()
            .filter(entry -> entry.relativePath().equals("original/value.json")).findFirst().orElseThrow().owner());
        assertEquals("generated-owner", first.snapshot().manifest().entries().stream()
            .filter(entry -> entry.relativePath().equals("generated/value.json")).findFirst().orElseThrow().owner());
    }

    @Test
    void rejectsUnclaimedAndChangedStagedFiles() throws Exception {
        Fixture missingTarget = fixture("missing-target");
        List<VerifiedSnapshotExporter.SourceOwnerBinding> sourceOwners = List.of(
            new VerifiedSnapshotExporter.SourceOwnerBinding("original/value.json", "source-owner"));
        SnapshotMetadata metadata = metadata("missing-target");

        assertThrows(MigrationException.class, () -> VerifiedSnapshotExporter.export(missingTarget.staged(),
            missingTarget.sourceAdmission(), metadata, sourceOwners, List.of(), missingTarget.exportRoot()));

        Fixture changed = fixture("changed");
        Files.writeString(changed.staged().resolve("original/value.json"), "changed", StandardCharsets.UTF_8);
        assertThrows(MigrationException.class, () -> VerifiedSnapshotExporter.export(changed.staged(),
            changed.sourceAdmission(), metadata, sourceOwners,
            List.of(new VerifiedSnapshotExporter.ChangeTargetOwner("generated/value.json", "generated-owner")),
            changed.exportRoot()));
    }

    @Test
    void rejectsConflictingExistingExport() throws Exception {
        Fixture fixture = fixture("conflict");
        List<VerifiedSnapshotExporter.SourceOwnerBinding> sourceOwners = List.of(
            new VerifiedSnapshotExporter.SourceOwnerBinding("original/value.json", "source-owner"));
        SnapshotMetadata metadata = metadata("conflict");
        List<VerifiedSnapshotExporter.ChangeTargetOwner> targets = List.of(
            new VerifiedSnapshotExporter.ChangeTargetOwner("generated/value.json", "generated-owner"));
        VerifiedSnapshotExporter.export(fixture.staged(), fixture.sourceAdmission(), metadata, sourceOwners, targets,
            fixture.exportRoot());
        Files.writeString(fixture.staged().resolve("generated/value.json"), "different", StandardCharsets.UTF_8);

        assertThrows(MigrationException.class, () -> VerifiedSnapshotExporter.export(fixture.staged(),
            fixture.sourceAdmission(), metadata, sourceOwners, targets, fixture.exportRoot()));
    }

    @Test
    void requiresVerifiedSourceOwnersAndRecoversPartialExport() throws Exception {
        Fixture fixture = fixture("recovery");
        List<VerifiedSnapshotExporter.SourceOwnerBinding> wrongOwners = List.of(
            new VerifiedSnapshotExporter.SourceOwnerBinding("original/value.json", "different-owner"));
        assertThrows(MigrationException.class, () -> VerifiedSnapshotExporter.export(fixture.staged(),
            fixture.sourceAdmission(), metadata("recovery"), wrongOwners, List.of(), fixture.exportRoot()));

        List<VerifiedSnapshotExporter.SourceOwnerBinding> sourceOwners = List.of(
            new VerifiedSnapshotExporter.SourceOwnerBinding("original/value.json", "source-owner"));
        SnapshotMetadata metadata = metadata("recovery");
        VerifiedSnapshotAdmission first = VerifiedSnapshotExporter.export(fixture.staged(), fixture.sourceAdmission(),
            metadata, sourceOwners, List.of(new VerifiedSnapshotExporter.ChangeTargetOwner(
                "generated/value.json", "generated-owner")), fixture.exportRoot());
        Files.delete(fixture.exportRoot().resolveSibling(fixture.exportRoot().getFileName() + ".manifest"));

        VerifiedSnapshotAdmission recovered = VerifiedSnapshotExporter.export(fixture.staged(),
            fixture.sourceAdmission(), metadata, sourceOwners, List.of(new VerifiedSnapshotExporter.ChangeTargetOwner(
                "generated/value.json", "generated-owner")), fixture.exportRoot());
        assertEquals(first.manifestHash(), recovered.manifestHash());
    }

    @Test
    void rejectsHardLinkedStagedFiles() throws Exception {
        Fixture fixture = fixture("hardlink");
        Files.createLink(fixture.staged().resolve("generated/alias.json"),
            fixture.staged().resolve("generated/value.json"));
        List<VerifiedSnapshotExporter.SourceOwnerBinding> sourceOwners = List.of(
            new VerifiedSnapshotExporter.SourceOwnerBinding("original/value.json", "source-owner"));
        assertThrows(MigrationException.class, () -> VerifiedSnapshotExporter.export(fixture.staged(),
            fixture.sourceAdmission(), metadata("hardlink"), sourceOwners,
            List.of(new VerifiedSnapshotExporter.ChangeTargetOwner("generated/value.json", "generated-owner")),
            fixture.exportRoot()));
    }

    @Test
    void rejectsHardLinkedVerifiedSourceFiles() throws Exception {
        Fixture fixture = fixture("source-hardlink");
        List<VerifiedSnapshotExporter.SourceOwnerBinding> sourceOwners = fixture.sourceAdmission().snapshot()
            .manifest().entries().stream()
            .map(entry -> new VerifiedSnapshotExporter.SourceOwnerBinding(entry.relativePath(), entry.owner()))
            .toList();
        assertThrows(MigrationException.class, () -> VerifiedSnapshotExporter.export(fixture.staged(),
            fixture.sourceAdmission(), metadata("source-hardlink"), sourceOwners,
            List.of(new VerifiedSnapshotExporter.ChangeTargetOwner("generated/value.json", "generated-owner")),
            fixture.exportRoot()));
    }

    private Fixture fixture(String name) throws Exception {
        Path source = Files.createDirectories(temporary.resolve(name + "-source"));
        Files.createDirectories(source.resolve("original"));
        Files.writeString(source.resolve("original/value.json"), "original", StandardCharsets.UTF_8);
        if (name.equals("source-hardlink")) {
            Files.createLink(source.resolve("original/alias.json"), source.resolve("original/value.json"));
        }
        SnapshotMetadata sourceMetadata = metadata(name + "-source");
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return "source-owner";
            }

            @Override
            public Path root() {
                return source.resolve("original");
            }
        });
        Snapshot sourceSnapshot = new SnapshotService(new MigrationFence()).create(source,
            temporary.resolve(name + "-source-snapshot"), sourceMetadata, participants);
        if (name.equals("source-hardlink")) {
            Path alias = sourceSnapshot.root().resolve("original/alias.json");
            Files.delete(alias);
            Files.createLink(alias, sourceSnapshot.root().resolve("original/value.json"));
        }
        VerifiedSnapshotAdmission admission = new SnapshotService(new MigrationFence()).admitExported(sourceSnapshot.root());
        Path staged = Files.createDirectories(temporary.resolve(name + "-staged"));
        Files.createDirectories(staged.resolve("original"));
        Files.createDirectories(staged.resolve("generated"));
        Files.writeString(staged.resolve("original/value.json"), "original", StandardCharsets.UTF_8);
        Files.writeString(staged.resolve("generated/value.json"), "generated", StandardCharsets.UTF_8);
        return new Fixture(admission, staged, temporary.resolve(name + "-export"));
    }

    private SnapshotMetadata metadata(String id) {
        return new SnapshotMetadata(1, id, Instant.parse("2026-08-24T00:00:00Z"), "build", "a".repeat(64), Map.of());
    }

    private record Fixture(VerifiedSnapshotAdmission sourceAdmission, Path staged, Path exportRoot) {
    }
}
