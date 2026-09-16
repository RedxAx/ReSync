package restudio.resync.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.diagnostic.DiagnosticSet;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.SnapshotId;
import restudio.resync.upgrade.AssetAdoptionArtifactProducer;
import restudio.resync.upgrade.ReplacementUpgrader;
import restudio.resync.upgrade.UpgradeApplyResult;
import restudio.resync.upgrade.UpgradeDryRunResult;
import restudio.resync.upgrade.UpgradeProposal;
import restudio.resync.upgrade.UpgradePlanner;
import restudio.resync.upgrade.UpgradeSourceWindow;
import restudio.resync.upgrade.UpgradeStatus;

class OfflineReplacementUpgradeEntrypointTest {
    @TempDir
    Path temporary;

    @Test
    void dryRunRetainsVerifiedSnapshotAndPlanWithoutWritingActivationMarker() throws IOException {
        Path source = source("dry-run");
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, null);

        UpgradeDryRunResult result = entrypoint.dryRun();

        assertEquals(UpgradeStatus.READY, result.status());
        assertTrue(result.sourceSnapshot().orElseThrow().verified());
        assertTrue(Files.exists(control(source).resolve("plans").resolve("dry-run.plan")));
        assertFalse(Files.exists(MigrationActivationMarker.markerPath(source)));
    }

    @Test
    void applyRejectsRetainedSnapshotWithVerifiedFlagButNonVerifiedState() throws IOException {
        Path source = source("retained-state-mismatch");
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, null, transformedStager());
        UpgradeDryRunResult retained = entrypoint.dryRun();
        Path state = retained.sourceSnapshot().orElseThrow().statePath();
        Files.writeString(state, Files.readString(state).replaceFirst("state=VERIFIED\\n", "state=FAILED\\n"));

        assertThrows(MigrationException.class, () -> entrypoint.apply(retained));
    }

    @Test
    void applyRejectsRetainedSnapshotWithVerifiedStateButNonEmptyFailures() throws IOException {
        Path source = source("retained-failures");
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, null, transformedStager());
        UpgradeDryRunResult retained = entrypoint.dryRun();
        Path state = retained.sourceSnapshot().orElseThrow().statePath();
        String content = Files.readString(state).replace("failures=0", "failures=1");
        Files.writeString(state, content + "failure=" + MigrationCanonical.encode("retained-failure") + '\n');

        assertThrows(MigrationException.class, () -> entrypoint.apply(retained));
    }

    @Test
    void applyRejectsRetainedSnapshotWithMetadataSidecarThatDoesNotMatchManifest() throws IOException {
        Path source = source("retained-metadata-mismatch");
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, null, transformedStager());
        UpgradeDryRunResult retained = entrypoint.dryRun();
        Snapshot snapshot = retained.sourceSnapshot().orElseThrow();
        Path metadata = ProductionSnapshotMetadataManifest.pathFor(snapshot.root());
        String content = Files.readString(metadata);
        String canonical = content.substring(0, content.lastIndexOf("metadata-hash="));
        String tampered = canonical.replace("manifest-hash=" + snapshot.manifest().manifestHash(),
            "manifest-hash=" + "0".repeat(64));
        Files.writeString(metadata, tampered + "metadata-hash=" + MigrationCanonical.sha256(tampered) + "\n");

        assertThrows(MigrationException.class, () -> entrypoint.apply(retained));
    }

    @Test
    void planOnlyComputesVerifiedPlanWithoutMutatingAnyUpgradePath() throws IOException {
        Path source = source("plan-only");
        String sourceDigest = TreeDigest.of(source);

        UpgradeDryRunResult result = planOnlyEntrypoint(source).planOnly();

        assertEquals(UpgradeStatus.READY, result.status());
        assertTrue(result.sourceSnapshot().orElseThrow().verified());
        assertEquals(sourceDigest, TreeDigest.of(source));
        assertFalse(Files.exists(control(source)));
        assertFalse(Files.exists(MigrationActivationMarker.markerPath(source)));
    }

    @Test
    void planOnlyPlanAndInvocationHashesMatchAuthenticatedApplyDryRun() throws IOException {
        Path source = source("plan-parity");

        UpgradeDryRunResult planOnly = planOnlyEntrypoint(source).planOnly();
        UpgradeDryRunResult apply = entrypoint(source, null).dryRun();

        assertEquals(planOnly.proposal().orElseThrow().plan().planHash(),
            apply.proposal().orElseThrow().plan().planHash());
        assertEquals(planOnly.proposal().orElseThrow().plan().invocationHash(),
            apply.proposal().orElseThrow().plan().invocationHash());
    }

    @Test
    void applyWithoutCompleteAuthorityFailsBeforeControlMutation() throws IOException {
        Path source = source("missing-authority");
        String sourceDigest = TreeDigest.of(source);

        assertThrows(MigrationException.class, () -> incompleteApplyEntrypoint(source, null, null).execute());

        assertEquals(sourceDigest, TreeDigest.of(source));
        assertFalse(Files.exists(control(source)));
    }

    @Test
    void applyWithPartialAuthorityFailsBeforeControlMutation() throws Exception {
        Path source = source("partial-authority");
        ProductionAuthorityTestSigner signer = new ProductionAuthorityTestSigner(ServerId.deterministic("partial-authority"), source);
        ProductionAuthorityTrustAnchor anchor = ProductionAuthorityTrustAnchor.pinned(signer.serverId(),
            "installation-partial-authority", ProductionAuthorityBundle.installAuthorityDigest(source),
            "authority-key-partial-authority", signer.signingPublicKey());
        String sourceDigest = TreeDigest.of(source);

        assertThrows(MigrationException.class, () -> incompleteApplyEntrypoint(source, anchor, null).execute());

        assertEquals(sourceDigest, TreeDigest.of(source));
        assertFalse(Files.exists(control(source)));
    }

    @Test
    void planOnlyRejectsAuthorityBoundRequest() throws Exception {
        AuthorityFixture fixture = authorityFixture("plan-authority-rejected");
        ActivationMarkerWriter.Binding binding = new ActivationMarkerWriter.Binding(
            fixture.validBundle(), fixture.anchor(), fixture.validGrant());

        assertThrows(IllegalArgumentException.class, () -> new OfflineReplacementUpgradeEntrypoint.Request(
            fixture.source(), fixture.control(), fixture.metadata(), fixture.window(), fixture.planner(),
            fixture.participants(), new DirectoryMigrationStager(), null, null, null, 0,
            fixture.migrationId(), ActivationMarkerWriter.replacementRoot(binding), fixture.anchor(),
            fixture.validGrant(), OfflineReplacementUpgradeEntrypoint.Request.Mode.PLAN_ONLY));
    }

    @Test
    void successfulApplyCommitsPlanAndRootWithBoundActivationMarker() throws IOException {
        Path source = source("success");
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, null, transformedStager());

        OfflineReplacementUpgradeEntrypoint.RunResult result = entrypoint.execute();
        MigrationResult migration = result.apply().migration().orElseThrow();

        assertEquals(result.apply().planHash().orElseThrow(), migration.staged().planHash());
        assertEquals("replacement", Files.readString(source.resolve("value.txt")));
        assertEquals(MigrationJournalState.COMMITTED, migration.finalState());
        assertTrue(Files.exists(MigrationActivationMarker.markerPath(source)));
    }

    @Test
    void stagedValidationFailureRollsBackSourceAndLeavesNoActivationMarker() throws IOException {
        Path source = source("failure");
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, staged -> {
            throw new MigrationException("staged validation failed");
        });

        OfflineReplacementUpgradeEntrypoint.RunResult result = entrypoint.execute();

        assertEquals(UpgradeStatus.FAILED, result.apply().status());
        assertFalse(result.apply().changed());
        assertEquals("legacy", Files.readString(source.resolve("value.txt")));
        assertFalse(Files.exists(MigrationActivationMarker.markerPath(source)));
        assertEquals(MigrationJournalState.ROLLED_BACK, MigrationJournal.open(control(source).resolve("journals").resolve("failure.journal")).currentState().orElseThrow());
    }

    @Test
    void legacyStagedMarkerIsRejectedBeforeActivation() throws IOException {
        Path source = source("legacy-marker");
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, null, (sourceSnapshot, stagingRoot, plan) -> {
            StagedMigration copied = new DirectoryMigrationStager().stage(sourceSnapshot, stagingRoot, plan);
            Files.writeString(MigrationActivationMarker.markerPath(copied.root()), "format=1\n");
            return new StagedMigration(copied.root(), copied.previousRoot(), copied.planHash(), TreeDigest.of(copied.root()));
        });

        OfflineReplacementUpgradeEntrypoint.RunResult result = entrypoint.execute();

        assertEquals(UpgradeStatus.FAILED, result.apply().status());
        assertFalse(result.apply().changed());
        assertEquals("legacy", Files.readString(source.resolve("value.txt")));
        assertFalse(Files.exists(MigrationActivationMarker.markerPath(source)));
        assertEquals(MigrationJournalState.ROLLED_BACK, MigrationJournal.open(control(source).resolve("journals").resolve("legacy-marker.journal")).currentState().orElseThrow());
    }

    @Test
    void runtimeLockRejectsOfflineUpgradeBeforeSnapshotOrPlanWrites() throws IOException {
        Path source = source("locked");
        Path control = control(source);
        Files.createDirectories(control);
        try (FileChannel channel = FileChannel.open(control.resolve(OfflineReplacementUpgradeEntrypoint.RUNTIME_LOCK_NAME), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            assertThrows(MigrationException.class, entrypoint(source, null)::dryRun);
        }
        assertFalse(Files.exists(control.resolve("plans")));
        assertFalse(Files.exists(MigrationActivationMarker.markerPath(source)));
    }

    @Test
    void expiredExactCommittedRetryReturnsAlreadyCommitted() throws Exception {
        AuthorityFixture fixture = authorityFixture("expired-committed");
        UpgradeDryRunResult retained = fixture.prepareCommitted();

        UpgradeApplyResult result = fixture.entrypoint(fixture.expiredBundle(), fixture.expiredGrant()).apply(retained);

        assertEquals(UpgradeStatus.ALREADY_COMMITTED, result.status());
        assertFalse(result.changed());
    }

    @Test
    void expiredChangedCommittedTupleFailsClosed() throws Exception {
        AuthorityFixture fixture = authorityFixture("expired-changed");
        UpgradeDryRunResult retained = fixture.prepareCommitted();
        AuthorityUseGrant changedGrant = fixture.expiredGrant("changed-grant");

        assertThrows(MigrationException.class,
            () -> fixture.entrypoint(fixture.expiredBundle(), changedGrant).apply(retained));
    }

    @Test
    void expiredInterruptedPrecommitFailsClosed() throws Exception {
        AuthorityFixture fixture = authorityFixture("expired-precommit");
        UpgradeDryRunResult retained = fixture.preparePrecommit();

        assertThrows(MigrationException.class,
            () -> fixture.entrypoint(fixture.expiredBundle(), fixture.expiredGrant()).apply(retained));
    }

    private AuthorityFixture authorityFixture(String name) throws Exception {
        Path source = source(name);
        SnapshotId snapshotId = SnapshotId.deterministic(name);
        SnapshotMetadata metadata = new SnapshotMetadata(1, snapshotId.canonicalText(),
            Instant.parse("2026-01-01T00:00:00Z"), "legacy-build", "b".repeat(64), Map.of());
        UpgradeSourceWindow window = new UpgradeSourceWindow(ReplacementUpgrader.VERSION, 1, "legacy-build", 2, "replacement-1");
        PersistenceParticipantRegistry participants = participants(source);
        String invocationHash = "1".repeat(64);
        MigrationOperation operation = new MigrationOperation("copy", "offline.copy", "value.txt", "value.txt", "", "");
        SnapshotManifest manifest = SnapshotManifest.scan(source, metadata, participants);
        MigrationPlan plan = new MigrationPlan(metadata.snapshotId(), manifest.manifestHash(), 1, 2,
            QuarantineReport.empty().reportHash(), List.of(operation), invocationHash);
        ServerId serverId = ServerId.deterministic(name);
        ProductionAuthorityTestSigner signer = new ProductionAuthorityTestSigner(serverId, source);
        String installationId = "installation-" + name;
        String authorityKeyId = "authority-key-" + name;
        ProductionAuthorityTrustAnchor anchor = ProductionAuthorityTrustAnchor.pinned(serverId, installationId,
            ProductionAuthorityBundle.installAuthorityDigest(source), authorityKeyId, signer.signingPublicKey());
        ProductionAuthorityBundle validBundle = ProductionAuthorityBundle.create(signer, anchor, snapshotId,
            Instant.now(), ContentHash.of("b".repeat(64)), 1, new CatalogVersion(1, 0),
            ContentHash.of("c".repeat(64)), 1, "d".repeat(64), 1);
        ProductionAuthorityBundle expiredBundle = ProductionAuthorityBundle.create(signer, anchor, snapshotId,
            Instant.EPOCH, ContentHash.of("b".repeat(64)), 1, new CatalogVersion(1, 0),
            ContentHash.of("c".repeat(64)), 1, "d".repeat(64), 1);
        String planPreimageHash = AuthorityUseGrant.planPreimageHash(plan.canonicalText());
        AuthorityUseGrant validGrant = AuthorityUseGrant.issue(validBundle, anchor, signer, source, name,
            invocationHash, planPreimageHash, Instant.now().minusSeconds(1), Instant.now().plusSeconds(3600), "valid-grant-" + name);
        AuthorityUseGrant expiredGrant = AuthorityUseGrant.issue(expiredBundle, anchor, signer, source, name,
            invocationHash, planPreimageHash, Instant.EPOCH, Instant.EPOCH.plusSeconds(1), "expired-grant-" + name);
        return new AuthorityFixture(source, control(source), metadata, window, participants, plan, operation,
            name, invocationHash, signer, anchor, validBundle, validGrant, expiredBundle, expiredGrant);
    }

    private record AuthorityFixture(
        Path source,
        Path control,
        SnapshotMetadata metadata,
        UpgradeSourceWindow window,
        PersistenceParticipantRegistry participants,
        MigrationPlan plan,
        MigrationOperation operation,
        String migrationId,
        String invocationHash,
        ProductionAuthorityTestSigner signer,
        ProductionAuthorityTrustAnchor anchor,
        ProductionAuthorityBundle validBundle,
        AuthorityUseGrant validGrant,
        ProductionAuthorityBundle expiredBundle,
        AuthorityUseGrant expiredGrant
    ) {
        private UpgradePlanner planner() {
            return (snapshot, ignored) -> new UpgradeProposal(
                new MigrationPlan(snapshot.metadata().snapshotId(), snapshot.manifest().manifestHash(), 1, 2,
                    QuarantineReport.empty().reportHash(), List.of(operation), invocationHash),
                QuarantineReport.empty(), new DiagnosticSet(List.of()));
        }

        private OfflineReplacementUpgradeEntrypoint entrypoint(
            ProductionAuthorityBundle bundle,
            AuthorityUseGrant grant
        ) {
            ActivationMarkerWriter.Binding binding = new ActivationMarkerWriter.Binding(bundle, anchor, grant);
            OfflineReplacementUpgradeEntrypoint.Request request = new OfflineReplacementUpgradeEntrypoint.Request(
                source,
                control,
                metadata,
                window,
                planner(),
                participants,
                OfflineReplacementUpgradeEntrypointTest.typedStager(new DirectoryMigrationStager(), migrationId,
                    source.getParent().resolve("typed-stage-evidence")),
                null,
                null,
                null,
                0,
                migrationId,
                ActivationMarkerWriter.replacementRoot(binding),
                anchor,
                grant);
            try {
                return new OfflineReplacementUpgradeEntrypoint(request.withProductionPersistenceCoordination(
                    coordination(source, migrationId)));
            } catch (IOException exception) {
                throw new IllegalStateException("Could Not Build Production Persistence Fixture", exception);
            }
        }

        private AuthorityUseGrant expiredGrant(String grantId) throws IOException {
            return AuthorityUseGrant.issue(expiredBundle, anchor, signer, source, migrationId,
                invocationHash, AuthorityUseGrant.planPreimageHash(plan.canonicalText()),
                Instant.EPOCH, Instant.EPOCH.plusSeconds(1), grantId);
        }

        private UpgradeDryRunResult retained() throws IOException {
            return entrypoint(validBundle, validGrant).dryRun();
        }

        private UpgradeDryRunResult prepareCommitted() throws IOException {
            UpgradeDryRunResult retained = retained();
            UpgradeProposal proposal = retained.proposal().orElseThrow();
            QuarantineReport report = proposal.quarantineReport();
            QuarantineAcceptance acceptance = report.accept("offline-upgrader", Instant.EPOCH);
            Path archive = control.resolve("archives").resolve(migrationId + "-source");
            copySource(source, archive);
            Files.writeString(source.resolve("value.txt"), "replacement");
            String replacementDigest = TreeDigest.of(source);
            String archivedDigest = TreeDigest.of(archive);
            StagedMigration active = new StagedMigration(source, Optional.of(archive), plan.planHash(), replacementDigest);
            StagedMigration evidenced = OfflineReplacementUpgradeEntrypointTest.typedEvidence(active,
                retained.sourceSnapshot().orElseThrow().root(), plan, report, acceptance,
                source.getParent().resolve("typed-stage-evidence"), migrationId);
            AcceptedStagePublisher.Result publication = new ProductionAcceptedStagePublisher(
                coordination(source, migrationId)).publish(new AcceptedStagePublisher.Publication(
                    migrationId,
                    retained.sourceSnapshot().orElseThrow(),
                    Optional.empty(),
                    plan.planHash(),
                    evidenced,
                    report,
                    acceptance,
                    evidenced.stageOutput().orElseThrow(),
                    ProductionAcceptedStagePublisher.CONTRACT_IDENTITY));
            ActivationMarkerWriter.replacementRoot(new ActivationMarkerWriter.Binding(expiredBundle, anchor, expiredGrant))
                .write(plan, active);
            MigrationJournal journal = MigrationJournal.create(
                control.resolve("journals").resolve(migrationId + ".journal"), migrationId, plan.planHash(),
                binding(proposal, acceptance, replacementDigest, archivedDigest)
                    .withPublicationBinding(publication.publicationBinding()));
            journal.transition(MigrationJournalState.PREPARED, "Prepared");
            journal.transition(MigrationJournalState.TRANSFORMING, "Transforming");
            journal.transition(MigrationJournalState.VALIDATING, "Validating");
            journal.transition(MigrationJournalState.STAGED, "Staged");
            journal.transition(MigrationJournalState.ACTIVATED, "Activated");
            journal.transition(MigrationJournalState.COMMITTED, "Committed");
            return retained;
        }

        private UpgradeDryRunResult preparePrecommit() throws IOException {
            UpgradeDryRunResult retained = retained();
            UpgradeProposal proposal = retained.proposal().orElseThrow();
            QuarantineAcceptance acceptance = proposal.quarantineReport().accept("offline-upgrader", Instant.EPOCH);
            MigrationJournal journal = MigrationJournal.create(
                control.resolve("journals").resolve(migrationId + ".journal"), migrationId, plan.planHash(),
                binding(proposal, acceptance, "", ""));
            journal.transition(MigrationJournalState.PREPARED, "Prepared");
            return retained;
        }

        private MigrationJournal.Binding binding(
            UpgradeProposal proposal,
            QuarantineAcceptance acceptance,
            String replacementDigest,
            String archivedDigest
        ) {
            return new MigrationJournal.Binding(
                plan.sourceSnapshotId(),
                plan.sourceManifestHash(),
                proposal.quarantineReport().reportHash(),
                acceptance.acceptanceHash(),
                replacementDigest,
                archivedDigest,
                expiredBundle.catalogContentChecksum().canonicalText(),
                expiredBundle.runtimeBindingManifestHash().canonicalText(),
                expiredBundle.runtimeBindingManifestVersion(),
                expiredBundle.readinessReportHash(),
                expiredBundle.readinessReportVersion(),
                expiredBundle,
                MigrationCanonical.sha256(anchor.canonicalBytes()),
                expiredGrant);
        }
    }

    private static void copySource(Path source, Path archive) throws IOException {
        Files.createDirectories(archive);
        Files.copy(source.resolve("value.txt"), archive.resolve("value.txt"));
        Files.copy(source.resolve("server-id"), archive.resolve("server-id"));
        Files.copy(source.resolve(ProductionAuthorityBundle.INSTALL_AUTHORITY_FILE),
            archive.resolve(ProductionAuthorityBundle.INSTALL_AUTHORITY_FILE));
    }

    private OfflineReplacementUpgradeEntrypoint entrypoint(Path source, StagedMigrationValidator validator) throws IOException {
        return entrypoint(source, validator, new DirectoryMigrationStager());
    }

    private OfflineReplacementUpgradeEntrypoint entrypoint(Path source, StagedMigrationValidator validator, MigrationStager stager) throws IOException {
        SnapshotId snapshotId = SnapshotId.deterministic(source.getFileName().toString());
        SnapshotMetadata metadata = new SnapshotMetadata(1, snapshotId.canonicalText(), Instant.parse("2026-01-01T00:00:00Z"), "legacy-build", "b".repeat(64), Map.of());
        UpgradeSourceWindow window = new UpgradeSourceWindow(ReplacementUpgrader.VERSION, 1, "legacy-build", 2, "replacement-1");
        PersistenceParticipantRegistry participants = participants(source);
        String invocationHash = "1".repeat(64);
        MigrationOperation operation = new MigrationOperation("copy", "offline.copy", "value.txt", "value.txt", "", "");
        SnapshotManifest manifest = SnapshotManifest.scan(source, metadata, participants);
        MigrationPlan authorityPlan = new MigrationPlan(metadata.snapshotId(), manifest.manifestHash(), 1, 2,
            QuarantineReport.empty().reportHash(), List.of(operation), invocationHash);
        try {
            ProductionAuthorityTestSigner signer = new ProductionAuthorityTestSigner(ServerId.deterministic(source.getFileName().toString()), source);
            ProductionAuthorityTrustAnchor anchor = ProductionAuthorityTrustAnchor.pinned(signer.serverId(),
                "installation-" + source.getFileName(), ProductionAuthorityBundle.installAuthorityDigest(source),
                "authority-key-" + source.getFileName(), signer.signingPublicKey());
            Instant now = Instant.now();
            ProductionAuthorityBundle bundle = ProductionAuthorityBundle.create(signer, anchor, snapshotId,
                now.minusSeconds(1), ContentHash.of("b".repeat(64)), 1,
                new CatalogVersion(1, 0), ContentHash.of("c".repeat(64)), 1, "d".repeat(64), 1);
            AuthorityUseGrant grant = AuthorityUseGrant.issue(bundle, anchor, signer, source,
                source.getFileName().toString(), invocationHash,
                AuthorityUseGrant.planPreimageHash(authorityPlan.canonicalText()),
                now.minusSeconds(1), now.plusSeconds(3600),
                "grant-" + source.getFileName());
            ActivationMarkerWriter.Binding authority = new ActivationMarkerWriter.Binding(bundle, anchor, grant);
            OfflineReplacementUpgradeEntrypoint.Request request = new OfflineReplacementUpgradeEntrypoint.Request(
                source,
                control(source),
                metadata,
                window,
                (snapshot, ignored) -> new UpgradeProposal(
                    new MigrationPlan(snapshot.metadata().snapshotId(), snapshot.manifest().manifestHash(), 1, 2,
                        QuarantineReport.empty().reportHash(), List.of(operation), invocationHash),
                    QuarantineReport.empty(),
                    new DiagnosticSet(List.of())),
                participants,
                typedStager(stager, source.getFileName().toString()),
                validator,
                null,
                null,
                0,
                source.getFileName().toString(),
                ActivationMarkerWriter.replacementRoot(authority),
                anchor,
                grant);
            return new OfflineReplacementUpgradeEntrypoint(request.withProductionPersistenceCoordination(
                coordination(source, source.getFileName().toString())));
        } catch (Exception exception) {
            throw new IllegalStateException("Could Not Build Signed Offline Upgrade Fixture", exception);
        }
    }

    private MigrationStager transformedStager() {
        return (sourceSnapshot, stagingRoot, plan) -> {
            StagedMigration copied = new DirectoryMigrationStager().stage(sourceSnapshot, stagingRoot, plan);
            Files.writeString(copied.root().resolve("value.txt"), "replacement");
            return new StagedMigration(copied.root(), copied.previousRoot(), copied.planHash(), TreeDigest.of(copied.root()));
        };
    }

    private OfflineReplacementUpgradeEntrypoint planOnlyEntrypoint(Path source) throws IOException {
        SnapshotId snapshotId = SnapshotId.deterministic(source.getFileName().toString());
        SnapshotMetadata metadata = new SnapshotMetadata(1, snapshotId.canonicalText(),
            Instant.parse("2026-01-01T00:00:00Z"), "legacy-build", "b".repeat(64), Map.of());
        UpgradeSourceWindow window = new UpgradeSourceWindow(ReplacementUpgrader.VERSION, 1, "legacy-build", 2, "replacement-1");
        PersistenceParticipantRegistry participants = participants(source);
        MigrationOperation operation = new MigrationOperation("copy", "offline.copy", "value.txt", "value.txt", "", "");
        UpgradePlanner planner = (snapshot, ignored) -> new UpgradeProposal(
            new MigrationPlan(snapshot.metadata().snapshotId(), snapshot.manifest().manifestHash(), 1, 2,
                QuarantineReport.empty().reportHash(), List.of(operation), "1".repeat(64)),
            QuarantineReport.empty(), new DiagnosticSet(List.of()));
        return new OfflineReplacementUpgradeEntrypoint(new OfflineReplacementUpgradeEntrypoint.Request(
            source, control(source), metadata, window, planner, participants, new DirectoryMigrationStager(),
            null, null, null, 0, source.getFileName().toString(), OfflineReplacementUpgradeEntrypoint.Request.Mode.PLAN_ONLY));
    }

    private OfflineReplacementUpgradeEntrypoint incompleteApplyEntrypoint(
        Path source,
        ProductionAuthorityTrustAnchor anchor,
        AuthorityUseGrant grant
    ) throws IOException {
        SnapshotId snapshotId = SnapshotId.deterministic(source.getFileName().toString());
        SnapshotMetadata metadata = new SnapshotMetadata(1, snapshotId.canonicalText(),
            Instant.parse("2026-01-01T00:00:00Z"), "legacy-build", "b".repeat(64), Map.of());
        UpgradeSourceWindow window = new UpgradeSourceWindow(ReplacementUpgrader.VERSION, 1, "legacy-build", 2, "replacement-1");
        PersistenceParticipantRegistry participants = participants(source);
        MigrationOperation operation = new MigrationOperation("copy", "offline.copy", "value.txt", "value.txt", "", "");
        UpgradePlanner planner = (snapshot, ignored) -> new UpgradeProposal(
            new MigrationPlan(snapshot.metadata().snapshotId(), snapshot.manifest().manifestHash(), 1, 2,
                QuarantineReport.empty().reportHash(), List.of(operation), "1".repeat(64)),
            QuarantineReport.empty(), new DiagnosticSet(List.of()));
        return new OfflineReplacementUpgradeEntrypoint(new OfflineReplacementUpgradeEntrypoint.Request(
            source, control(source), metadata, window, planner, participants, new DirectoryMigrationStager(),
            null, null, null, 0, source.getFileName().toString(), ActivationMarkerWriter.none(), anchor, grant));
    }

    private Path source(String name) throws IOException {
        Path source = Files.createDirectory(temporary.resolve(name));
        Files.writeString(source.resolve("value.txt"), "legacy");
        ServerId serverId = ServerId.deterministic(name);
        Files.writeString(source.resolve("server-id"), serverId.canonicalText() + "\n");
        Files.writeString(source.resolve(ProductionAuthorityBundle.INSTALL_AUTHORITY_FILE), "install-authority-" + name);
        return source;
    }

    private Path control(Path source) {
        return source.getParent().resolve(OfflineReplacementUpgradeEntrypoint.CONTROL_DIRECTORY_NAME);
    }

    private static PersistenceParticipantRegistry participants(Path source) {
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(new RebindableParticipant(source));
        return participants;
    }

    private static Path coordination(Path source, String migrationId) throws IOException {
        return Files.createDirectories(source.getParent().resolve("coordination").resolve(migrationId));
    }

    private MigrationStager typedStager(MigrationStager delegate, String migrationId) {
        return typedStager(delegate, migrationId, temporary.resolve("typed-stage-evidence"));
    }

    private static MigrationStager typedStager(MigrationStager delegate, String migrationId, Path evidenceBase) {
        return new MigrationStager() {
            @Override
            public StagedMigration stage(Path sourceRoot, Path stagingRoot, MigrationPlan plan) throws IOException {
                QuarantineReport report = QuarantineReport.empty();
                return typedEvidence(delegate.stage(sourceRoot, stagingRoot, plan), sourceRoot, plan, report,
                    report.accept("offline-stager", Instant.EPOCH), evidenceBase, migrationId);
            }

            @Override
            public StagedMigration stage(
                Path sourceRoot,
                Path stagingRoot,
                MigrationPlan plan,
                QuarantineReport report,
                QuarantineAcceptance acceptance
            ) throws IOException {
                return typedEvidence(delegate.stage(sourceRoot, stagingRoot, plan, report, acceptance), sourceRoot,
                    plan, report, acceptance, evidenceBase, migrationId);
            }
        };
    }

    private static StagedMigration typedEvidence(
        StagedMigration staged,
        Path sourceRoot,
        MigrationPlan plan,
        QuarantineReport report,
        QuarantineAcceptance acceptance,
        Path evidenceBase,
        String migrationId
    ) throws IOException {
        SnapshotService snapshots = new SnapshotService(new MigrationFence());
        VerifiedSnapshotAdmission sourceAdmission = snapshots.admitExported(sourceRoot);
        Path postStageRoot = evidenceBase.resolve(migrationId);
        deleteTree(postStageRoot);
        deleteTree(postStageRoot.resolveSibling(postStageRoot.getFileName() + ".manifest"));
        deleteTree(postStageRoot.resolveSibling(postStageRoot.getFileName() + ".state"));
        deleteTree(postStageRoot.resolveSibling(postStageRoot.getFileName() + ".metadata"));
        PersistenceParticipantRegistry evidenceParticipants = participants(staged.root());
        SnapshotMetadata postStageMetadata = new SnapshotMetadata(
            sourceAdmission.metadata().formatVersion(),
            SnapshotId.deterministic(migrationId + "-post-stage").canonicalText(),
            Instant.parse("2026-01-02T00:00:00Z"),
            "replacement-1",
            sourceAdmission.metadata().catalogChecksum(),
            sourceAdmission.metadata().extensionVersions());
        Snapshot postStage = snapshots.create(staged.root(), postStageRoot, postStageMetadata, evidenceParticipants);
        VerifiedSnapshotAdmission postStageAdmission = snapshots.admitExported(postStage.root());
        AssetAdoptionArtifactProducer.StageOutput output = new AssetAdoptionArtifactProducer.StageOutput(
            plan.planHash(), sourceAdmission.metadata().snapshotId(), sourceAdmission.manifestHash(),
            postStageAdmission, List.of(), report, acceptance, List.of());
        return staged.attachTypedEvidence(new StagedMigration.TypedEvidence(sourceAdmission, output));
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private static final class RebindableParticipant implements RebindablePersistenceParticipant {
        private final Path initialRoot;
        private Path activeRoot;

        private RebindableParticipant(Path root) {
            initialRoot = root.toAbsolutePath().normalize();
            activeRoot = initialRoot;
        }

        @Override
        public String owner() {
            return "core";
        }

        @Override
        public Path root() {
            return activeRoot;
        }

        @Override
        public Path rebindScope() {
            return initialRoot;
        }

        @Override
        public void flush() {
        }

        @Override
        public void quiesce() {
        }

        @Override
        public void resume() {
        }

        @Override
        public void rebind(Path activeRoot) throws IOException {
            this.activeRoot = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        }

        @Override
        public void healthCheck() {
        }
    }

}
