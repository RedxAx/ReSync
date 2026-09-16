package restudio.resync.migration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import restudio.resync.flow.diagnostic.DiagnosticSet;
import restudio.resync.upgrade.ReplacementUpgrader;
import restudio.resync.upgrade.UpgradeProposal;
import restudio.resync.upgrade.UpgradeSourceWindow;
import restudio.resync.upgrade.UpgradeStatus;

class VerifiedSnapshotAdmissionTest {
    @TempDir
    Path temporary;

    @Test
    void admitsAndCopiesExactExportedManifestAuthority() throws Exception {
        ExportFixture fixture = export("exact-copy");
        SnapshotService service = new SnapshotService(new MigrationFence());

        VerifiedSnapshotAdmission admission = service.admitExported(fixture.exported());
        Snapshot admitted = admission.snapshot();
        Snapshot copied = service.copyAdmitted(admission, temporary.resolve("retained"));

        assertEquals(fixture.metadata(), admission.metadata());
        assertEquals(fixture.snapshot().manifest().manifestHash(), admission.manifestHash());
        assertEquals(List.of("flow-storage", "resource-registry"), admitted.manifest().entries().stream()
            .map(SnapshotManifest.Entry::owner).distinct().sorted().toList());
        assertEquals(admitted.manifest().canonicalText(), copied.manifest().canonicalText());
        assertEquals(admitted.manifest().manifestHash(), copied.manifest().manifestHash());
        assertArrayEquals(Files.readAllBytes(admitted.manifestPath()), Files.readAllBytes(copied.manifestPath()));
        assertArrayEquals(Files.readAllBytes(admitted.statePath()), Files.readAllBytes(copied.statePath()));
        assertArrayEquals(Files.readAllBytes(ProductionSnapshotMetadataManifest.pathFor(admitted.root())),
            Files.readAllBytes(ProductionSnapshotMetadataManifest.pathFor(copied.root())));

        SnapshotManifest rescanned = SnapshotManifest.scan(admitted.root(), admitted.metadata(),
            admission.participantRegistry(admitted.root()));
        assertEquals(admitted.manifest().canonicalText(), rescanned.canonicalText());
        assertEquals(admitted.manifest().manifestHash(), rescanned.manifestHash());
        UpgradeSourceWindow window = window(copied.metadata());
        OfflineReplacementUpgradeEntrypoint.Request applyRequest = new OfflineReplacementUpgradeEntrypoint.Request(
            copied.root(), control(copied.root()), copied.metadata(), window, (snapshot, ignored) -> proposal(snapshot, window),
            new PersistenceParticipantRegistry(), new DirectoryMigrationStager(), null, null, null, 0,
            "retained-apply", OfflineReplacementUpgradeEntrypoint.Request.Mode.APPLY_OR_RESUME)
            .withVerifiedSource(admission);
        SnapshotManifest rebound = SnapshotManifest.scan(copied.root(), copied.metadata(), applyRequest.participants());
        assertEquals(admitted.manifest().canonicalText(), rebound.canonicalText());
        assertEquals(admitted.manifest().manifestHash(), rebound.manifestHash());
    }

    @Test
    void planOnlyConsumesExactManifestWithoutSyntheticOwner() throws Exception {
        ExportFixture fixture = export("plan-only");
        SnapshotService service = new SnapshotService(new MigrationFence());
        VerifiedSnapshotAdmission admission = service.admitExported(fixture.exported());
        AtomicReference<Set<String>> observedOwners = new AtomicReference<>();
        AtomicInteger plans = new AtomicInteger();
        UpgradeSourceWindow window = window(fixture.metadata());
        var planner = new restudio.resync.upgrade.UpgradePlanner() {
            @Override
            public UpgradeProposal plan(Snapshot snapshot, UpgradeSourceWindow ignored) {
                plans.incrementAndGet();
                observedOwners.set(snapshot.manifest().entries().stream().map(SnapshotManifest.Entry::owner)
                    .collect(java.util.stream.Collectors.toSet()));
                return proposal(snapshot, window);
            }
        };
        OfflineReplacementUpgradeEntrypoint.Request request = new OfflineReplacementUpgradeEntrypoint.Request(
            fixture.exported(), control(fixture.exported()), fixture.metadata(), window, planner,
            new PersistenceParticipantRegistry(), new DirectoryMigrationStager(), null, null, null, 0,
            "verified-plan", OfflineReplacementUpgradeEntrypoint.Request.Mode.PLAN_ONLY).withVerifiedSource(admission);

        var result = new OfflineReplacementUpgradeEntrypoint(request).planOnly();

        assertEquals(UpgradeStatus.READY, result.status());
        assertEquals(2, plans.get());
        assertEquals(Set.of("flow-storage", "resource-registry"), observedOwners.get());
        assertEquals(admission.manifestHash(), result.proposal().orElseThrow().plan().sourceManifestHash());
        assertFalse(Files.exists(control(fixture.exported())));
    }

