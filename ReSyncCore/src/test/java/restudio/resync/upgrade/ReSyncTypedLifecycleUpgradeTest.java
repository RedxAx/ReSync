package restudio.resync.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.MigrationOperationType;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotService;
import restudio.resync.migration.StagedMigration;

class ReSyncTypedLifecycleUpgradeTest {
    @TempDir
    Path temporary;

    @Test
    void bindsOneOwnerCopiesFirstAndProducesZeroChangesOnCanonicalInput() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Files.createDirectories(source.resolve("assets"));
        Files.writeString(source.resolve("assets/lifecycle.json"), "legacy", StandardCharsets.UTF_8);
        Files.writeString(source.resolve("assets/opaque.bin"), "opaque", StandardCharsets.UTF_8);
        Snapshot snapshot = snapshot(source, metadata(1, "source-snapshot", "legacy-build"), temporary.resolve("source-snapshot"));
        ReSyncTypedLifecycleUpgrade upgrade = new ReSyncTypedLifecycleUpgrade(List.of(new CanonicalAdapter()));

        UpgradeProposal proposal = upgrade.plan(snapshot, window(1, "legacy-build", 2, "replacement-build"));
        UpgradeProposal repeated = upgrade.plan(snapshot, window(1, "legacy-build", 2, "replacement-build"));

        assertEquals(1, proposal.plan().operations().size());
        assertEquals(proposal.plan().planHash(), repeated.plan().planHash());
        assertTrue(proposal.quarantineReport().records().isEmpty());
        assertEquals("assets/lifecycle.json", proposal.plan().operations().getFirst().sourcePath());
        assertEquals("assets/lifecycle.json", proposal.plan().operations().getFirst().targetPath());
        assertEquals(MigrationOperationType.CONVERT, proposal.plan().operations().getFirst().operationType());

        StagedMigration staged = upgrade.stage(snapshot.root(), temporary.resolve("replacement"), proposal.plan());

        assertEquals("canonical", Files.readString(staged.root().resolve("assets/lifecycle.json"), StandardCharsets.UTF_8));
        assertEquals("opaque", Files.readString(staged.root().resolve("assets/opaque.bin"), StandardCharsets.UTF_8));
        Snapshot canonical = snapshot(staged.root(), metadata(2, "replacement-snapshot", "replacement-build"), temporary.resolve("replacement-snapshot"));
        String canonicalHash = canonical.manifest().entries().stream()
            .filter(entry -> entry.relativePath().equals("assets/lifecycle.json"))
            .findFirst()
            .orElseThrow()
            .sha256();
        assertEquals(canonicalHash, proposal.plan().operations().getFirst().targetHash());
        UpgradeProposal second = upgrade.plan(canonical, window(2, "replacement-build", 3, "next-build"));

