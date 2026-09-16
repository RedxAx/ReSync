package restudio.resync.migration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.SnapshotId;

class ProductionAuthorityBundlePersistenceParticipantTest {
    @TempDir
    Path temporary;

    @Test
    void freshBootstrapAllowsShutdownLifecycleButHealthAndReadinessRemainStrict() throws Exception {
        Fixture fixture = fixture("fresh");
        ProductionAuthorityBundlePersistenceParticipant participant =
            new ProductionAuthorityBundlePersistenceParticipant(fixture.root(), fixture.authority(), true);

        participant.flush();
        participant.quiesce();
        participant.resume();
        assertTrue(participant.health().regenerationRequired());
        assertThrows(MigrationException.class, participant::healthCheck);
        assertThrows(MigrationException.class, participant::readinessCheck);
    }

    @Test
    void ownershipIndexMatchesAuthoritySubtreeOwnership() throws Exception {
        Fixture fixture = fixture("ownership");
        ProductionAuthorityBundlePersistenceParticipant participant =
            new ProductionAuthorityBundlePersistenceParticipant(fixture.root(), fixture.authority(), false, true);
        PersistenceOwnershipContext context = new PersistenceOwnershipContext(fixture.root(), participant.root());
        PersistenceOwnershipIndex index = participant.ownershipIndex(context);
        Path owned = participant.root().resolve(ProductionAuthorityBundle.AUTHORITY_FILE);
        byte[] retained = "retained-authority-bundle".getBytes(StandardCharsets.UTF_8);
        Path retainedPath = writeQuarantine(fixture.root(), retained);
        Path rejected = fixture.root().resolve("authority-sibling");
        Path rejectedQuarantine = retainedPath.resolveSibling(retainedPath.getFileName().toString().replace(".json", ".JSON"));

        assertTrue(participant.owns(owned));
        assertTrue(index.owns(context.relativeToSource(owned)));
        assertTrue(participant.owns(participant.root()));
        assertTrue(index.owns(context.relativeToSource(participant.root())));
        assertTrue(participant.owns(retainedPath));
        assertTrue(index.owns(context.relativeToSource(retainedPath)));
        assertFalse(participant.owns(rejectedQuarantine));
        assertFalse(index.owns(context.relativeToSource(rejectedQuarantine)));
        assertFalse(participant.owns(rejected));
        assertFalse(index.owns(context.relativeToSource(rejected)));
    }

    @Test
    void validQuarantineArtifactsAreRetainedAndVerifiedByRawContentHash() throws Exception {
        Fixture fixture = fixture("valid-quarantine");
        byte[] retained = "retained-authority-bundle".getBytes(StandardCharsets.UTF_8);
        Path retainedPath = writeQuarantine(fixture.root(), retained);

        ProductionAuthorityBundlePersistenceParticipant participant =
            new ProductionAuthorityBundlePersistenceParticipant(fixture.root(), fixture.authority(), false, true);

        assertTrue(participant.health().available());
        participant.healthCheck();
        assertArrayEquals(retained, Files.readAllBytes(retainedPath));
        assertEquals(MigrationCanonical.sha256(retained) + ".json", retainedPath.getFileName().toString());
    }

    @Test
    void rejectsQuarantineArtifactsWithMismatchedContentHash() throws Exception {
        Fixture fixture = fixture("quarantine-mismatch");
        Path quarantine = Files.createDirectories(fixture.root().resolve(".quarantine/authority-bundle"));
        Files.write(quarantine.resolve("0".repeat(64) + ".json"),
            "different-content".getBytes(StandardCharsets.UTF_8));

        assertThrows(MigrationException.class,
            () -> new ProductionAuthorityBundlePersistenceParticipant(fixture.root(), fixture.authority(), false, true));
    }

    @Test
    void rejectsUnknownQuarantineArtifactsAndNonRegularEntries() throws Exception {
        Fixture unknownFixture = fixture("quarantine-unknown");
        Path unknownQuarantine = Files.createDirectories(unknownFixture.root().resolve(".quarantine/authority-bundle"));
        Files.writeString(unknownQuarantine.resolve("unexpected.json"), "unexpected", StandardCharsets.UTF_8);
        assertThrows(MigrationException.class,
            () -> new ProductionAuthorityBundlePersistenceParticipant(unknownFixture.root(), unknownFixture.authority(), false, true));

        Fixture directoryFixture = fixture("quarantine-directory");
        Path directoryQuarantine = Files.createDirectories(directoryFixture.root().resolve(".quarantine/authority-bundle"));
        Files.createDirectory(directoryQuarantine.resolve("nested"));
        assertThrows(MigrationException.class,
            () -> new ProductionAuthorityBundlePersistenceParticipant(directoryFixture.root(), directoryFixture.authority(), false, true));
    }

