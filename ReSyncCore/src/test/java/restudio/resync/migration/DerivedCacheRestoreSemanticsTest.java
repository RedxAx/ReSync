package restudio.resync.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.SnapshotId;
import restudio.resync.restore.AtomicRestoreActivation;
import restudio.resync.restore.RestoreCompatibilityPolicy;
import restudio.resync.restore.RestoreRequest;
import restudio.resync.restore.RestoreResult;
import restudio.resync.restore.RestoreService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DerivedCacheRestoreSemanticsTest {
    private static final String CATALOG_CHECKSUM = "a".repeat(64);

    @TempDir
    Path temporary;

    @Test
    void snapshotOmitsAuthorityOutputAndRestoreCompletesWithADegradedCache() throws Exception {
        AuthorityFixture previousFixture = authorityFixture("previous");
        Path previousSource = previousFixture.source();
        Path previousDurableRoot = Files.createDirectory(previousSource.resolve("durable"));
        Files.writeString(previousDurableRoot.resolve("state.txt"), "previous");
        ProductionAuthorityBundlePersistenceParticipant previousAuthority = previousFixture.participant();
        TestParticipant previousDurable = new TestParticipant(previousSource, previousDurableRoot, false);
        PersistenceParticipantRegistry participants = registry(previousSource, previousAuthority, previousDurable);
        SnapshotMetadata previousMetadata = metadata("previous");
        Snapshot previousSnapshot = snapshot(previousSource, previousMetadata, participants,
            temporary.resolve("previous-snapshot"));

        assertFalse(previousSnapshot.manifest().entries().stream()
            .anyMatch(entry -> entry.relativePath().equals("authority/authority-bundle.json")));
        assertFalse(Files.exists(previousSnapshot.root().resolve("authority/authority-bundle.json")));

        AtomicRestoreActivation activation = new AtomicRestoreActivation(temporary.resolve("restore-control"));
        activation.activate(activation.stage(previousSnapshot, temporary.resolve("initial-active")));
        participants.quiesceAll();
        participants.rebindAll(activation.activeRoot().orElseThrow());
        participants.resumeAll();

        AuthorityFixture targetFixture = authorityFixture("target");
        Path targetSource = targetFixture.source();
        Path targetDurableRoot = Files.createDirectory(targetSource.resolve("durable"));
        Files.writeString(targetDurableRoot.resolve("state.txt"), "target");
        ProductionAuthorityBundlePersistenceParticipant targetAuthority = targetFixture.participant();
        PersistenceParticipantRegistry targetParticipants = registry(targetSource, targetAuthority,
            new TestParticipant(targetSource, targetDurableRoot, false));
        Snapshot targetSnapshot = snapshot(targetSource, metadata("target"), targetParticipants,
            temporary.resolve("target-snapshot"));

        RestoreService restores = new RestoreService(new MigrationFence(), participants, activation);
        RestoreRequest request = new RestoreRequest(targetSnapshot,
            compatibility(), temporary.resolve("current-snapshot"), metadata("current"),
            temporary.resolve("restore-staging"), temporary.resolve("restore-journal"));

        RestoreResult result = restores.restore(request);

        assertEquals(MigrationJournalState.COMMITTED, result.finalState());
        assertEquals(result.staged().root(), activation.activeRoot().orElseThrow());
        assertEquals("target", Files.readString(activation.activeRoot().orElseThrow().resolve("durable/state.txt")));
        assertTrue(previousAuthority.health().regenerationRequired());
        assertThrows(MigrationException.class, participants::readinessCheckAll);
    }

    @Test
    void presentTamperedAuthorityOutputStillRejectsRebind() throws Exception {
        AuthorityFixture fixture = authorityFixture("tampered");
        Path source = fixture.source();
        ProductionAuthorityBundlePersistenceParticipant authority = fixture.participant();
        authority.quiesce();

        Path candidate = Files.createDirectory(temporary.resolve("candidate"));
        Path candidateAuthority = Files.createDirectory(candidate.resolve("authority"));
        Files.writeString(candidateAuthority.resolve("authority-bundle.json"), "tampered");

        assertThrows(MigrationException.class, () -> authority.rebind(candidate));
        assertEquals(source.resolve("authority"), authority.root());
        assertTrue(authority.health().available());
    }

    @Test
    void unrelatedDurableRebindFailureRollsBackAlongsideMissingDerivedOutput() throws Exception {
        AuthorityFixture fixture = authorityFixture("rollback");
        Path source = fixture.source();
        Path durableRoot = Files.createDirectory(source.resolve("durable"));
        ProductionAuthorityBundlePersistenceParticipant authority = fixture.participant();
        TestParticipant durable = new TestParticipant(source, durableRoot, true);
        PersistenceParticipantRegistry participants = registry(source, authority, durable);
        participants.quiesceAll();
        Path candidate = Files.createDirectory(temporary.resolve("candidate"));

        assertThrows(MigrationException.class, () -> participants.rebindAll(candidate));

        assertEquals(source.resolve("authority"), authority.root());
        assertEquals(durableRoot, durable.root());
        assertEquals(PersistenceRebindStatus.State.ROLLED_BACK, participants.rebindStatus().state());
    }

    @Test
    void registryHealthRemainsStrictForUnavailableDerivedOutput() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("scope"));
        Path derivedRoot = Files.createDirectory(scope.resolve("derived"));
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry(scope);
        participants.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return "derived";
            }

            @Override
            public Path root() {
                return derivedRoot;
            }

            @Override
            public PersistenceParticipantClassification classification() {
                return PersistenceParticipantClassification.DERIVED_CACHE;
            }

            @Override
            public void healthCheck() throws IOException {
                throw new IOException("rebuild required");
            }
        });

        assertThrows(MigrationException.class, participants::healthCheckAll);
        Files.writeString(derivedRoot.resolve("output"), "tampered");
        assertThrows(MigrationException.class, participants::healthCheckAll);
    }

    private AuthorityFixture authorityFixture(String name) throws Exception {
        Path source = Files.createDirectory(temporary.resolve(name + "-source"));
        ServerId serverId = ServerId.deterministic(name + "-server");
        Files.writeString(source.resolve("server-id"), serverId.canonicalText() + "\n", StandardCharsets.UTF_8);
        Files.writeString(source.resolve(ProductionAuthorityBundle.INSTALL_AUTHORITY_FILE), "authority-" + name,
            StandardCharsets.UTF_8);
        ProductionAuthorityTestSigner signer = new ProductionAuthorityTestSigner(serverId, source);
        Files.createDirectory(source.resolve(ProductionAuthorityBundlePersistenceParticipant.DIRECTORY));
        ProductionAuthorityBundle bundle = ProductionAuthorityBundle.create(
            signer, serverId, SnapshotId.deterministic(name + "-snapshot"), Instant.now(),
            ContentHash.of(CATALOG_CHECKSUM), 1, new CatalogVersion(1, 0), ContentHash.of("b".repeat(64)), 1,
            "c".repeat(64), 1);
        bundle.write(ProductionAuthorityBundle.path(source));
        ProductionAuthorityBundlePersistenceParticipant participant =
            new ProductionAuthorityBundlePersistenceParticipant(source,
                ProductionAuthorityBundle.TrustedAuthority.from(signer));
        return new AuthorityFixture(source, participant);
    }

    private PersistenceParticipantRegistry registry(Path scope,
                                                    ProductionAuthorityBundlePersistenceParticipant authority,
                                                    TestParticipant durable) {
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry(scope);
        participants.register(authority);
        participants.register(durable);
        return participants;
    }

    private Snapshot snapshot(Path source, SnapshotMetadata metadata,
                              PersistenceParticipantRegistry participants, Path staging) throws IOException {
        return new SnapshotService(new MigrationFence()).create(source, staging, metadata, participants);
    }

    private SnapshotMetadata metadata(String id) {
        return new SnapshotMetadata(1, id, Instant.parse("2026-08-18T00:00:00Z"), "resync-test",
            CATALOG_CHECKSUM, Map.of());
    }

    private RestoreCompatibilityPolicy compatibility() {
        return new RestoreCompatibilityPolicy(1, 1, Set.of("resync-test"), new CatalogVersion(1, 0),
            new CatalogBinding(1, CATALOG_CHECKSUM, CATALOG_CHECKSUM), Map.of());
    }

    private record AuthorityFixture(Path source, ProductionAuthorityBundlePersistenceParticipant participant) {
    }

    private static final class TestParticipant implements RebindablePersistenceParticipant {
        private final Path scope;
        private final Path relativeRoot;
        private final boolean failTarget;
        private Path activeScope;

        private TestParticipant(Path scope, Path root, boolean failTarget) {
            this.scope = scope.toAbsolutePath().normalize();
            this.relativeRoot = this.scope.relativize(root.toAbsolutePath().normalize());
            this.failTarget = failTarget;
            this.activeScope = this.scope;
        }

        @Override
        public String owner() {
            return "z-durable";
        }

        @Override
        public Path root() {
            return activeScope.resolve(relativeRoot).normalize();
        }

        @Override
        public boolean owns(Path file) {
            Path candidate = file.toAbsolutePath().normalize();
            return candidate.startsWith(root())
                || candidate.equals(activeScope.resolve("server-id").normalize())
                || candidate.equals(activeScope.resolve(ProductionAuthorityBundle.INSTALL_AUTHORITY_FILE).normalize());
        }

        @Override
        public Path rebindScope() {
            return scope;
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
            Path normalized = activeRoot.toAbsolutePath().normalize();
            if (failTarget && !normalized.equals(scope)) {
                throw new IOException("durable rebind failed");
            }
            activeScope = normalized;
        }

        @Override
        public void healthCheck() {
        }
    }
}
