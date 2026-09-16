package restudio.resync.upgrade;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.migration.QuarantineReport;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotService;
import restudio.resync.migration.VerifiedSnapshotAdmission;
import restudio.resync.storage.StorageSafety;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetAdoptionArtifactProducerTest {
    private static final String OWNER = "asset-owner";
    private static final String PLAN_HASH = "a".repeat(64);

    @TempDir
    Path temporary;

    @Test
    void rejectsClaimThatIsAbsentFromThePostStageManifest() throws Exception {
        Fixture fixture = fixture("missing-claim", "post-missing-claim");
        AssetAdoptionArtifactProducer.StageOutput stage = stage(fixture,
            List.of(new AssetAdoptionArtifactProducer.LifecycleOutput("assets", OWNER,
                List.of(new AssetAdoptionArtifactProducer.Claim("assets/missing.json", OWNER)), List.of())));

        assertThrows(MigrationException.class,
            () -> AssetAdoptionArtifactProducer.produce(coordination(), fixture.preAdmission(), stage));
    }

    @Test
    void rejectsClaimWhoseOwnerDiffersFromThePostStageManifest() throws Exception {
        Fixture fixture = fixture("unowned-claim", "post-unowned-claim");
        AssetAdoptionArtifactProducer.StageOutput stage = stage(fixture,
            List.of(new AssetAdoptionArtifactProducer.LifecycleOutput("assets", "foreign-owner",
                List.of(new AssetAdoptionArtifactProducer.Claim("assets/project.json", "foreign-owner")), List.of())));

        assertThrows(MigrationException.class,
            () -> AssetAdoptionArtifactProducer.produce(coordination(), fixture.preAdmission(), stage));
    }

    @Test
    void bindsChangeSourcesToPreStageAndTargetsToPostStage() throws Exception {
        Fixture fixture = fixture("change-hash", "post-change-hash");
        AssetAdoptionArtifactProducer.StageOutput stage = stage(fixture,
            List.of(new AssetAdoptionArtifactProducer.LifecycleOutput("assets", OWNER,
                List.of(new AssetAdoptionArtifactProducer.Claim("assets/project.json", OWNER)),
                List.of(new AssetAdoptionArtifactProducer.AcceptedChange("convert", "assets/project.json",
                    "assets/project.json", "b".repeat(64), "b".repeat(64), OWNER)))));

        assertThrows(MigrationException.class,
            () -> AssetAdoptionArtifactProducer.produce(coordination(), fixture.preAdmission(), stage));
    }

    @Test
    void requiresExactlyOneAcceptedChangeForAContentModification() throws Exception {
        Fixture fixture = fixture("reported-change", "post-reported-change");
        var before = fixture.preAdmission().snapshot().manifest().entries().getFirst();
        var after = fixture.postAdmission().snapshot().manifest().entries().getFirst();
        AssetAdoptionArtifactProducer.LifecycleOutput output = new AssetAdoptionArtifactProducer.LifecycleOutput(
            "assets", OWNER, List.of(new AssetAdoptionArtifactProducer.Claim(after.relativePath(), OWNER)),
            List.of(new AssetAdoptionArtifactProducer.AcceptedChange("rewrite", before.relativePath(),
                after.relativePath(), before.sha256(), after.sha256(), OWNER)));

        AssetAdoptionArtifactProducer.Result result = AssetAdoptionArtifactProducer.produce(coordination(),
            fixture.preAdmission(), stage(fixture, List.of(output)));

        assertNotNull(result);
    }

    @Test
    void rejectsAChangedSnapshotWithoutAnAcceptedChange() throws Exception {
        Fixture fixture = fixture("unreported-change", "post-unreported-change");
        var after = fixture.postAdmission().snapshot().manifest().entries().getFirst();
        AssetAdoptionArtifactProducer.LifecycleOutput output = new AssetAdoptionArtifactProducer.LifecycleOutput(
            "assets", OWNER, List.of(new AssetAdoptionArtifactProducer.Claim(after.relativePath(), OWNER)), List.of());

        assertThrows(MigrationException.class,
            () -> AssetAdoptionArtifactProducer.produce(coordination(), fixture.preAdmission(), stage(fixture,
                List.of(output))));
    }

    @Test
    void rejectsAnAcceptedChangeForAnUnchangedPath() throws Exception {
        Fixture fixture = fixture("extra-change", "post-extra-change", "same", "same");
        var before = fixture.preAdmission().snapshot().manifest().entries().getFirst();
        var after = fixture.postAdmission().snapshot().manifest().entries().getFirst();
        AssetAdoptionArtifactProducer.LifecycleOutput output = new AssetAdoptionArtifactProducer.LifecycleOutput(
            "assets", OWNER, List.of(new AssetAdoptionArtifactProducer.Claim(after.relativePath(), OWNER)),
            List.of(new AssetAdoptionArtifactProducer.AcceptedChange("noop", before.relativePath(),
                after.relativePath(), before.sha256(), after.sha256(), OWNER)));

        assertThrows(MigrationException.class,
            () -> AssetAdoptionArtifactProducer.produce(coordination(), fixture.preAdmission(), stage(fixture,
                List.of(output))));
    }

    @Test
    void rejectsBlockedIdentityThatIsAlreadyLive() {
        byte[] content = "live".getBytes(StandardCharsets.UTF_8);
        String hash = StorageSafety.sha256(content);
        AssetAdoptionArtifactProducer.Asset live = new AssetAdoptionArtifactProducer.Asset("owner", "flow", "same",
            Path.of("Blueprints/Flows/same.json"),
            new AssetAdoptionArtifactProducer.AssetState("LIVE", 1L, hash), "live-mutation", content, null);
        AssetAdoptionArtifactProducer.BlockedState blocked = new AssetAdoptionArtifactProducer.BlockedState("owner",
            "assets/.tombstones/flow/same.json", hash, Path.of("assets/.tombstones/flow/same.json"), null, null,
            1L, "blocked-mutation", "blocked", "flow", "same");

        assertThrows(IllegalArgumentException.class,
            () -> new AssetAdoptionArtifactProducer.Inventory("snapshot:hash", "{}", List.of(live), List.of(blocked)));
    }

    @Test
    void deletedLifecycleRecordsRequireAnAuxiliaryIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new AssetAdoptionArtifactProducer.LegacyDeletion(
            null, null, Path.of("Blueprints/Flows/deleted.json"), "a".repeat(64), OWNER,
            "assets/.tombstones/flow/deleted.json", 2L, "delete-2"));
    }

    @Test
    void preservesFamilySpecificAuxiliaryIdentitiesInThePortableSchema() {
        AssetAdoptionArtifactProducer.AuxiliaryKey json = new AssetAdoptionArtifactProducer.AuxiliaryKey(
            AssetAdoptionArtifactProducer.AuxiliaryKey.JSON_FAMILY, "npc_definition.tombstone", "guide");
        AssetAdoptionArtifactProducer.AuxiliaryKey flow = new AssetAdoptionArtifactProducer.AuxiliaryKey(
            AssetAdoptionArtifactProducer.AuxiliaryKey.FLOW_GRAPH_FAMILY, "tombstone:flow", "main");
        AssetAdoptionArtifactProducer.AuxiliaryKey explicit = new AssetAdoptionArtifactProducer.AuxiliaryKey(
            AssetAdoptionArtifactProducer.AuxiliaryKey.EXPLICIT_FAMILY, "custom.tombstone", "different-id");

        assertEquals("npc_definition.tombstone", json.type());
        assertEquals("tombstone:flow", flow.type());
        assertEquals("different-id", explicit.id());
    }

    @Test
    void bindsAcceptedQuarantineToTheMovedEvidenceBytesWithoutTreatingItAsALifecycleChange() throws Exception {
        byte[] original = "blocked-flow".getBytes(StandardCharsets.UTF_8);
        String hash = StorageSafety.sha256(original);
        Path preSource = Files.createDirectories(temporary.resolve("blocked-pre").resolve("assets").resolve("Blueprints/Flows"));
        Files.writeString(preSource.getParent().getParent().resolve("project.json"), "{}", StandardCharsets.UTF_8);
        Files.write(preSource.resolve("main.json"), original);
        Path postSource = Files.createDirectories(temporary.resolve("blocked-post").resolve("assets"));
        Files.writeString(postSource.resolve("project.json"), "{}", StandardCharsets.UTF_8);
        Path evidence = Files.createDirectories(postSource.getParent().resolve(".quarantine/migration/blocked/assets/Blueprints/Flows"));
        Files.write(evidence.resolve("main.json"), original);
        SnapshotService service = new SnapshotService(new MigrationFence());
        Path preRoot = preSource.getParent().getParent().getParent();
        Snapshot pre = service.create(preRoot, temporary.resolve("blocked-pre-export"),
            metadata("blocked-pre"), participantsWholeRoot(preRoot));
        Snapshot post = service.create(postSource.getParent(), temporary.resolve("blocked-post-export"),
            metadata("blocked-post"), participantsWholeRoot(postSource.getParent()));
        VerifiedSnapshotAdmission preAdmission = service.admitExported(pre.root());
        VerifiedSnapshotAdmission postAdmission = service.admitExported(post.root());
        QuarantineReport report = new QuarantineReport(List.of(new QuarantineRecord(
            "blocked", "MIGRATION.BLOCKED", "assets/Blueprints/Flows/main.json", "blocked", List.of(),
            "review", hash)));
        AssetAdoptionArtifactProducer.BlockedState blocked = new AssetAdoptionArtifactProducer.BlockedState(OWNER,
            ".quarantine/migration/blocked/assets/Blueprints/Flows/main.json", hash,
            Path.of(".quarantine/migration/blocked/assets/Blueprints/Flows/main.json"),
            Path.of("Blueprints/Flows/main.json"), Path.of("Blueprints/Flows/main.json"), 1L,
            "blocked-mutation", "blocked", "flow", "main");
        AssetAdoptionArtifactProducer.LifecycleOutput project = new AssetAdoptionArtifactProducer.LifecycleOutput(
            "managed", OWNER, List.of(new AssetAdoptionArtifactProducer.Claim("assets/project.json", OWNER)), List.of());
        AssetAdoptionArtifactProducer.StageOutput stage = new AssetAdoptionArtifactProducer.StageOutput(PLAN_HASH,
            preAdmission.metadata().snapshotId(), preAdmission.manifestHash(), postAdmission, List.of(project), report,
            report.acceptance(List.of("blocked"), "producer-test", Instant.parse("2026-08-24T00:00:00Z")), List.of(blocked));

        AssetAdoptionArtifactProducer.Result result = AssetAdoptionArtifactProducer.produce(coordination(),
            preAdmission, stage);

        assertEquals(1, result.inventory().blocked().size());
        assertTrue(result.evidence().stream().anyMatch(value ->
            value.originalPath().equals(".quarantine/migration/blocked/assets/Blueprints/Flows/main.json")));
    }

    @Test
    void preservesEveryAcceptedQuarantineRecordForOneSourceAsDurableEvidence() throws Exception {
        byte[] original = "blocked-flow".getBytes(StandardCharsets.UTF_8);
        String hash = StorageSafety.sha256(original);
        Path preSource = Files.createDirectories(temporary.resolve("blocked-multi-pre").resolve("assets")
            .resolve("Blueprints/Flows"));
        Files.writeString(preSource.getParent().getParent().resolve("project.json"), "{}", StandardCharsets.UTF_8);
        Files.write(preSource.resolve("main.json"), original);
        Path postSource = Files.createDirectories(temporary.resolve("blocked-multi-post").resolve("assets"));
        Files.writeString(postSource.resolve("project.json"), "{}", StandardCharsets.UTF_8);
        Path firstEvidence = Files.createDirectories(postSource.getParent().resolve(
            ".quarantine/migration/blocked-a/assets/Blueprints/Flows"));
        Path secondEvidence = Files.createDirectories(postSource.getParent().resolve(
            ".quarantine/migration/blocked-b/assets/Blueprints/Flows"));
        Files.write(firstEvidence.resolve("main.json"), original);
        Files.write(secondEvidence.resolve("main.json"), original);
        SnapshotService service = new SnapshotService(new MigrationFence());
        Path preRoot = preSource.getParent().getParent().getParent();
        Snapshot pre = service.create(preRoot, temporary.resolve("blocked-multi-pre-export"),
            metadata("blocked-multi-pre"), participantsWholeRoot(preRoot));
        Snapshot post = service.create(postSource.getParent(), temporary.resolve("blocked-multi-post-export"),
            metadata("blocked-multi-post"), participantsWholeRoot(postSource.getParent()));
        VerifiedSnapshotAdmission preAdmission = service.admitExported(pre.root());
        VerifiedSnapshotAdmission postAdmission = service.admitExported(post.root());
        QuarantineReport report = new QuarantineReport(List.of(
            new QuarantineRecord("blocked-a", "MIGRATION.FIRST",
                "assets/Blueprints/Flows/main.json", "first", List.of(), "review", hash),
            new QuarantineRecord("blocked-b", "MIGRATION.SECOND",
                "assets/Blueprints/Flows/main.json", "second", List.of(), "review", hash)));
        AssetAdoptionArtifactProducer.BlockedState blocked = new AssetAdoptionArtifactProducer.BlockedState(OWNER,
            ".quarantine/migration/blocked-a/assets/Blueprints/Flows/main.json", hash,
            Path.of(".quarantine/migration/blocked-a/assets/Blueprints/Flows/main.json"),
            Path.of("Blueprints/Flows/main.json"), Path.of("Blueprints/Flows/main.json"), 1L,
            "blocked-mutation", "first; second", "flow", "main");
        AssetAdoptionArtifactProducer.LifecycleOutput project = new AssetAdoptionArtifactProducer.LifecycleOutput(
            "managed", OWNER, List.of(new AssetAdoptionArtifactProducer.Claim("assets/project.json", OWNER)), List.of());
        AssetAdoptionArtifactProducer.StageOutput stage = new AssetAdoptionArtifactProducer.StageOutput(PLAN_HASH,
            preAdmission.metadata().snapshotId(), preAdmission.manifestHash(), postAdmission, List.of(project), report,
            report.acceptance(List.of("blocked-a", "blocked-b"), "producer-test",
                Instant.parse("2026-08-24T00:00:00Z")), List.of(blocked));

        AssetAdoptionArtifactProducer.Result result = AssetAdoptionArtifactProducer.produce(coordination(),
            preAdmission, stage);

        assertEquals(1, result.inventory().blocked().size());
        assertEquals(2, result.evidence().stream().filter(value ->
            value.originalPath().startsWith(".quarantine/migration/")).count());
    }

    private AssetAdoptionArtifactProducer.StageOutput stage(Fixture fixture,
                                                              List<AssetAdoptionArtifactProducer.LifecycleOutput> outputs) {
        QuarantineReport report = QuarantineReport.empty();
        return new AssetAdoptionArtifactProducer.StageOutput(PLAN_HASH,
            fixture.preAdmission().metadata().snapshotId(), fixture.preAdmission().manifestHash(),
            fixture.postAdmission(), outputs, report, report.acceptance(List.of(), "producer-test",
                Instant.parse("2026-08-24T00:00:00Z")), List.of());
    }

    private Fixture fixture(String preName, String postName) throws Exception {
        return fixture(preName, postName, "pre", "post");
    }

    private Fixture fixture(String preName, String postName, String preValue, String postValue) throws Exception {
        Path preSource = source(preName, preValue);
        Path postSource = source(postName, postValue);
        SnapshotMetadata preMetadata = metadata("pre-" + preName);
        SnapshotMetadata postMetadata = metadata("post-" + postName);
        SnapshotService service = new SnapshotService(new MigrationFence());
        Snapshot preSnapshot = service.create(preSource, temporary.resolve(preName + "-exported"), preMetadata,
            participants(preSource));
        Snapshot postSnapshot = service.create(postSource, temporary.resolve(postName + "-exported"), postMetadata,
            participants(postSource));
        return new Fixture(service.admitExported(preSnapshot.root()), service.admitExported(postSnapshot.root()));
    }

    private Path source(String name, String value) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(name + "-source").resolve("assets"));
        Files.writeString(root.resolve("project.json"), "{\"value\":\"" + value + "\"}\n", StandardCharsets.UTF_8);
        return root.getParent();
    }

    private PersistenceParticipantRegistry participants(Path source) {
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry();
        registry.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return OWNER;
            }

            @Override
            public Path root() {
                return source.resolve("assets");
            }
        });
        return registry;
    }

    private PersistenceParticipantRegistry participantsWholeRoot(Path source) {
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry();
        registry.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return OWNER;
            }

            @Override
            public Path root() {
                return source;
            }
        });
        return registry;
    }

    private SnapshotMetadata metadata(String snapshotId) {
        return new SnapshotMetadata(1, snapshotId, Instant.parse("2026-08-24T00:00:00Z"),
            "producer-test", "c".repeat(64), Map.of());
    }

    private Path coordination() throws Exception {
        return Files.createDirectories(temporary.resolve("coordination"));
    }

    private record Fixture(VerifiedSnapshotAdmission preAdmission, VerifiedSnapshotAdmission postAdmission) {
    }
}