    @Test
    void rejectsMalformedQuarantineHierarchy() throws Exception {
        Fixture rootFileFixture = fixture("quarantine-root-file");
        Files.writeString(rootFileFixture.root().resolve(".quarantine"), "not-a-directory", StandardCharsets.UTF_8);
        assertThrows(MigrationException.class,
            () -> new ProductionAuthorityBundlePersistenceParticipant(rootFileFixture.root(), rootFileFixture.authority(), false, true));

        Fixture directoryFileFixture = fixture("quarantine-directory-file");
        Files.createDirectory(directoryFileFixture.root().resolve(".quarantine"));
        Files.writeString(directoryFileFixture.root().resolve(".quarantine/authority-bundle"), "not-a-directory",
            StandardCharsets.UTF_8);
        assertThrows(MigrationException.class,
            () -> new ProductionAuthorityBundlePersistenceParticipant(directoryFileFixture.root(),
                directoryFileFixture.authority(), false, true));
    }

    @Test
    void anchorOnlyParticipantIsHealthyAndReadyAcrossRestarts() throws Exception {
        Fixture fixture = fixture("anchor-only");
        ProductionAuthorityBundlePersistenceParticipant participant =
            new ProductionAuthorityBundlePersistenceParticipant(fixture.root(), fixture.authority(), false, true);

        assertTrue(participant.health().available());
        participant.healthCheck();
        participant.readinessCheck();
        participant.flush();
        participant.quiesce();
        participant.resume();

        ProductionAuthorityBundlePersistenceParticipant restarted =
            new ProductionAuthorityBundlePersistenceParticipant(fixture.root(), fixture.authority(), false, false);
        assertTrue(restarted.health().available());
        restarted.healthCheck();
        restarted.readinessCheck();
    }

    @Test
    void explicitBundleRequirementRejectsMissingBundle() throws Exception {
        Fixture fixture = fixture("required");
        ProductionAuthorityBundlePersistenceParticipant participant =
            new ProductionAuthorityBundlePersistenceParticipant(fixture.root(), fixture.authority(), true, false);

        assertTrue(participant.bundleRequired());
        assertTrue(participant.health().regenerationRequired());
        assertThrows(MigrationException.class, participant::healthCheck);
        assertThrows(MigrationException.class, participant::readinessCheck);
    }

    @Test
    void defaultConstructorRejectsExistingInstallMissingBundle() throws Exception {
        Fixture fixture = fixture("existing");
        ProductionAuthorityBundlePersistenceParticipant participant =
            new ProductionAuthorityBundlePersistenceParticipant(fixture.root(), fixture.authority());

        assertThrows(MigrationException.class, participant::flush);
        assertThrows(MigrationException.class, participant::quiesce);
        assertThrows(MigrationException.class, participant::resume);
    }

    @Test
    void existingHealthyParticipantRebindsToEmptyDerivedCandidateButHealthRemainsStrict() throws Exception {
        Fixture fixture = healthyFixture("rebind-empty");
        ProductionAuthorityBundlePersistenceParticipant participant =
            new ProductionAuthorityBundlePersistenceParticipant(fixture.root(), fixture.authority());
        assertTrue(participant.health().available());

        participant.quiesce();
        Path candidate = Files.createDirectory(temporary.resolve("rebind-empty-candidate"));
        Files.createDirectory(candidate.resolve(ProductionAuthorityBundlePersistenceParticipant.DIRECTORY));

        participant.rebind(candidate);

        assertEquals(candidate.resolve(ProductionAuthorityBundlePersistenceParticipant.DIRECTORY)
            .toAbsolutePath().normalize(), participant.root());
        participant.resume();
        participant.flush();
        participant.quiesce();
        participant.resume();
        assertTrue(participant.health().regenerationRequired());
        assertThrows(MigrationException.class, participant::healthCheck);
        assertThrows(MigrationException.class, participant::readinessCheck);
    }

    @Test
    void freshBootstrapRejectsExtraAuthorityFiles() throws Exception {
        Fixture fixture = fixture("extra");
        Files.writeString(fixture.root().resolve(ProductionAuthorityBundlePersistenceParticipant.DIRECTORY)
            .resolve("unexpected"), "extra", StandardCharsets.UTF_8);
        ProductionAuthorityBundlePersistenceParticipant participant =
            new ProductionAuthorityBundlePersistenceParticipant(fixture.root(), fixture.authority(), true);

        assertThrows(MigrationException.class, participant::flush);
    }

