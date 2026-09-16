package restudio.resync.migration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.SnapshotId;

class ProductionAuthorityTrustTest {
    @TempDir
    Path temporary;

    @Test
    void forgedBundleKeyCannotReplaceTheTrustedInstallationKey() throws Exception {
        Fixture fixture = fixture("forged-key");
        KeyPair forgedKey = KeyPairGenerator.getInstance(ProductionAuthorityBundle.SIGNATURE_ALGORITHM).generateKeyPair();
        String forgedPublicKey = Base64.getEncoder().encodeToString(forgedKey.getPublic().getEncoded());
        Map<String, Object> unsigned = new LinkedHashMap<>(fixture.bundle().canonicalValue());
        unsigned.remove("signature");
        unsigned.remove("bundleHash");
        unsigned.put("signingPublicKey", forgedPublicKey);
        Signature signature = Signature.getInstance(ProductionAuthorityBundle.SIGNATURE_ALGORITHM);
        signature.initSign(forgedKey.getPrivate());
        signature.update(JsonValue.fromJava(unsigned).canonicalBytes());
        ProductionAuthorityBundle forged = ProductionAuthorityBundle.create(
            fixture.bundle().serverId(), fixture.bundle().snapshotId(), fixture.bundle().timestamp(),
            fixture.bundle().catalogContentChecksum(), fixture.bundle().catalogGeneration(),
            fixture.bundle().catalogContractVersion(), fixture.bundle().runtimeBindingManifestHash(),
            fixture.bundle().runtimeBindingManifestVersion(), fixture.bundle().readinessReportHash(),
            fixture.bundle().readinessReportVersion(), fixture.bundle().installAuthorityHash(), forgedPublicKey,
            Base64.getEncoder().encodeToString(signature.sign()));

        assertFalse(forged.verifySignature(fixture.context()));
        assertFalse(forged.verifySignature(fixture.root(), fixture.trustedAuthority()));
    }

    @Test
    void unsignedActivationBindingFailsClosed() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("unsigned"));
        MigrationPlan plan = new MigrationPlan("snapshot", "a".repeat(64), 1, 2, "b".repeat(64), List.of());
        StagedMigration staged = new StagedMigration(root, Optional.empty(), plan.planHash(), "c".repeat(64));
        ActivationMarkerWriter.Binding binding = new ActivationMarkerWriter.Binding(
            "d".repeat(64), "e".repeat(64), 1, "f".repeat(64), 1);

        assertThrows(MigrationException.class, () -> ActivationMarkerWriter.replacementRoot(binding).write(plan, staged));
    }

    @Test
    void participantRejectsTamperedForeignAndStaleBundlesAsRegenerationRequired() throws Exception {
        Fixture fixture = fixture("participant");
        ProductionAuthorityBundlePersistenceParticipant participant = new ProductionAuthorityBundlePersistenceParticipant(
            fixture.root(), fixture.trustedAuthority());
        assertTrue(participant.health().available());
        participant.healthCheck();

        Files.writeString(ProductionAuthorityBundle.path(fixture.root()),
            fixture.bundle().canonical().replace(fixture.bundle().signature(), "A".repeat(fixture.bundle().signature().length())),
            StandardCharsets.UTF_8);
        assertTrue(participant.health().regenerationRequired());
        assertFalse(participant.health().available());

        Fixture foreignFixture = fixture("foreign");
        ProductionAuthorityBundle foreign = ProductionAuthorityBundle.create(
            new ProductionAuthorityTestSigner(ServerId.deterministic("foreign-server"), fixture.root()),
            ServerId.deterministic("foreign-server"), fixture.bundle().snapshotId(), Instant.now(),
            fixture.bundle().catalogContentChecksum(), fixture.bundle().catalogGeneration(),
            fixture.bundle().catalogContractVersion(), fixture.bundle().runtimeBindingManifestHash(),
            fixture.bundle().runtimeBindingManifestVersion(), fixture.bundle().readinessReportHash(),
            fixture.bundle().readinessReportVersion());
        Files.delete(ProductionAuthorityBundle.path(fixture.root()));
        foreign.write(ProductionAuthorityBundle.path(fixture.root()));
        assertTrue(participant.health().regenerationRequired());

        ProductionAuthorityBundle stale = ProductionAuthorityBundle.create(
            fixture.signer(), fixture.bundle().serverId(),
            fixture.bundle().snapshotId(), Instant.EPOCH, fixture.bundle().catalogContentChecksum(),
            fixture.bundle().catalogGeneration(), fixture.bundle().catalogContractVersion(),
            fixture.bundle().runtimeBindingManifestHash(), fixture.bundle().runtimeBindingManifestVersion(),
            fixture.bundle().readinessReportHash(), fixture.bundle().readinessReportVersion());
        Files.delete(ProductionAuthorityBundle.path(fixture.root()));
        stale.write(ProductionAuthorityBundle.path(fixture.root()));
        assertTrue(participant.health().regenerationRequired());
        assertTrue(foreignFixture.bundle().verifySignature(foreignFixture.context()));
    }

    private Fixture fixture(String name) throws Exception {
        Path root = Files.createDirectory(temporary.resolve(name));
        ServerId serverId = ServerId.deterministic(name + "-server");
        SnapshotId snapshotId = SnapshotId.deterministic(name + "-snapshot");
        Files.writeString(root.resolve("server-id"), serverId.canonicalText() + "\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve(ProductionAuthorityBundle.INSTALL_AUTHORITY_FILE), "authority-" + name,
            StandardCharsets.UTF_8);
        ProductionAuthorityTestSigner signer = new ProductionAuthorityTestSigner(serverId, root);
        ProductionAuthorityBundle bundle = ProductionAuthorityBundle.create(
            signer, serverId, snapshotId, Instant.now(), ContentHash.of("a".repeat(64)), 1,
            new CatalogVersion(1, 0), ContentHash.of("b".repeat(64)), 1, "c".repeat(64), 1);
        bundle.write(ProductionAuthorityBundle.path(root));
        ProductionAuthorityBundle.TrustContext context = ProductionAuthorityBundle.TrustContext.forSource(
            root, snapshotId, name + "-migration", signer);
        return new Fixture(root, bundle, context, context.trustedAuthority(), signer);
    }

    private record Fixture(Path root, ProductionAuthorityBundle bundle,
                           ProductionAuthorityBundle.TrustContext context,
                           ProductionAuthorityBundle.TrustedAuthority trustedAuthority,
                           ProductionAuthorityTestSigner signer) {
    }
}