        assertTrue(second.plan().operations().isEmpty());
        assertTrue(second.quarantineReport().records().isEmpty());
    }

    @Test
    void quarantinesSourcesWithoutExactlyOneMatchingOwner() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("ownership"));
        Files.writeString(source.resolve("owned.json"), "value", StandardCharsets.UTF_8);
        Snapshot snapshot = snapshot(source, metadata(1, "ownership-snapshot", "legacy-build"), temporary.resolve("ownership-snapshot"));
        TypedLifecycleMigrationAdapter first = claiming("first", "core");
        TypedLifecycleMigrationAdapter second = claiming("second", "different-owner");
        ReSyncTypedLifecycleUpgrade upgrade = new ReSyncTypedLifecycleUpgrade(List.of(first, second));

        UpgradeProposal proposal = upgrade.plan(snapshot, window(1, "legacy-build", 2, "replacement-build"));

        assertTrue(proposal.plan().operations().isEmpty());
        assertEquals(1, proposal.quarantineReport().records().size());
        assertEquals("MIGRATION.LIFECYCLE_OWNER_AMBIGUOUS", proposal.quarantineReport().records().getFirst().code());

        UpgradeProposal mismatch = new ReSyncTypedLifecycleUpgrade(List.of(claiming("mismatch", "different-owner")))
            .plan(snapshot, window(1, "legacy-build", 2, "replacement-build"));

        assertTrue(mismatch.plan().operations().isEmpty());
        assertEquals(1, mismatch.quarantineReport().records().size());
        assertEquals("MIGRATION.LIFECYCLE_OWNER_MISMATCH", mismatch.quarantineReport().records().getFirst().code());

        TypedLifecycleMigrationAdapter.Change delete = new TypedLifecycleMigrationAdapter.Change("delete", "owned.json", "", null);
        assertThrows(IllegalArgumentException.class, () -> new TypedLifecycleMigrationAdapter.Adaptation(
            List.of(new TypedLifecycleMigrationAdapter.Claim("owned.json", "core")),
            List.of(delete, delete),
            List.of()));
    }

    @Test
    void stagesAcceptedQuarantineFromItsUniqueVerifiedOwner() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("quarantine-source"));
        Files.writeString(source.resolve("quarantined.json"), "unsafe", StandardCharsets.UTF_8);
        Snapshot snapshot = snapshot(source, metadata(1, "quarantine-snapshot", "legacy-build"), temporary.resolve("quarantine-snapshot"));
        TypedLifecycleMigrationAdapter adapter = new TypedLifecycleMigrationAdapter() {
            @Override
            public String adapterId() {
                return "quarantine-owner";
            }

            @Override
            public Adaptation adapt(Input input) {
                SourceFile file = input.file("quarantined.json").orElse(null);
                if (file == null) {
                    return Adaptation.claimed(List.of());
                }
                QuarantineRecord record = new QuarantineRecord(
                    "unsafe-source",
                    "MIGRATION.LIFECYCLE_UNSAFE_SOURCE",
                    file.relativePath(),
                    "The source requires explicit review.",
                    List.of(),
                    "Review the retained source before retrying.",
                    file.sha256());
                return new Adaptation(List.of(new Claim(file.relativePath(), "core")), List.of(), List.of(record));
            }
        };
        ReSyncTypedLifecycleUpgrade upgrade = new ReSyncTypedLifecycleUpgrade(List.of(adapter));
        UpgradeProposal proposal = upgrade.plan(snapshot, window(1, "legacy-build", 2, "replacement-build"));

        StagedMigration staged = upgrade.stage(
            snapshot.root(),
            temporary.resolve("quarantined-replacement"),
            proposal.plan(),
            proposal.quarantineReport(),
            proposal.quarantineReport().accept("reviewer", Instant.parse("2026-01-01T00:00:00Z")));

        QuarantineRecord stagedRecord = proposal.quarantineReport().records().getFirst();
        assertTrue(Files.notExists(staged.root().resolve("quarantined.json")));
        assertEquals(
            "unsafe",
            Files.readString(staged.root().resolve(".quarantine/migration").resolve(stagedRecord.recordId()).resolve("quarantined.json"), StandardCharsets.UTF_8));
        Snapshot retained = snapshot(staged.root(), metadata(2, "retained-quarantine-snapshot", "replacement-build"), temporary.resolve("retained-quarantine-snapshot"));
        UpgradeProposal second = upgrade.plan(retained, window(2, "replacement-build", 3, "next-build"));
        assertTrue(second.plan().operations().isEmpty());
        assertTrue(second.quarantineReport().records().isEmpty());
    }

    @Test
    void stagesPlanBoundCopyAndSourceRetirement() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("relocation-source"));
        Files.writeString(source.resolve("legacy.json"), "preserved", StandardCharsets.UTF_8);
        Files.writeString(source.resolve("template.json"), "copied", StandardCharsets.UTF_8);
        Snapshot snapshot = snapshot(source, metadata(1, "relocation-snapshot", "legacy-build"), temporary.resolve("relocation-snapshot"));
        TypedLifecycleMigrationAdapter adapter = new TypedLifecycleMigrationAdapter() {
            @Override
            public String adapterId() {
                return "relocation-owner";
            }

            @Override
            public Adaptation adapt(Input input) throws IOException {
                SourceFile file = input.file("legacy.json").orElseThrow();
                SourceFile template = input.file("template.json").orElseThrow();
                return new Adaptation(
                    List.of(new Claim(file.relativePath(), "core"), new Claim(template.relativePath(), "core")),
                    List.of(
                        new Change("move", file.relativePath(), "canonical/value.json", input.read(file)),
                        new Change("copy", template.relativePath(), "canonical/template.json", input.read(template))),
                    List.of());
            }
        };
        ReSyncTypedLifecycleUpgrade upgrade = new ReSyncTypedLifecycleUpgrade(List.of(adapter));
        UpgradeProposal proposal = upgrade.plan(snapshot, window(1, "legacy-build", 2, "replacement-build"));

        assertEquals(
            Set.of(MigrationOperationType.COPY, MigrationOperationType.MOVE),
            proposal.plan().operations().stream().map(operation -> operation.operationType()).collect(Collectors.toSet()));

        StagedMigration staged = upgrade.stage(snapshot.root(), temporary.resolve("relocation-replacement"), proposal.plan());

        assertTrue(Files.notExists(staged.root().resolve("legacy.json")));
        assertEquals("copied", Files.readString(staged.root().resolve("template.json"), StandardCharsets.UTF_8));
        assertEquals("preserved", Files.readString(staged.root().resolve("canonical/value.json"), StandardCharsets.UTF_8));
        assertEquals("copied", Files.readString(staged.root().resolve("canonical/template.json"), StandardCharsets.UTF_8));
    }

    @Test
    void stagesUnsourcedGenerationAndRejectsItsCollisionsGlobally() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("generated-source"));
        Files.writeString(source.resolve("input.json"), "input", StandardCharsets.UTF_8);
        Snapshot snapshot = snapshot(source, metadata(1, "generated-snapshot", "legacy-build"), temporary.resolve("generated-snapshot"));
        UpgradeSourceWindow window = window(1, "legacy-build", 2, "replacement-build");
        TypedLifecycleMigrationAdapter generated = new TypedLifecycleMigrationAdapter() {
            @Override
            public String adapterId() {
                return "generated";
            }

            @Override
            public Adaptation adapt(Input input) {
                SourceFile file = input.file("input.json").orElseThrow();
                return new Adaptation(
                    List.of(new Claim(file.relativePath(), "core")),
                    List.of(
                        new Change("convert", file.relativePath(), file.relativePath(), "converted".getBytes(StandardCharsets.UTF_8)),
                        new Change("generate", "", "definitions/generated.json", "generated".getBytes(StandardCharsets.UTF_8))),
                    List.of());
            }
        };
        ReSyncTypedLifecycleUpgrade upgrade = new ReSyncTypedLifecycleUpgrade(List.of(generated));
        UpgradeProposal proposal = upgrade.plan(snapshot, window);

        assertEquals(Set.of(MigrationOperationType.CONVERT, MigrationOperationType.GENERATE),
            proposal.plan().operations().stream().map(operation -> operation.operationType()).collect(Collectors.toSet()));
        assertTrue(proposal.plan().operations().stream()
            .filter(operation -> operation.operationType() == MigrationOperationType.GENERATE)
            .findFirst().orElseThrow().sourcePath().isEmpty());
        StagedMigration staged = upgrade.stage(snapshot.root(), temporary.resolve("generated-replacement"), proposal.plan());
        assertEquals("converted", Files.readString(staged.root().resolve("input.json"), StandardCharsets.UTF_8));
        assertEquals("generated", Files.readString(staged.root().resolve("definitions/generated.json"), StandardCharsets.UTF_8));

        TypedLifecycleMigrationAdapter competing = claiming("competing", "core");
        UpgradeProposal ambiguous = new ReSyncTypedLifecycleUpgrade(List.of(generated, competing)).plan(snapshot, window);
        assertTrue(ambiguous.plan().operations().isEmpty());
        assertEquals("MIGRATION.LIFECYCLE_OWNER_AMBIGUOUS", ambiguous.quarantineReport().records().getFirst().code());

        Path dependencySource = Files.createDirectory(temporary.resolve("dependency-source"));
        Files.writeString(dependencySource.resolve("a.json"), "a", StandardCharsets.UTF_8);
        Files.writeString(dependencySource.resolve("b.json"), "b", StandardCharsets.UTF_8);
        Snapshot dependencySnapshot = snapshot(
            dependencySource,
            metadata(1, "dependency-snapshot", "legacy-build"),
            temporary.resolve("dependency-snapshot"));
        TypedLifecycleMigrationAdapter dependencyAdapter = dependencyAdapter();
        TypedLifecycleMigrationAdapter dependencyCompetitor = new TypedLifecycleMigrationAdapter() {
            @Override
            public String adapterId() {
                return "dependency-competitor";
            }

            @Override
            public Adaptation adapt(Input input) {
                return input.file("a.json")
                    .map(file -> Adaptation.claimed(List.of(new Claim(file.relativePath(), "core"))))
                    .orElseGet(() -> Adaptation.claimed(List.of()));
            }
        };
        ReSyncTypedLifecycleUpgrade dependencyUpgrade = new ReSyncTypedLifecycleUpgrade(List.of(dependencyAdapter, dependencyCompetitor));
        UpgradeProposal dependencyProposal = dependencyUpgrade.plan(dependencySnapshot, window);
        assertTrue(dependencyProposal.plan().operations().isEmpty());
        assertEquals(2, dependencyProposal.quarantineReport().records().size());
        StagedMigration dependencyStaged = dependencyUpgrade.stage(
            dependencySnapshot.root(),
            temporary.resolve("dependency-replacement"),
            dependencyProposal.plan(),
            dependencyProposal.quarantineReport(),
            dependencyProposal.quarantineReport().accept("reviewer", Instant.parse("2026-01-01T00:00:00Z")));
        assertTrue(Files.notExists(dependencyStaged.root().resolve("a.json")));
        assertTrue(Files.notExists(dependencyStaged.root().resolve("b.json")));
        Snapshot dependencyRetained = snapshot(
            dependencyStaged.root(),
            metadata(2, "dependency-retained-snapshot", "replacement-build"),
            temporary.resolve("dependency-retained-snapshot"));
        UpgradeProposal dependencySecond = dependencyUpgrade.plan(dependencyRetained, window(2, "replacement-build", 3, "next-build"));
        assertTrue(dependencySecond.plan().operations().isEmpty());
        assertTrue(dependencySecond.quarantineReport().records().isEmpty());

        TypedLifecycleMigrationAdapter collision = new TypedLifecycleMigrationAdapter() {
            @Override
            public String adapterId() {
                return "generated-collision";
            }

            @Override
            public Adaptation adapt(Input input) {
                SourceFile file = input.file("input.json").orElseThrow();
                return new Adaptation(
                    List.of(new Claim(file.relativePath(), "core")),
                    List.of(
                        new Change("generate", "", "definitions/collision.json", new byte[]{1}),
                        new Change("generate", "", "definitions/collision.json", new byte[]{2})),
                    List.of());
            }
        };
        assertThrows(IOException.class, () -> new ReSyncTypedLifecycleUpgrade(List.of(collision)).plan(snapshot, window));
    }

    @Test
    void keepsSourcedOnlyQuarantineLocalToItsClaim() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("sourced-quarantine-source"));
        Files.writeString(source.resolve("stale.json"), "stale", StandardCharsets.UTF_8);
        Files.writeString(source.resolve("canonical.json"), "legacy", StandardCharsets.UTF_8);
        Files.writeString(source.resolve("unrelated.json"), "unrelated", StandardCharsets.UTF_8);
        Snapshot snapshot = snapshot(
            source,
            metadata(1, "sourced-quarantine-snapshot", "legacy-build"),
            temporary.resolve("sourced-quarantine-snapshot"));
        TypedLifecycleMigrationAdapter adapter = new TypedLifecycleMigrationAdapter() {
            @Override
            public String adapterId() {
                return "sourced-only";
            }

            @Override
            public Adaptation adapt(Input input) throws IOException {
                SourceFile stale = input.file("stale.json").orElseThrow();
                SourceFile canonical = input.file("canonical.json").orElseThrow();
                SourceFile unrelated = input.file("unrelated.json").orElseThrow();
                return new Adaptation(
                    List.of(
                        new Claim(stale.relativePath(), "core"),
                        new Claim(canonical.relativePath(), "core"),
                        new Claim(unrelated.relativePath(), "core")),
                    List.of(new Change(
                        "convert",
                        canonical.relativePath(),
                        canonical.relativePath(),
                        "canonical".getBytes(StandardCharsets.UTF_8))),
                    List.of(new QuarantineRecord(
                        "stale-record",
                        "MIGRATION.TOMBSTONE_STALE_PAYLOAD",
                        stale.relativePath(),
                        "The stale payload cannot remain active.",
                        List.of(),
                        "Review the retained stale payload evidence.",
                        stale.sha256())));
            }
        };

        UpgradeProposal proposal = new ReSyncTypedLifecycleUpgrade(List.of(adapter))
            .plan(snapshot, window(1, "legacy-build", 2, "replacement-build"));

        assertEquals(List.of("stale.json"),
            proposal.quarantineReport().records().stream().map(QuarantineRecord::sourceLocation).toList());
        assertEquals(List.of("canonical.json"),
            proposal.plan().operations().stream().map(operation -> operation.sourcePath()).toList());
    }

    @Test
    void propagatesLateCollisionAcrossTheDependencyGroup() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("late-collision-source"));
        Files.writeString(source.resolve("a.json"), "a", StandardCharsets.UTF_8);
        Files.writeString(source.resolve("b.json"), "b", StandardCharsets.UTF_8);
        Files.writeString(source.resolve("c.json"), "c", StandardCharsets.UTF_8);
        Snapshot snapshot = snapshot(source, metadata(1, "late-collision-snapshot", "legacy-build"), temporary.resolve("late-collision-snapshot"));
        TypedLifecycleMigrationAdapter dependent = new TypedLifecycleMigrationAdapter() {
            @Override
            public String adapterId() {
                return "late-dependent";
            }

            @Override
            public Adaptation adapt(Input input) throws IOException {
                SourceFile a = input.file("a.json").orElseThrow();
                SourceFile b = input.file("b.json").orElseThrow();
                return new Adaptation(
                    List.of(new Claim(a.relativePath(), "core"), new Claim(b.relativePath(), "core")),
                    List.of(
                        new Change("copy", a.relativePath(), "shared/output.json", input.read(a)),
                        new Change("convert", b.relativePath(), b.relativePath(), "migrated".getBytes(StandardCharsets.UTF_8)),
                        new Change("generate", "", "definitions/dependent.json", "generated".getBytes(StandardCharsets.UTF_8))),
                    List.of());
            }
        };
        TypedLifecycleMigrationAdapter competing = new TypedLifecycleMigrationAdapter() {
            @Override
            public String adapterId() {
                return "late-competing";
            }

            @Override
            public Adaptation adapt(Input input) throws IOException {
                SourceFile c = input.file("c.json").orElseThrow();
                return new Adaptation(
                    List.of(new Claim(c.relativePath(), "core")),
                    List.of(new Change("copy", c.relativePath(), "shared/output.json", input.read(c))),
                    List.of());
            }
        };

        UpgradeProposal proposal = new ReSyncTypedLifecycleUpgrade(List.of(dependent, competing))
            .plan(snapshot, window(1, "legacy-build", 2, "replacement-build"));

        assertTrue(proposal.plan().operations().isEmpty());
        assertEquals(
            Set.of("a.json", "b.json", "c.json"),
            proposal.quarantineReport().records().stream().map(QuarantineRecord::sourceLocation).collect(Collectors.toSet()));
    }

    @Test
    void rejectsTamperInvalidQuarantineEvidenceAndTargetCollisions() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("invalid-source"));
        Files.writeString(source.resolve("one.json"), "one", StandardCharsets.UTF_8);
        Files.writeString(source.resolve("two.json"), "two", StandardCharsets.UTF_8);
        Snapshot snapshot = snapshot(source, metadata(1, "invalid-snapshot", "legacy-build"), temporary.resolve("invalid-snapshot"));
        UpgradeSourceWindow window = window(1, "legacy-build", 2, "replacement-build");

        Files.writeString(snapshot.root().resolve("one.json"), "tampered", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> new ReSyncTypedLifecycleUpgrade(List.of(claiming("tamper", "core"))).plan(snapshot, window));
        Files.writeString(snapshot.root().resolve("one.json"), "one", StandardCharsets.UTF_8);

        TypedLifecycleMigrationAdapter invalidHash = quarantineAdapter("invalid-hash", "0".repeat(64), false);
        assertThrows(IOException.class, () -> new ReSyncTypedLifecycleUpgrade(List.of(invalidHash)).plan(snapshot, window));

        TypedLifecycleMigrationAdapter contradictory = quarantineAdapter("contradictory", null, true);
        assertThrows(IOException.class, () -> new ReSyncTypedLifecycleUpgrade(List.of(contradictory)).plan(snapshot, window));

        TypedLifecycleMigrationAdapter collision = new TypedLifecycleMigrationAdapter() {
            @Override
            public String adapterId() {
                return "collision";
            }

            @Override
            public Adaptation adapt(Input input) throws IOException {
                SourceFile one = input.file("one.json").orElseThrow();
                SourceFile two = input.file("two.json").orElseThrow();
                return new Adaptation(
                    List.of(new Claim(one.relativePath(), "core"), new Claim(two.relativePath(), "core")),
                    List.of(
                        new Change("generate-one", one.relativePath(), "generated/value.json", input.read(one)),
                        new Change("generate-two", two.relativePath(), "generated/value.json", input.read(two))),
                    List.of());
            }
        };
        UpgradeProposal collisionProposal = new ReSyncTypedLifecycleUpgrade(List.of(collision)).plan(snapshot, window);
        assertTrue(collisionProposal.plan().operations().isEmpty());
        assertEquals(2, collisionProposal.quarantineReport().records().size());
    }

    private Snapshot snapshot(Path source, SnapshotMetadata metadata, Path staging) throws IOException {
        return new SnapshotService(new MigrationFence()).create(source, staging, metadata, participants(source));
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

    private SnapshotMetadata metadata(int format, String snapshotId, String build) {
        return new SnapshotMetadata(format, snapshotId, Instant.parse("2026-01-01T00:00:00Z"), build, "b".repeat(64), Map.of());
    }

    private UpgradeSourceWindow window(int sourceFormat, String sourceBuild, int targetFormat, String targetBuild) {
        return new UpgradeSourceWindow(ReplacementUpgrader.VERSION, sourceFormat, sourceBuild, targetFormat, targetBuild);
    }

    private TypedLifecycleMigrationAdapter claiming(String adapterId, String owner) {
        return new TypedLifecycleMigrationAdapter() {
            @Override
            public String adapterId() {
                return adapterId;
            }

            @Override
            public Adaptation adapt(Input input) {
                return Adaptation.claimed(input.files().stream().map(source -> new Claim(source.relativePath(), owner)).toList());
            }
        };
    }

    private TypedLifecycleMigrationAdapter dependencyAdapter() {
        return new TypedLifecycleMigrationAdapter() {
            @Override
            public String adapterId() {
                return "dependency";
            }

            @Override
            public Adaptation adapt(Input input) {
                List<SourceFile> files = input.files().stream()
                    .filter(file -> file.relativePath().equals("a.json") || file.relativePath().equals("b.json"))
                    .toList();
                List<Claim> claims = files.stream().map(file -> new Claim(file.relativePath(), "core")).toList();
                if (files.size() != 2) {
                    return Adaptation.claimed(claims);
                }
                return new Adaptation(
                    claims,
                    List.of(
                        new Change("convert", "b.json", "b.json", "migrated".getBytes(StandardCharsets.UTF_8)),
                        new Change("generate", "", "definitions/dependency.json", "generated".getBytes(StandardCharsets.UTF_8))),
                    List.of());
            }
        };
    }

    private TypedLifecycleMigrationAdapter quarantineAdapter(String adapterId, String sourceHash, boolean withChange) {
        return new TypedLifecycleMigrationAdapter() {
            @Override
            public String adapterId() {
                return adapterId;
            }

            @Override
            public Adaptation adapt(Input input) {
                List<Claim> claims = input.files().stream().map(source -> new Claim(source.relativePath(), "core")).toList();
                SourceFile source = input.file("one.json").orElseThrow();
                QuarantineRecord quarantine = new QuarantineRecord(
                    adapterId,
                    "MIGRATION.LIFECYCLE_INVALID_TEST_SOURCE",
                    source.relativePath(),
                    "The source is invalid.",
                    List.of(),
                    "Repair the source.",
                    sourceHash == null ? source.sha256() : sourceHash);
                List<Change> changes = withChange
                    ? List.of(new Change("convert", source.relativePath(), source.relativePath(), "changed".getBytes(StandardCharsets.UTF_8)))
                    : List.of();
                return new Adaptation(claims, changes, List.of(quarantine));
            }
        };
    }

    private static final class CanonicalAdapter implements TypedLifecycleMigrationAdapter {
        @Override
        public String adapterId() {
            return "resync.lifecycle.canonical";
        }

        @Override
        public Adaptation adapt(Input input) throws IOException {
            List<Claim> claims = input.files().stream().map(source -> new Claim(source.relativePath(), source.owner())).toList();
            List<Change> changes = new ArrayList<>();
            SourceFile lifecycle = input.file("assets/lifecycle.json").orElseThrow();
            if (new String(input.read(lifecycle), StandardCharsets.UTF_8).equals("legacy")) {
                changes.add(new Change("canonicalize", lifecycle.relativePath(), lifecycle.relativePath(), "canonical".getBytes(StandardCharsets.UTF_8)));
            }
            return new Adaptation(claims, changes, List.of());
        }
    }
}