    @Test
    void rebindAcceptsIdenticalCanonicalTrustAnchorEvidenceAndPreservesIt() throws Exception {
        Fixture fixture = healthyFixture("rebind-anchor-evidence");
        ProductionAuthorityBundlePersistenceParticipant participant =
            new ProductionAuthorityBundlePersistenceParticipant(fixture.root(), fixture.authority());
        participant.quiesce();

        Path candidate = healthyCandidate(fixture, "rebind-anchor-evidence");
        Path authorityRoot = candidate.resolve(ProductionAuthorityBundlePersistenceParticipant.DIRECTORY);
        String canonical = Files.readString(authorityRoot.resolve(
            ProductionAuthorityBundlePersistenceParticipant.TRUST_ANCHOR_FILE));
        Path firstEvidence = authorityRoot.resolve(
            ProductionAuthorityBundlePersistenceParticipant.TRUST_ANCHOR_FILE
                + ".00000000-0000-0000-0000-000000000001.tmp");
        Path secondEvidence = authorityRoot.resolve(
            ProductionAuthorityBundlePersistenceParticipant.TRUST_ANCHOR_FILE
                + ".00000000-0000-0000-0000-000000000002.tmp");
        Files.writeString(firstEvidence, canonical, StandardCharsets.UTF_8);
        Files.writeString(secondEvidence, canonical, StandardCharsets.UTF_8);

        participant.rebind(candidate);

        assertEquals(authorityRoot.toAbsolutePath().normalize(), participant.root());
        assertEquals(canonical, Files.readString(firstEvidence));
        assertEquals(canonical, Files.readString(secondEvidence));
        participant.resume();
        participant.healthCheck();
    }

    @Test
    void rebindRejectsTrustAnchorEvidenceWithoutAnActiveAnchor() throws Exception {
        Fixture fixture = healthyFixture("rebind-anchor-missing");
        ProductionAuthorityBundlePersistenceParticipant participant =
            new ProductionAuthorityBundlePersistenceParticipant(fixture.root(), fixture.authority());
        participant.quiesce();

        Path candidate = healthyCandidate(fixture, "rebind-anchor-missing");
        Path authorityRoot = candidate.resolve(ProductionAuthorityBundlePersistenceParticipant.DIRECTORY);
        Path activeAnchor = authorityRoot.resolve(ProductionAuthorityBundlePersistenceParticipant.TRUST_ANCHOR_FILE);
        String canonical = Files.readString(activeAnchor);
        Files.delete(activeAnchor);
        Path evidence = authorityRoot.resolve(
            ProductionAuthorityBundlePersistenceParticipant.TRUST_ANCHOR_FILE
                + ".00000000-0000-0000-0000-000000000003.tmp");
        Files.writeString(evidence, canonical, StandardCharsets.UTF_8);

        assertThrows(MigrationException.class, () -> participant.rebind(candidate));
        assertTrue(Files.exists(evidence));
    }

    @Test
    void rebindRejectsMalformedAndConflictingTrustAnchorEvidence() throws Exception {
        Fixture malformedFixture = healthyFixture("rebind-anchor-malformed");
        ProductionAuthorityBundlePersistenceParticipant malformedParticipant =
            new ProductionAuthorityBundlePersistenceParticipant(malformedFixture.root(), malformedFixture.authority());
        malformedParticipant.quiesce();
        Path malformedCandidate = healthyCandidate(malformedFixture, "rebind-anchor-malformed");
        Path malformedEvidence = malformedCandidate
            .resolve(ProductionAuthorityBundlePersistenceParticipant.DIRECTORY)
            .resolve(ProductionAuthorityBundlePersistenceParticipant.TRUST_ANCHOR_FILE
                + ".00000000-0000-0000-0000-00000000000A.tmp");
        Files.writeString(malformedEvidence, malformedFixture.authority().trustAnchor().canonical(),
            StandardCharsets.UTF_8);
        assertThrows(MigrationException.class, () -> malformedParticipant.rebind(malformedCandidate));

        Fixture conflictFixture = healthyFixture("rebind-anchor-conflict");
        ProductionAuthorityBundlePersistenceParticipant conflictParticipant =
            new ProductionAuthorityBundlePersistenceParticipant(conflictFixture.root(), conflictFixture.authority());
        conflictParticipant.quiesce();
        Path conflictCandidate = healthyCandidate(conflictFixture, "rebind-anchor-conflict");
        Path conflictAuthorityRoot = conflictCandidate.resolve(ProductionAuthorityBundlePersistenceParticipant.DIRECTORY);
        Path conflictEvidence = conflictAuthorityRoot.resolve(
            ProductionAuthorityBundlePersistenceParticipant.TRUST_ANCHOR_FILE
                + ".00000000-0000-0000-0000-000000000004.tmp");
        ProductionAuthorityTrustAnchor conflictingAnchor = ProductionAuthorityTrustAnchor.pinned(
            conflictFixture.authority().trustAnchor().serverId(),
            conflictFixture.authority().trustAnchor().installationId() + "-conflict",
            conflictFixture.authority().trustAnchor().installAuthorityHash(),
            conflictFixture.authority().trustAnchor().authorityKeyId(),
            conflictFixture.authority().trustAnchor().publicKey());
        Files.writeString(conflictEvidence, conflictingAnchor.canonical(), StandardCharsets.UTF_8);

        assertThrows(MigrationException.class, () -> conflictParticipant.rebind(conflictCandidate));
        assertTrue(Files.exists(conflictEvidence));
    }