    @Test
    void payloadTamperingIsRejectedBeforePlanningOrControlMutation() throws Exception {
        ExportFixture fixture = export("payload-tamper");
        SnapshotService service = new SnapshotService(new MigrationFence());
        VerifiedSnapshotAdmission admission = service.admitExported(fixture.exported());
        AtomicInteger plans = new AtomicInteger();
        UpgradeSourceWindow window = window(fixture.metadata());
        OfflineReplacementUpgradeEntrypoint.Request request = new OfflineReplacementUpgradeEntrypoint.Request(
            fixture.exported(), control(fixture.exported()), fixture.metadata(), window, (snapshot, ignored) -> {
                plans.incrementAndGet();
                return proposal(snapshot, window);
            }, new PersistenceParticipantRegistry(), new DirectoryMigrationStager(), null, null, null, 0,
            "payload-tamper", OfflineReplacementUpgradeEntrypoint.Request.Mode.PLAN_ONLY).withVerifiedSource(admission);
        Files.writeString(fixture.exported().resolve("graphs/main.json"), "tampered", StandardCharsets.UTF_8);

        assertThrows(MigrationException.class, () -> new OfflineReplacementUpgradeEntrypoint(request).planOnly());
        assertEquals(0, plans.get());
        assertFalse(Files.exists(control(fixture.exported())));
    }

    @Test
    void sidecarTamperingIsRejectedByAdmissionAndReverification() throws Exception {
        SnapshotService service = new SnapshotService(new MigrationFence());
        ExportFixture stateFixture = export("state-tamper");
        VerifiedSnapshotAdmission stateAdmission = service.admitExported(stateFixture.exported());
        Files.writeString(stateFixture.snapshot().statePath(), "state=FAILED\nverified=true\nmanifest-hash="
            + stateAdmission.manifestHash() + "\nfailures=0\n", StandardCharsets.UTF_8);
        assertThrows(MigrationException.class, stateAdmission::snapshot);

        ExportFixture manifestFixture = export("manifest-tamper");
        Files.writeString(manifestFixture.snapshot().manifestPath(),
            Files.readString(manifestFixture.snapshot().manifestPath(), StandardCharsets.UTF_8) + "unexpected\n",
            StandardCharsets.UTF_8);
        assertThrows(MigrationException.class, () -> service.admitExported(manifestFixture.exported()));

        ExportFixture metadataFixture = export("metadata-tamper");
        Path metadataPath = ProductionSnapshotMetadataManifest.pathFor(metadataFixture.exported());
        Files.writeString(metadataPath, Files.readString(metadataPath, StandardCharsets.UTF_8) + "unexpected\n",
            StandardCharsets.UTF_8);
        assertThrows(MigrationException.class, () -> service.admitExported(metadataFixture.exported()));
    }

    private ExportFixture export(String name) throws IOException {
        Path source = Files.createDirectory(temporary.resolve(name + "-source"));
        Path graphs = Files.createDirectory(source.resolve("graphs"));
        Path resources = Files.createDirectory(source.resolve("resources"));
        Files.writeString(graphs.resolve("main.json"), "graph", StandardCharsets.UTF_8);
        Files.writeString(resources.resolve("menu.json"), "resource", StandardCharsets.UTF_8);
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(participant("flow-storage", graphs));
        participants.register(participant("resource-registry", resources));
        SnapshotMetadata metadata = new SnapshotMetadata(1, name, Instant.parse("2026-08-23T00:00:00Z"),
            "legacy-build", "a".repeat(64), Map.of("extension.example", "1.0.0"));
        Path exported = temporary.resolve(name + "-exported");
        Snapshot snapshot = new SnapshotService(new MigrationFence()).create(source, exported, metadata, participants);
        return new ExportFixture(exported, metadata, snapshot);
    }

    private static PersistenceParticipant participant(String owner, Path root) {
        return new PersistenceParticipant() {
            @Override
            public String owner() {
                return owner;
            }

            @Override
            public Path root() {
                return root;
            }
        };
    }

    private static UpgradeSourceWindow window(SnapshotMetadata metadata) {
        return new UpgradeSourceWindow(ReplacementUpgrader.VERSION, metadata.formatVersion(), metadata.build(),
            2, "replacement-1");
    }

    private static UpgradeProposal proposal(Snapshot snapshot, UpgradeSourceWindow window) {
        return new UpgradeProposal(new MigrationPlan(snapshot.metadata().snapshotId(),
            snapshot.manifest().manifestHash(), window.sourceFormatVersion(), window.targetFormatVersion(),
            QuarantineReport.empty().reportHash(), List.of(), "1".repeat(64)), QuarantineReport.empty(),
            new DiagnosticSet(List.of()));
    }

    private static Path control(Path source) {
        return source.getParent().resolve(OfflineReplacementUpgradeEntrypoint.CONTROL_DIRECTORY_NAME);
    }

    private record ExportFixture(Path exported, SnapshotMetadata metadata, Snapshot snapshot) {
    }
}
