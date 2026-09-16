package restudio.resync.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.catalog.CatalogActivationAuthority;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeRegistrySnapshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionAuthorityBundleExporterTest {
    @TempDir
    Path temporary;

    @Test
    void emitsCanonicalAtomicBundleBoundToAllLiveAuthorities() throws Exception {
        Fixture fixture = fixture();
        Path target = ProductionAuthorityBundle.path(fixture.persistence().dataRoot());

        ProductionAuthorityBundle bundle = ProductionAuthorityBundleExporter.emit(
            fixture.source(), target, Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC));

        assertEquals(bundle.canonical(), Files.readString(target));
        assertEquals(bundle, ProductionAuthorityBundle.fromCanonical(bundle.canonical()));
        assertEquals(bundle, ProductionAuthorityBundle.read(target));
        assertEquals(fixture.catalog().contentChecksum(), bundle.catalogContentChecksum());
        assertEquals(fixture.runtime().bindingManifestHash(), bundle.runtimeBindingManifestHash());
        assertEquals(fixture.readiness().reportHash(), bundle.readinessReportHash());
        assertTrue(bundle.snapshotId().canonicalText().length() > 1);
    }

    @Test
    void rejectsAStartupProofFromAnotherPersistenceCoordinator() throws Exception {
        Fixture fixture = fixture();
        Path dataRoot = Files.createDirectory(temporary.resolve("foreign-proof-data"));
        ReSyncPersistenceCoordinator foreign = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("foreign-proof-coordination"), new MigrationFence());
        RebindablePersistenceParticipant participant = new RebindablePersistenceParticipant() {
            private Path root = dataRoot;

            @Override
            public String owner() {
                return "owner";
            }

            @Override
            public Path root() {
                return root;
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
            public void rebind(Path activeRoot) {
                root = activeRoot;
            }

            @Override
            public void healthCheck() {
            }
        };
        foreign.register(participant);
        foreign.seal();
        PersistenceRootReadiness readiness = new PersistenceRootReadiness(List.of(
            PersistenceRootReadiness.Owner.registered(participant.owner(), participant.root(), true)));
        ReSyncPersistenceCoordinator.ReadinessProof proof = foreign.validateRestoreReadiness(readiness).orElseThrow();
        ProductionAuthorityBundleExporter.Source stale = new ProductionAuthorityBundleExporter.Source(
            fixture.activation(), fixture.authority(), readiness, fixture.persistence(), fixture.serverId(), fixture.signer(), proof);

        assertThrows(MigrationException.class, () -> ProductionAuthorityBundleExporter.create(stale));
        foreign.close();
    }

    @Test
    void passesAuthorityExportPreflightForASealedFixtureWithExternalAffectedWriters() throws Exception {
        Fixture fixture = fixture();
        Path externalRoot = Files.createDirectories(temporary.resolve("paper-world").resolve("playerdata"));
        PersistenceRootReadiness readiness = new PersistenceRootReadiness(
            fixture.readiness().owners(),
            List.of(PersistenceRootReadiness.UncoveredWriter.externalAffected(
                "resync.paper.playerdata", externalRoot, "Paper owns world player data outside the ReSync root",
                "Bukkit/Paper world persistence authority")),
            fixture.readiness().externalInputs());

        assertTrue(fixture.persistence().sealed());
        assertTrue(readiness.complete());
        assertTrue(readiness.writerInventoryComplete());
        assertEquals(List.of("resync.paper.playerdata"), readiness.externalAffectedWriters().stream()
            .map(PersistenceRootReadiness.UncoveredWriter::id).toList());
        ProductionAuthorityBundle bundle = ProductionAuthorityBundleExporter.create(
            sourceWithReadiness(fixture, readiness), Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC));

        assertEquals(readiness.reportHash(), bundle.readinessReportHash());
    }

    @Test
    void retainsAndValidatesAnAuthorityBundleAlreadyBoundByTheActivationMarker() throws Exception {
        Fixture fixture = fixture();
        Instant timestamp = Instant.parse("2026-08-17T00:00:00Z");
        ProductionAuthorityBundle original = ProductionAuthorityBundleExporter.create(
            fixture.source(), Clock.fixed(timestamp, ZoneOffset.UTC));
        MigrationActivationMarker.write(fixture.persistence().dataRoot(), new MigrationActivationMarker.Values(
            "a".repeat(64), "b".repeat(64), "c".repeat(64), MigrationActivationMarker.NO_ARCHIVED_SOURCE_DIGEST,
            fixture.catalog().contentChecksum().canonicalText(), fixture.runtime().bindingManifestHash().canonicalText(),
            fixture.runtime().manifest().version(), fixture.readiness().reportHash(), fixture.readiness().reportVersion(), true, original));
        ProductionAuthorityBundleExporter.Source retained = retainedSource(fixture);

        assertEquals(original, ProductionAuthorityBundleExporter.create(retained, Clock.fixed(timestamp, ZoneOffset.UTC)));
        assertThrows(MigrationException.class, () -> ProductionAuthorityBundleExporter.create(
            retained, Clock.fixed(timestamp.plus(ProductionAuthorityBundle.MAX_FRESHNESS_AGE).plusSeconds(1), ZoneOffset.UTC)));
    }

    @Test
    void rejectsRetainedAuthoritySignedByAValidForeignSigner() throws Exception {
        Fixture fixture = fixture();
        Instant timestamp = Instant.parse("2026-08-17T00:00:00Z");
        ProductionAuthorityBundle original = ProductionAuthorityBundleExporter.create(
            fixture.source(), Clock.fixed(timestamp, ZoneOffset.UTC));
        ProductionAuthorityTestSigner foreignSigner = new ProductionAuthorityTestSigner(
            fixture.serverId(), fixture.persistence().dataRoot());
        ProductionAuthorityBundle foreign = ProductionAuthorityBundle.create(
            foreignSigner, original.serverId(), original.snapshotId(), original.timestamp(), original.catalogContentChecksum(),
            original.catalogGeneration(), original.catalogContractVersion(), original.runtimeBindingManifestHash(),
            original.runtimeBindingManifestVersion(), original.readinessReportHash(), original.readinessReportVersion());
        writeAuthorityMarker(fixture, foreign);

        assertThrows(MigrationException.class, () -> ProductionAuthorityBundleExporter.create(
            retainedSource(fixture), Clock.fixed(timestamp, ZoneOffset.UTC)));
    }

    @Test
    void rejectsRetainedAuthorityWithAForgedSignature() throws Exception {
        Fixture fixture = fixture();
        Instant timestamp = Instant.parse("2026-08-17T00:00:00Z");
        ProductionAuthorityBundle original = ProductionAuthorityBundleExporter.create(
            fixture.source(), Clock.fixed(timestamp, ZoneOffset.UTC));
        byte[] signature = Base64.getDecoder().decode(original.signature());
        signature[0] ^= 1;
        ProductionAuthorityBundle forged = ProductionAuthorityBundle.create(
            original.serverId(), original.snapshotId(), original.timestamp(), original.catalogContentChecksum(),
            original.catalogGeneration(), original.catalogContractVersion(), original.runtimeBindingManifestHash(),
            original.runtimeBindingManifestVersion(), original.readinessReportHash(), original.readinessReportVersion(),
            original.installAuthorityHash(), original.signingPublicKey(), Base64.getEncoder().encodeToString(signature));
        writeAuthorityMarker(fixture, forged);

        assertThrows(MigrationException.class, () -> ProductionAuthorityBundleExporter.create(
            retainedSource(fixture), Clock.fixed(timestamp, ZoneOffset.UTC)));
    }

    @Test
    void rejectsTamperedBundleBeforeItCanBeConsumed() throws Exception {
        Fixture fixture = fixture();
        ProductionAuthorityBundle bundle = ProductionAuthorityBundleExporter.create(
            fixture.source(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        String tampered = bundle.canonical().replace(bundle.runtimeBindingManifestHash().canonicalText(), "f".repeat(64));

        assertThrows(IllegalArgumentException.class, () -> ProductionAuthorityBundle.fromCanonical(tampered));
        Path target = temporary.resolve("tampered.json");
        Files.writeString(target, tampered, StandardCharsets.UTF_8);
        assertThrows(MigrationException.class, () -> ProductionAuthorityBundle.read(target));
    }

    @Test
    void exportPathIsFixedAndExistingContentIsIdempotentOnly() throws Exception {
        Fixture fixture = fixture();
        Path target = ProductionAuthorityBundle.path(fixture.persistence().dataRoot());
        ProductionAuthorityBundle bundle = ProductionAuthorityBundleExporter.emit(
            fixture.source(), target, Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC));

        ProductionAuthorityBundleExporter.emit(
            fixture.source(), target, Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC));
        assertEquals(bundle.canonical(), Files.readString(target));
        assertThrows(MigrationException.class, () -> ProductionAuthorityBundleExporter.emit(
            fixture.source(), target, Clock.fixed(Instant.parse("2026-08-17T00:00:01Z"), ZoneOffset.UTC)));
        assertThrows(MigrationException.class, () -> ProductionAuthorityBundleExporter.emit(
            fixture.source(), fixture.persistence().dataRoot().resolve("authority-bundle.json"),
            Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC)));
        Files.writeString(target, "tampered");
        assertThrows(MigrationException.class, () -> ProductionAuthorityBundleExporter.emit(
            fixture.source(), target, Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC)));
    }

    @Test
    void regeneratesMissingOptionalAuthorityOutput() throws Exception {
        Fixture fixture = fixture(true);
        Path target = ProductionAuthorityBundle.path(fixture.persistence().dataRoot());

        assertFalse(Files.exists(target));
        ProductionAuthorityBundle bundle = ProductionAuthorityBundleExporter.create(fixture.source(), Clock.systemUTC());

        assertTrue(fixture.authorityParticipant().health().regenerationRequired());
        bundle.write(target);
        assertTrue(fixture.authorityParticipant().health().available());
        assertEquals(bundle, ProductionAuthorityBundle.read(target));
    }

    @Test
    void regeneratesTamperedOptionalAuthorityOutput() throws Exception {
        Fixture fixture = fixture(true);
        Path target = ProductionAuthorityBundle.path(fixture.persistence().dataRoot());
        ProductionAuthorityBundle original = ProductionAuthorityBundleExporter.emit(
            fixture.source(), target, Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC));
        byte[] signature = Base64.getDecoder().decode(original.signature());
        signature[0] ^= 1;
        ProductionAuthorityBundle tampered = ProductionAuthorityBundle.create(
            original.serverId(), original.snapshotId(), original.timestamp(), original.catalogContentChecksum(),
            original.catalogGeneration(), original.catalogContractVersion(), original.runtimeBindingManifestHash(),
            original.runtimeBindingManifestVersion(), original.readinessReportHash(), original.readinessReportVersion(),
            original.installAuthorityHash(), original.signingPublicKey(), Base64.getEncoder().encodeToString(signature));
        byte[] tamperedBytes = tampered.canonicalBytes();
        Files.write(target, tamperedBytes);

        ProductionAuthorityBundle repaired = ProductionAuthorityBundleExporter.emit(
            fixture.source(), target, Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC));

        assertEquals(original, repaired);
        assertEquals(original.canonical(), Files.readString(target));
        assertArrayEquals(tamperedBytes, Files.readAllBytes(quarantinePath(fixture, tamperedBytes)));
    }

    @Test
    void regeneratesStaleOptionalAuthorityOutput() throws Exception {
        Fixture fixture = fixture(true);
        Path target = ProductionAuthorityBundle.path(fixture.persistence().dataRoot());
        ProductionAuthorityBundle stale = ProductionAuthorityBundleExporter.create(
            fixture.source(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        byte[] staleBytes = stale.canonicalBytes();
        Files.write(target, staleBytes);

        ProductionAuthorityBundle repaired = ProductionAuthorityBundleExporter.emit(
            fixture.source(), target, Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC));

        assertFalse(stale.freshAt(Instant.now()));
        assertTrue(repaired.freshAt(Instant.parse("2026-08-17T00:00:00Z")));
        assertEquals(repaired.canonical(), Files.readString(target));
        assertArrayEquals(staleBytes, Files.readAllBytes(quarantinePath(fixture, staleBytes)));
    }

    @Test
    void regeneratesMalformedOptionalAuthorityOutput() throws Exception {
        Fixture fixture = fixture(true);
        Path target = ProductionAuthorityBundle.path(fixture.persistence().dataRoot());
        byte[] malformed = "not-an-authority-bundle".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(target.getParent());
        Files.write(target, malformed);

        ProductionAuthorityBundle repaired = ProductionAuthorityBundleExporter.emit(
            fixture.source(), target, Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC));

        assertEquals(repaired.canonical(), Files.readString(target));
        assertArrayEquals(malformed, Files.readAllBytes(quarantinePath(fixture, malformed)));
    }

    @Test
    void repeatedExportAfterAuthorityRegenerationIsIdempotentWithTheOriginalReadinessSource() throws Exception {
        Fixture fixture = fixture(true);
        Path target = ProductionAuthorityBundle.path(fixture.persistence().dataRoot());
        Clock clock = Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC);

        ProductionAuthorityBundle first = ProductionAuthorityBundleExporter.emit(fixture.source(), target, clock);
        ProductionAuthorityBundle second = ProductionAuthorityBundleExporter.emit(fixture.source(), target, clock);

        assertEquals(first, second);
        assertEquals(first.canonical(), Files.readString(target));
    }

    @Test
    void rejectsAnUnrelatedUnavailableOwnerAlongsideAuthorityRegeneration() throws Exception {
        Fixture fixture = fixture(true);
        Path unrelatedRoot = temporary.resolve("unrelated-derived");
        PersistenceRootReadiness readiness = new PersistenceRootReadiness(List.of(
            PersistenceRootReadiness.Owner.registered("owner", fixture.persistence().dataRoot().resolve("participant"), true),
            PersistenceRootReadiness.Owner.unavailable(ProductionAuthorityBundlePersistenceParticipant.OWNER,
                fixture.authorityParticipant().root(), false, PersistenceParticipantClassification.DERIVED_CACHE,
                fixture.authorityParticipant().health().reason()),
            PersistenceRootReadiness.Owner.unavailable("unrelated-derived", unrelatedRoot, false,
                PersistenceParticipantClassification.DERIVED_CACHE, "Unrelated derived output is unavailable")));
        ProductionAuthorityBundleExporter.Source source = sourceWithReadiness(fixture, readiness);

        assertThrows(MigrationException.class, () -> ProductionAuthorityBundleExporter.create(
            source, Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC)));
    }

    @Test
    void rejectsIncompleteReadinessAndUncommittedAuthority() throws Exception {
        Fixture fixture = fixture();
        Path missingRoot = temporary.resolve("missing-owner");
        PersistenceRootReadiness incomplete = new PersistenceRootReadiness(List.of(
            PersistenceRootReadiness.Owner.unavailable("owner", missingRoot, true, "owner unavailable")));
        ProductionAuthorityBundleExporter.Source incompleteSource = new ProductionAuthorityBundleExporter.Source(
            fixture.activation(), fixture.authority(), incomplete, fixture.persistence(), fixture.serverId(), fixture.signer());

        assertThrows(MigrationException.class, () -> ProductionAuthorityBundleExporter.create(incompleteSource));

        ProductionAuthorityBundleExporter.Source freshAuthority = new ProductionAuthorityBundleExporter.Source(
            fixture.activation(), CatalogActivationAuthority.freshInstall(), fixture.readiness(), fixture.persistence(), fixture.serverId(), fixture.signer());
        assertThrows(MigrationException.class, () -> ProductionAuthorityBundleExporter.create(freshAuthority));
    }

    @Test
    void rejectsMixedCatalogRuntimeGeneration() throws Exception {
        Fixture fixture = fixture();
        CatalogSnapshot nextCatalog = new CatalogCompiler(
            new CatalogVersion(2, 0), CatalogBindingProof.snapshot(fixture.runtime()))
            .compile(List.of(), fixture.catalog().generation() + 1)
            .snapshot()
            .orElseThrow();
        CatalogRuntimeActivation mixed = new CatalogRuntimeActivation(nextCatalog, fixture.runtime());
        ProductionAuthorityBundleExporter.Source mixedSource = new ProductionAuthorityBundleExporter.Source(
            mixed, fixture.authority(), fixture.readiness(), fixture.persistence(), fixture.serverId(), fixture.signer());

        assertThrows(MigrationException.class, () -> ProductionAuthorityBundleExporter.create(mixedSource));
    }

    @Test
    void rejectsReadinessBoundToAStaleParticipantRoot() throws Exception {
        Fixture fixture = fixture();
        Path staleRoot = Files.createDirectory(temporary.resolve("stale-owner-root"));
        PersistenceRootReadiness stale = new PersistenceRootReadiness(List.of(
            PersistenceRootReadiness.Owner.registered("owner", staleRoot, true)));
        MigrationActivationMarker.write(fixture.persistence().dataRoot(), new MigrationActivationMarker.Values(
            "a".repeat(64), "b".repeat(64), "c".repeat(64), fixture.catalog().contentChecksum().canonicalText(),
            fixture.runtime().bindingManifestHash().canonicalText(), fixture.runtime().manifest().version(), stale.reportHash(),
            stale.reportVersion(), true));
        ReplacementActivationRecord.write(fixture.persistence().dataRoot(), new ReplacementActivationRecord.Values(
            fixture.catalog().generation(), fixture.catalog().contentChecksum().canonicalText(), fixture.runtime().manifest().version(),
            fixture.runtime().bindingManifestHash().canonicalText(), stale.reportVersion(), stale.reportHash()));
        CatalogActivationAuthority authority = CatalogActivationAuthority.fromCommittedMigration(fixture.persistence().dataRoot());
        ProductionAuthorityBundleExporter.Source staleSource = new ProductionAuthorityBundleExporter.Source(
            fixture.activation(), authority, stale, fixture.persistence(), fixture.serverId(), fixture.signer());

        assertThrows(MigrationException.class, () -> ProductionAuthorityBundleExporter.create(staleSource));
    }

    @Test
    void rejectsAStableIdentityProjectionThatDoesNotMatchTheSource() throws Exception {
        Fixture fixture = fixture();
        Files.writeString(fixture.persistence().dataRoot().resolve("server-id"),
            ServerId.deterministic("different-server").canonicalText() + "\n", StandardCharsets.UTF_8);
        ProductionAuthorityBundleExporter.Source mismatched = new ProductionAuthorityBundleExporter.Source(
            fixture.activation(), fixture.authority(), fixture.readiness(), fixture.persistence(), fixture.serverId(), fixture.signer());

        assertThrows(MigrationException.class, () -> ProductionAuthorityBundleExporter.create(mismatched));
    }

    private Fixture fixture() throws Exception {
        return fixture(false);
    }

    private Fixture fixture(boolean withAuthorityParticipant) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("data-" + UUID.randomUUID()));
        Path participantRoot = Files.createDirectory(dataRoot.resolve("participant"));
        ReSyncPersistenceCoordinator persistence = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("coord-" + UUID.randomUUID()));
        ServerId serverId = ServerId.deterministic("production-authority-bundle-test");
        ProductionAuthorityTestSigner signer = new ProductionAuthorityTestSigner(serverId, dataRoot);
        ProductionAuthorityBundlePersistenceParticipant authorityParticipant = withAuthorityParticipant
            ? new ProductionAuthorityBundlePersistenceParticipant(dataRoot,
                new ProductionAuthorityBundle.TrustedAuthority(serverId, MigrationCanonical.sha256("authority"),
                    signer.signingPublicKey()))
            : null;
        persistence.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return "owner";
            }

            @Override
            public Path root() {
                return participantRoot;
            }
        });
        if (authorityParticipant != null) {
            persistence.register(authorityParticipant);
        }
        persistence.seal();
        Files.writeString(dataRoot.resolve("server-id"), serverId.canonicalText() + "\n", StandardCharsets.UTF_8);
        Files.writeString(dataRoot.resolve(ProductionAuthorityBundle.INSTALL_AUTHORITY_FILE), "authority");
        List<PersistenceRootReadiness.Owner> owners = new ArrayList<>();
        owners.add(PersistenceRootReadiness.Owner.registered("owner", participantRoot, true));
        if (authorityParticipant != null) {
            owners.add(PersistenceRootReadiness.Owner.unavailable(
                ProductionAuthorityBundlePersistenceParticipant.OWNER, authorityParticipant.root(), false,
                PersistenceParticipantClassification.DERIVED_CACHE, authorityParticipant.health().reason()));
        }
        PersistenceRootReadiness readiness = new PersistenceRootReadiness(owners);
        RuntimeBindingRegistry runtimeRegistry = new RuntimeBindingRegistry();
        var runtime = runtimeRegistry.snapshot();
        CatalogSnapshot catalog = new CatalogCompiler(new CatalogVersion(1, 0), CatalogBindingProof.snapshot(runtime))
            .compile(List.of(), 1)
            .snapshot()
            .orElseThrow();
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(catalog, runtime);
        ReplacementActivationRecord.write(dataRoot, new ReplacementActivationRecord.Values(
            catalog.generation(), catalog.contentChecksum().canonicalText(), runtime.manifest().version(),
            runtime.bindingManifestHash().canonicalText(), readiness.reportVersion(), readiness.reportHash()));
        MigrationActivationMarker.write(dataRoot, new MigrationActivationMarker.Values(
            "a".repeat(64), "b".repeat(64), "c".repeat(64), catalog.contentChecksum().canonicalText(),
            runtime.bindingManifestHash().canonicalText(), runtime.manifest().version(), readiness.reportHash(),
            readiness.reportVersion(), true));
        CatalogActivationAuthority authority = CatalogActivationAuthority.fromCommittedMigration(dataRoot);
        return new Fixture(activation, authority, readiness, persistence, catalog, runtime, serverId, signer, authorityParticipant);
    }

    private void writeAuthorityMarker(Fixture fixture, ProductionAuthorityBundle authority) throws Exception {
        MigrationActivationMarker.write(fixture.persistence().dataRoot(), new MigrationActivationMarker.Values(
            "a".repeat(64), "b".repeat(64), "c".repeat(64), MigrationActivationMarker.NO_ARCHIVED_SOURCE_DIGEST,
            fixture.catalog().contentChecksum().canonicalText(), fixture.runtime().bindingManifestHash().canonicalText(),
            fixture.runtime().manifest().version(), fixture.readiness().reportHash(), fixture.readiness().reportVersion(), true, authority));
    }

    private ProductionAuthorityBundleExporter.Source retainedSource(Fixture fixture) {
        return new ProductionAuthorityBundleExporter.Source(
            fixture.activation(), CatalogActivationAuthority.fromCommittedMigration(fixture.persistence().dataRoot()),
            fixture.readiness(), fixture.persistence(), fixture.serverId(), fixture.signer());
    }

    private Path quarantinePath(Fixture fixture, byte[] bytes) {
        return fixture.persistence().dataRoot().resolve(".quarantine/authority-bundle")
            .resolve(MigrationCanonical.sha256(bytes) + ".json");
    }

    private ProductionAuthorityBundleExporter.Source sourceWithReadiness(
        Fixture fixture,
        PersistenceRootReadiness readiness
    ) throws Exception {
        ReplacementActivationRecord.write(fixture.persistence().dataRoot(), new ReplacementActivationRecord.Values(
            fixture.catalog().generation(), fixture.catalog().contentChecksum().canonicalText(), fixture.runtime().manifest().version(),
            fixture.runtime().bindingManifestHash().canonicalText(), readiness.reportVersion(), readiness.reportHash()));
        MigrationActivationMarker.write(fixture.persistence().dataRoot(), new MigrationActivationMarker.Values(
            "a".repeat(64), "b".repeat(64), "c".repeat(64), fixture.catalog().contentChecksum().canonicalText(),
            fixture.runtime().bindingManifestHash().canonicalText(), fixture.runtime().manifest().version(), readiness.reportHash(),
            readiness.reportVersion(), true));
        return new ProductionAuthorityBundleExporter.Source(
            fixture.activation(), CatalogActivationAuthority.fromCommittedMigration(fixture.persistence().dataRoot()), readiness,
            fixture.persistence(), fixture.serverId(), fixture.signer());
    }

    private record Fixture(
        CatalogRuntimeActivation activation,
        CatalogActivationAuthority authority,
        PersistenceRootReadiness readiness,
        ReSyncPersistenceCoordinator persistence,
        CatalogSnapshot catalog,
        RuntimeRegistrySnapshot runtime,
        ServerId serverId,
        ProductionAuthoritySigner signer,
        ProductionAuthorityBundlePersistenceParticipant authorityParticipant
    ) {
        private ProductionAuthorityBundleExporter.Source source() {
            return new ProductionAuthorityBundleExporter.Source(activation, authority, readiness, persistence, serverId, signer);
        }
    }
}