    private Fixture fixture(String name) throws Exception {
        Path root = Files.createDirectory(temporary.resolve(name));
        ServerId serverId = ServerId.deterministic(name + "-server");
        Files.writeString(root.resolve("server-id"), serverId.canonicalText() + "\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve(ProductionAuthorityBundle.INSTALL_AUTHORITY_FILE), "authority-" + name,
            StandardCharsets.UTF_8);
        ProductionAuthorityTestSigner signer = new ProductionAuthorityTestSigner(serverId, root);
        Path authorityRoot = Files.createDirectory(root.resolve(ProductionAuthorityBundlePersistenceParticipant.DIRECTORY));
        ProductionAuthorityBundle.TrustedAuthority authority = ProductionAuthorityBundle.TrustedAuthority.from(signer);
        Files.writeString(authorityRoot.resolve(ProductionAuthorityBundlePersistenceParticipant.TRUST_ANCHOR_FILE),
            authority.trustAnchor().canonical(), StandardCharsets.UTF_8);
        return new Fixture(root, authority, signer);
    }

    private Path writeQuarantine(Path root, byte[] content) throws Exception {
        Path quarantine = Files.createDirectories(root.resolve(".quarantine/authority-bundle"));
        return Files.write(quarantine.resolve(MigrationCanonical.sha256(content) + ".json"), content);
    }

    private Path healthyCandidate(Fixture fixture, String name) throws Exception {
        Path candidate = Files.createDirectory(temporary.resolve(name + "-candidate"));
        Files.copy(fixture.root().resolve("server-id"), candidate.resolve("server-id"));
        Files.copy(fixture.root().resolve(ProductionAuthorityBundle.INSTALL_AUTHORITY_FILE),
            candidate.resolve(ProductionAuthorityBundle.INSTALL_AUTHORITY_FILE));
        Path sourceAuthorityRoot = fixture.root().resolve(ProductionAuthorityBundlePersistenceParticipant.DIRECTORY);
        Path candidateAuthorityRoot = Files.createDirectory(
            candidate.resolve(ProductionAuthorityBundlePersistenceParticipant.DIRECTORY));
        Files.copy(sourceAuthorityRoot.resolve(ProductionAuthorityBundlePersistenceParticipant.TRUST_ANCHOR_FILE),
            candidateAuthorityRoot.resolve(ProductionAuthorityBundlePersistenceParticipant.TRUST_ANCHOR_FILE));
        Files.copy(sourceAuthorityRoot.resolve(ProductionAuthorityBundle.AUTHORITY_FILE),
            candidateAuthorityRoot.resolve(ProductionAuthorityBundle.AUTHORITY_FILE));
        return candidate;
    }

    private Fixture healthyFixture(String name) throws Exception {
        Fixture fixture = fixture(name);
        ServerId serverId = ServerId.deterministic(name + "-server");
        ProductionAuthorityBundle bundle = ProductionAuthorityBundle.create(
            fixture.signer(), serverId, SnapshotId.deterministic(name + "-snapshot"), Instant.now(),
            ContentHash.of("a".repeat(64)), 1, new CatalogVersion(1, 0), ContentHash.of("b".repeat(64)), 1,
            "c".repeat(64), 1);
        bundle.write(ProductionAuthorityBundle.path(fixture.root()));
        return fixture;
    }

    private record Fixture(Path root, ProductionAuthorityBundle.TrustedAuthority authority,
                           ProductionAuthorityTestSigner signer) {
    }
}
