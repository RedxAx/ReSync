package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.PersistenceRootReadiness;
import restudio.resync.migration.PersistenceShutdownStatus;
import restudio.resync.migration.ProductionAuthoritySigner;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerIdentityPersistenceParticipantTest {
    @Test
    void freshBootstrapCreatesOneExactIdentityPairAndDurableAuthority(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("fresh"));

        ServerIdentityStore store = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));

        assertTrue(store.freshInstall());
        assertEquals(ServerIdentityStore.OWNER, store.owner());
        assertEquals(dataRoot.resolve(ServerIdentityStore.FILE_NAME), store.root());
        assertTrue(store.owns(dataRoot.resolve(ServerIdentityStore.FILE_NAME)));
        assertTrue(store.owns(dataRoot.resolve(ServerIdentityStore.INSTALL_SIGNAL_FILE)));
        assertTrue(store.owns(dataRoot.resolve(ServerIdentityStore.AUTHORITY_FILE)));
        assertTrue(store.owns(dataRoot.resolve(ServerIdentityStore.AUTHORITY_WAL_FILE)));
        assertTrue(store.owns(dataRoot.resolve(ServerIdentityStore.AUTHORITY_SHM_FILE)));
        assertTrue(store.owns(dataRoot.resolve(ServerIdentityStore.AUTHORITY_JOURNAL_FILE)));
        assertFalse(store.owns(dataRoot.resolve("config.properties")));
        assertEquals(store.serverId().canonicalText() + "\n", Files.readString(store.path()));
        assertTrue(Files.readString(store.installSignalPath()).contains("server-id=" + store.serverId().canonicalText() + "\n"));
        assertEquals(store.serverId(), ServerIdentityStore.durableAuthorityId(dataRoot).orElseThrow());
        try (Connection connection = DriverManager.getConnection(
            "jdbc:sqlite:" + dataRoot.resolve(ServerIdentityStore.AUTHORITY_FILE).toAbsolutePath());
             Statement statement = connection.createStatement();
             var result = statement.executeQuery(
                 "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'resync_install_identity_authority'")) {
            assertTrue(result.next());
            assertEquals(exactAuthorityTableSql(), result.getString(1));
        }
        try (var entries = Files.list(dataRoot)) {
            assertTrue(entries.map(path -> path.getFileName().toString())
                .allMatch(ServerIdentityStore.ownedFileNames()::contains));
        }
        store.healthCheck();
        store.flush();
    }

    @Test
    void freshBootstrapDoesNotDependOnDirectoryForce(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("windows-no-directory-force"));

        ServerIdentityStore store = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));

        assertTrue(Files.isRegularFile(dataRoot.resolve(ServerIdentityStore.AUTHORITY_FILE)));
        assertEquals(store.serverId(), ServerIdentityStore.durableAuthorityId(dataRoot).orElseThrow());
        assertEquals(store.serverId().canonicalText() + "\n", Files.readString(store.path()));
        assertTrue(Files.readString(store.installSignalPath()).contains(
            "server-id=" + store.serverId().canonicalText() + "\n"));
    }

    @Test
    void existingInstallPreservesIdentityAndSignalBytes(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("existing"));
        ServerIdentityStore first = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        String identity = Files.readString(first.path());
        String signal = Files.readString(first.installSignalPath());

        ServerIdentityStore reopened = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));

        assertEquals(first.serverId(), reopened.serverId());
        assertFalse(reopened.freshInstall());
        assertEquals(identity, Files.readString(reopened.path()));
        assertEquals(signal, Files.readString(reopened.installSignalPath()));
        assertEquals(first.serverId(), ServerIdentityStore.durableAuthorityId(dataRoot).orElseThrow());
    }

    @Test
    void legacyPairMigratesWithoutChangingIdentityOrSignal(@TempDir Path temporary) throws Exception {
        Path sourceRoot = Files.createDirectory(temporary.resolve("source"));
        ServerIdentityStore source = ServerIdentityStore.open(sourceRoot.resolve(ServerIdentityStore.FILE_NAME));
        String expectedIdentity = Files.readString(source.path());
        String expectedSignal = Files.readString(source.installSignalPath());
        Path legacyRoot = Files.createDirectory(temporary.resolve("legacy"));
        Files.copy(source.path(), legacyRoot.resolve(ServerIdentityStore.FILE_NAME));
        Files.copy(source.installSignalPath(), legacyRoot.resolve(ServerIdentityStore.INSTALL_SIGNAL_FILE));

        ServerIdentityStore migrated = ServerIdentityStore.open(legacyRoot.resolve(ServerIdentityStore.FILE_NAME));

        assertEquals(source.serverId(), migrated.serverId());
        assertFalse(migrated.freshInstall());
        assertEquals(expectedIdentity, Files.readString(migrated.path()));
        assertEquals(expectedSignal, Files.readString(migrated.installSignalPath()));
        assertEquals(source.serverId(), ServerIdentityStore.durableAuthorityId(legacyRoot).orElseThrow());
    }

    @Test
    void committedAuthorityRepairsMalformedProjectionOnRestart(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("repair"));
        ServerIdentityStore store = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        String expectedSignal = Files.readString(store.installSignalPath());
        Path otherRoot = Files.createDirectory(temporary.resolve("other"));
        ServerIdentityStore other = ServerIdentityStore.open(otherRoot.resolve(ServerIdentityStore.FILE_NAME));
        String corruptIdentity = Files.readString(other.path());
        Files.copy(other.path(), store.path(), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(store.installSignalPath(), "partial");

        ServerIdentityStore repaired = ServerIdentityStore.open(store.path());

        assertEquals(store.serverId(), repaired.serverId());
        assertFalse(repaired.freshInstall());
        assertEquals(store.serverId().canonicalText() + "\n", Files.readString(repaired.path()));
        assertEquals(expectedSignal, Files.readString(repaired.installSignalPath()));
        Path quarantine = dataRoot.resolve(ServerIdentityStore.QUARANTINE_DIRECTORY);
        try (var entries = Files.list(quarantine)) {
            List<String> evidence = entries.map(path -> {
                try {
                    return Files.readString(path);
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            }).toList();
            assertTrue(evidence.contains(corruptIdentity));
            assertTrue(evidence.contains("partial"));
        }
    }

    @Test
    void boundedProjectionFailureIsRepairedFromCommittedAuthority(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("bounded-repair"));
        ServerIdentityStore store = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        Files.writeString(store.path(), "partial");

        ServerIdentityStore.ProjectionWriter bounded = (target, bytes) -> Files.write(target,
            bytes.length == 0 ? bytes : new byte[] {bytes[0]});
        assertThrows(IOException.class, () -> ServerIdentityStore.open(store.path(),
            ServerIdentityStore.AuthorityFaultInjector.none(), bounded));

        ServerIdentityStore repaired = ServerIdentityStore.open(store.path());

        assertEquals(store.serverId(), repaired.serverId());
        assertFalse(repaired.freshInstall());
        assertEquals(store.serverId().canonicalText() + "\n", Files.readString(repaired.path()));
    }

    @Test
    void projectionRepairRetainsEvidenceThatArrivesBeforePublication(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("target-fence"));
        ServerIdentityStore store = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        Files.writeString(store.path(), "partial");
        ServerIdentityStore.ProjectionWriter racing = (target, bytes) -> {
            Files.writeString(target, "external evidence");
            ServerIdentityStore.ProjectionWriter.atomic().write(target, bytes);
        };

        assertThrows(IOException.class, () -> ServerIdentityStore.open(store.path(),
            ServerIdentityStore.AuthorityFaultInjector.none(), racing));
        assertEquals("external evidence", Files.readString(store.path()));
    }

    @Test
    void rejectsLookalikeAuthorityTable(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("lookalike"));
        executeAuthoritySql(dataRoot, "CREATE TABLE resync_install_identity_authority(server_id TEXT)", 0);

        assertThrows(IOException.class, () -> ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME)));
        assertFalse(Files.exists(dataRoot.resolve(ServerIdentityStore.FILE_NAME)));
    }

    @Test
    void rejectsDriftedAuthorityColumns(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("drifted"));
        executeAuthoritySql(dataRoot, "CREATE TABLE resync_install_identity_authority(singleton INTEGER PRIMARY KEY, server_id TEXT NOT NULL, install_signal TEXT NOT NULL, install_signal_hash TEXT NOT NULL, projection_state TEXT NOT NULL, drift TEXT)", 1);

        assertThrows(IOException.class, () -> ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME)));
        assertFalse(Files.exists(dataRoot.resolve(ServerIdentityStore.FILE_NAME)));
    }

    @Test
    void rejectsDriftedAuthorityConstraintsAndCollations(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("drifted-constraint"));
        executeAuthoritySql(dataRoot, "CREATE TABLE resync_install_identity_authority(singleton INTEGER PRIMARY KEY CHECK(singleton > 0), server_id TEXT COLLATE NOCASE NOT NULL, install_signal TEXT NOT NULL, install_signal_hash TEXT NOT NULL, projection_state TEXT NOT NULL CHECK(projection_state IN ('PENDING', 'COMMITTED')))", 1);

        assertThrows(IOException.class, () -> ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME)));
        assertFalse(Files.exists(dataRoot.resolve(ServerIdentityStore.FILE_NAME)));
    }

    @Test
    void rejectsUnknownAuthorityVersion(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("unknown-version"));
        executeAuthoritySql(dataRoot, exactAuthorityTableSql(), 2);

        assertThrows(IOException.class, () -> ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME)));
        assertFalse(Files.exists(dataRoot.resolve(ServerIdentityStore.FILE_NAME)));
    }

    @Test
    void rejectsUnexpectedAuthorityObjects(@TempDir Path temporary) throws Exception {
        for (String extra : List.of(
            "CREATE VIEW unexpected_view AS SELECT 1",
            "CREATE INDEX unexpected_index ON resync_install_identity_authority(server_id)",
            "CREATE TRIGGER unexpected_trigger AFTER INSERT ON resync_install_identity_authority BEGIN SELECT 1; END")) {
            Path dataRoot = Files.createDirectory(temporary.resolve(Integer.toHexString(extra.hashCode())));
            executeAuthoritySql(dataRoot, exactAuthorityTableSql(), 1);
            executeAuthoritySql(dataRoot, extra, null);

            assertThrows(IOException.class, () -> ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME)));
            assertFalse(Files.exists(dataRoot.resolve(ServerIdentityStore.FILE_NAME)));
        }
    }

    @Test
    void snapshotAuthorityAndProjectionsRebindExactly(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("source"));
        ServerIdentityStore store = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("coordination"));
        coordinator.register(store);
        coordinator.seal();
        Snapshot snapshot = coordinator.createSnapshot(temporary.resolve("snapshot-staging"),
            SnapshotMetadata.preflight());
        Path candidate = Files.createDirectory(temporary.resolve("candidate"));
        copyRegularFiles(snapshot.root(), candidate);
        String expectedIdentity = Files.readString(candidate.resolve(ServerIdentityStore.FILE_NAME));
        String expectedSignal = Files.readString(candidate.resolve(ServerIdentityStore.INSTALL_SIGNAL_FILE));
        assertTrue(Files.isRegularFile(candidate.resolve(ServerIdentityStore.AUTHORITY_FILE)));

        store.quiesce();
        store.rebind(candidate);

        assertEquals(candidate.resolve(ServerIdentityStore.FILE_NAME), store.root());
        assertEquals(expectedIdentity, Files.readString(store.path()));
        assertEquals(expectedSignal, Files.readString(store.installSignalPath()));
        assertEquals(store.serverId(), ServerIdentityStore.durableAuthorityId(candidate).orElseThrow());
        assertFalse(store.freshInstall());
        store.resume();
        coordinator.close();
    }

    @Test
    void rejectsMalformedMissingAndMixedCandidatePairsBeforeSwitch(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("active"));
        ServerIdentityStore store = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path activeIdentity = store.path();
        ServerId activeId = store.serverId();
        Path malformed = pairRoot(temporary.resolve("malformed"), dataRoot);
        Files.writeString(malformed.resolve(ServerIdentityStore.INSTALL_SIGNAL_FILE), "malformed\n");
        Path missingIdentity = pairRoot(temporary.resolve("missing-identity"), dataRoot);
        Files.delete(missingIdentity.resolve(ServerIdentityStore.FILE_NAME));
        Path missingSignal = pairRoot(temporary.resolve("missing-signal"), dataRoot);
        Files.delete(missingSignal.resolve(ServerIdentityStore.INSTALL_SIGNAL_FILE));
        Path otherRoot = Files.createDirectory(temporary.resolve("other"));
        ServerIdentityStore other = ServerIdentityStore.open(otherRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path mixed = pairRoot(temporary.resolve("mixed"), dataRoot);
        Files.copy(other.path(), mixed.resolve(ServerIdentityStore.FILE_NAME), StandardCopyOption.REPLACE_EXISTING);

        store.quiesce();
        assertThrows(IOException.class, () -> store.rebind(malformed));
        assertThrows(IOException.class, () -> store.rebind(missingIdentity));
        assertThrows(IOException.class, () -> store.rebind(missingSignal));
        assertThrows(IOException.class, () -> store.rebind(mixed));
        assertEquals(activeIdentity, store.path());
        assertEquals(activeId, store.serverId());
        store.resume();
    }

    @Test
    void rejectsCompleteForeignCandidateBeforeSwitch(@TempDir Path temporary) throws Exception {
        Path activeRoot = Files.createDirectory(temporary.resolve("foreign-active"));
        ServerIdentityStore store = ServerIdentityStore.open(activeRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path foreignRoot = Files.createDirectory(temporary.resolve("foreign-candidate-source"));
        ServerIdentityStore foreign = ServerIdentityStore.open(foreignRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path candidate = Files.createDirectory(temporary.resolve("foreign-candidate"));
        copyRegularFiles(foreignRoot, candidate);
        Path activePath = store.path();
        ServerId activeId = store.serverId();

        store.quiesce();
        assertThrows(IOException.class, () -> store.rebind(candidate));
        assertEquals(activePath, store.path());
        assertEquals(activeId, store.serverId());
        assertEquals(foreign.serverId(), ServerIdentityStore.durableAuthorityId(candidate).orElseThrow());
        store.resume();
    }

    @Test
    void rejectsActiveAuthorityMutationAfterQuiesce(@TempDir Path temporary) throws Exception {
        Path activeRoot = Files.createDirectory(temporary.resolve("mutated-active"));
        ServerIdentityStore store = ServerIdentityStore.open(activeRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path candidate = pairRoot(temporary.resolve("same-identity-candidate"), activeRoot);
        Path foreignRoot = Files.createDirectory(temporary.resolve("mutated-authority-source"));
        ServerIdentityStore foreign = ServerIdentityStore.open(foreignRoot.resolve(ServerIdentityStore.FILE_NAME));

        store.quiesce();
        Files.deleteIfExists(activeRoot.resolve(ServerIdentityStore.AUTHORITY_WAL_FILE));
        Files.deleteIfExists(activeRoot.resolve(ServerIdentityStore.AUTHORITY_SHM_FILE));
        Files.deleteIfExists(activeRoot.resolve(ServerIdentityStore.AUTHORITY_JOURNAL_FILE));
        Files.copy(foreignRoot.resolve(ServerIdentityStore.AUTHORITY_FILE),
            activeRoot.resolve(ServerIdentityStore.AUTHORITY_FILE), StandardCopyOption.REPLACE_EXISTING);

        assertThrows(IOException.class, () -> store.rebind(candidate));
        assertEquals(activeRoot.resolve(ServerIdentityStore.FILE_NAME), store.path());
        assertEquals(foreign.serverId(), ServerIdentityStore.durableAuthorityId(activeRoot).orElseThrow());
    }

    @Test
    void invalidatesCachedSignerAfterSameIdentityRebind(@TempDir Path temporary) throws Exception {
        Path activeRoot = Files.createDirectory(temporary.resolve("signer-active"));
        ServerIdentityStore store = ServerIdentityStore.open(activeRoot.resolve(ServerIdentityStore.FILE_NAME));
        ProductionAuthoritySigner before = store.productionAuthoritySigner();
        Path candidate = pairRoot(temporary.resolve("signer-candidate"), activeRoot);

        store.quiesce();
        store.rebind(candidate);

        ProductionAuthoritySigner after = store.productionAuthoritySigner();
        assertNotSame(before, after);
        assertEquals(store.serverId(), after.serverId());
        store.resume();
    }

    @Test
    void partialLegacyPairFailsClosedWithoutCreatingAuthority(@TempDir Path temporary) throws Exception {
        Path sourceRoot = Files.createDirectory(temporary.resolve("source"));
        ServerIdentityStore source = ServerIdentityStore.open(sourceRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path partialRoot = Files.createDirectory(temporary.resolve("partial"));
        Files.copy(source.path(), partialRoot.resolve(ServerIdentityStore.FILE_NAME));

        assertThrows(IOException.class, () -> ServerIdentityStore.open(partialRoot.resolve(ServerIdentityStore.FILE_NAME)));
        assertFalse(Files.exists(partialRoot.resolve(ServerIdentityStore.AUTHORITY_FILE)));
        assertEquals(source.serverId().canonicalText() + "\n", Files.readString(partialRoot.resolve(ServerIdentityStore.FILE_NAME)));
    }

    @Test
    void malformedLegacyPairFailsClosedWithoutCreatingAuthority(@TempDir Path temporary) throws Exception {
        Path sourceRoot = Files.createDirectory(temporary.resolve("source"));
        ServerIdentityStore source = ServerIdentityStore.open(sourceRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path malformedRoot = Files.createDirectory(temporary.resolve("malformed"));
        Files.copy(source.path(), malformedRoot.resolve(ServerIdentityStore.FILE_NAME));
        Files.writeString(malformedRoot.resolve(ServerIdentityStore.INSTALL_SIGNAL_FILE), "malformed\n");

        assertThrows(IOException.class, () -> ServerIdentityStore.open(malformedRoot.resolve(ServerIdentityStore.FILE_NAME)));
        assertFalse(Files.exists(malformedRoot.resolve(ServerIdentityStore.AUTHORITY_FILE)));
    }

    @Test
    void rebindRequiresQuiesceAndResumeRestoresAdmission(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("lifecycle"));
        ServerIdentityStore store = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path candidate = pairRoot(temporary.resolve("candidate"), dataRoot);

        assertFalse(store.isQuiesced());
        assertThrows(IOException.class, () -> store.rebind(candidate));
        store.quiesce();
        assertTrue(store.isQuiesced());
        store.resume();
        assertFalse(store.isQuiesced());
        store.healthCheck();
    }

    @Test
    void shutdownUsesTheParticipantLifecycleOnce(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("shutdown"));
        ServerIdentityStore store = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("coordination"));
        coordinator.register(store);
        coordinator.seal();

        PersistenceShutdownStatus status = coordinator.close();

        assertEquals(PersistenceShutdownStatus.State.CLOSED, status.state());
        assertTrue(store.isQuiesced());
        assertTrue(Files.isRegularFile(dataRoot.resolve(ServerIdentityStore.FILE_NAME)));
        assertTrue(Files.isRegularFile(dataRoot.resolve(ServerIdentityStore.INSTALL_SIGNAL_FILE)));
        assertTrue(Files.isRegularFile(dataRoot.resolve(ServerIdentityStore.AUTHORITY_FILE)));
    }

    @Test
    void exactOwnershipAllowsAdjacentParticipantWithoutRootOverlap(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("coverage"));
        ServerIdentityStore identity = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        ConfigurationPersistenceParticipant configuration = new ConfigurationPersistenceParticipant(dataRoot);
        configuration.flush();
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(dataRoot);
        registry.register(identity);
        registry.register(configuration);

        registry.validateForRoot(dataRoot);

        assertEquals(ServerIdentityStore.OWNER, registry.ownerFor(dataRoot, dataRoot.resolve(ServerIdentityStore.FILE_NAME)));
        assertEquals(ServerIdentityStore.OWNER, registry.ownerFor(dataRoot, dataRoot.resolve(ServerIdentityStore.INSTALL_SIGNAL_FILE)));
        assertEquals(ServerIdentityStore.OWNER, registry.ownerFor(dataRoot, dataRoot.resolve(ServerIdentityStore.AUTHORITY_FILE)));
        assertEquals(ServerIdentityStore.OWNER, registry.ownerFor(dataRoot, dataRoot.resolve(ServerIdentityStore.AUTHORITY_WAL_FILE)));
        assertEquals(ServerIdentityStore.OWNER, registry.ownerFor(dataRoot, dataRoot.resolve(ServerIdentityStore.AUTHORITY_SHM_FILE)));
        assertEquals(ServerIdentityStore.OWNER, registry.ownerFor(dataRoot, dataRoot.resolve(ServerIdentityStore.AUTHORITY_JOURNAL_FILE)));
        assertEquals(ConfigurationPersistenceParticipant.OWNER, registry.ownerFor(dataRoot, dataRoot.resolve("config.properties")));
        assertThrows(MigrationException.class, () -> registry.ownerFor(dataRoot, dataRoot.resolve("unowned.txt")));

        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("topology-coordination"));
        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(
                ReSyncPersistenceTopology.requiredForRestore(ServerIdentityStore.OWNER, identity.root(), identity),
                ReSyncPersistenceTopology.requiredForRestore(ConfigurationPersistenceParticipant.OWNER,
                    configuration.root(), configuration)),
            List.of(PersistenceRootReadiness.UncoveredWriter.of(
                ServerIdentityStore.OWNER, dataRoot.resolve(ServerIdentityStore.FILE_NAME), "identity pair")));

        assertTrue(registration.sealed());
        assertEquals(List.of(ConfigurationPersistenceParticipant.OWNER, ServerIdentityStore.OWNER), registration.registeredOwners());
        coordinator.close();
    }

    @Test
    void mixedPairCannotBeOpenedOrRegenerated(@TempDir Path temporary) throws Exception {
        Path firstRoot = Files.createDirectory(temporary.resolve("first"));
        Path secondRoot = Files.createDirectory(temporary.resolve("second"));
        ServerIdentityStore first = ServerIdentityStore.open(firstRoot.resolve(ServerIdentityStore.FILE_NAME));
        ServerIdentityStore second = ServerIdentityStore.open(secondRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path mixedRoot = Files.createDirectory(temporary.resolve("mixed"));
        Files.copy(second.path(), mixedRoot.resolve(ServerIdentityStore.FILE_NAME));
        Files.copy(first.installSignalPath(), mixedRoot.resolve(ServerIdentityStore.INSTALL_SIGNAL_FILE));

        assertThrows(IOException.class, () -> ServerIdentityStore.open(mixedRoot.resolve(ServerIdentityStore.FILE_NAME)));
        assertNotEquals(first.serverId(), second.serverId());
        assertEquals(second.serverId().canonicalText() + "\n", Files.readString(mixedRoot.resolve(ServerIdentityStore.FILE_NAME)));
        assertTrue(ServerIdentityStore.durableAuthorityId(mixedRoot).isEmpty());
    }

    @Test
    void authorityRecoveryRetainsOneGenerationAcrossEveryDurableCut(@TempDir Path temporary) throws Exception {
        for (ServerIdentityStore.AuthorityCut cut : ServerIdentityStore.AuthorityCut.values()) {
            if (cut == ServerIdentityStore.AuthorityCut.AFTER_AUTHORITY_REOPEN) {
                continue;
            }
            Path dataRoot = Files.createDirectory(temporary.resolve(cut.name().toLowerCase()));
            Path identity = dataRoot.resolve(ServerIdentityStore.FILE_NAME);
            Files.writeString(dataRoot.resolve(".resync-install.bootstrap.tmp"), "uncommitted\n");
            Files.writeString(dataRoot.resolve("server-id-unforced.tmp"), "uncommitted\n");
            assertThrows(IOException.class, () -> ServerIdentityStore.open(identity, point -> {
                if (point == cut) {
                    throw new IOException("cut=" + point);
                }
            }));

            var durableId = ServerIdentityStore.durableAuthorityId(dataRoot);
            if (cut == ServerIdentityStore.AuthorityCut.BEFORE_AUTHORITY_WRITE
                || cut == ServerIdentityStore.AuthorityCut.AFTER_AUTHORITY_WRITE
                || cut == ServerIdentityStore.AuthorityCut.BEFORE_AUTHORITY_COMMIT) {
                assertTrue(durableId.isEmpty());
                ServerIdentityStore rolledBack = ServerIdentityStore.open(identity);
                assertNotNull(rolledBack.serverId());
            } else {
                ServerIdentityStore recovered = ServerIdentityStore.open(identity);
                assertEquals(durableId.orElseThrow(), recovered.serverId());
                assertTrue(Files.isRegularFile(recovered.path()));
                assertTrue(Files.isRegularFile(recovered.installSignalPath()));
                if (cut == ServerIdentityStore.AuthorityCut.AFTER_PROJECTION_COMMIT) {
                    assertFalse(recovered.freshInstall());
                } else {
                    assertTrue(recovered.freshInstall());
                }
            }
            assertFalse(Files.exists(dataRoot.resolve(".resync-install.bootstrap")));
            assertFalse(Files.exists(dataRoot.resolve(".resync-install.bootstrap.tmp")));
            assertFalse(Files.exists(dataRoot.resolve(".resync-install.server-id.tmp")));
            assertFalse(Files.exists(dataRoot.resolve(".resync-install.signal.tmp")));
            Path quarantine = dataRoot.resolve(ServerIdentityStore.QUARANTINE_DIRECTORY);
            assertTrue(Files.isDirectory(quarantine));
            try (var entries = Files.list(quarantine)) {
                assertTrue(entries.count() >= 2);
            }
        }
    }

    @Test
    void authorityReopenFaultRepairsFromCommittedBackendWithoutRegeneration(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("reopen"));
        ServerIdentityStore first = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        Files.delete(first.path());
        Files.delete(first.installSignalPath());

        assertThrows(IOException.class, () -> ServerIdentityStore.open(first.path(), point -> {
            if (point == ServerIdentityStore.AuthorityCut.AFTER_AUTHORITY_REOPEN) {
                throw new IOException("reopen cut");
            }
        }));

        ServerIdentityStore recovered = ServerIdentityStore.open(first.path());

        assertEquals(first.serverId(), recovered.serverId());
        assertFalse(recovered.freshInstall());
        assertEquals(first.serverId(), ServerIdentityStore.durableAuthorityId(dataRoot).orElseThrow());
        assertEquals(first.serverId().canonicalText() + "\n", Files.readString(recovered.path()));
    }

    @Test
    void failedBootstrapClosesBackendResourcesForStartupCleanup(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("startup-cleanup"));
        Path identity = dataRoot.resolve(ServerIdentityStore.FILE_NAME);

        assertThrows(IOException.class, () -> ServerIdentityStore.open(identity, point -> {
            if (point == ServerIdentityStore.AuthorityCut.AFTER_SIGNAL_PROJECTION) {
                throw new IOException("startup cut");
            }
        }));

        try (var paths = Files.walk(dataRoot)) {
            paths.sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
        }
        assertFalse(Files.exists(dataRoot));
    }

    @Test
    void concurrentObserversSeeOnlyImmutableIdentityBindings(@TempDir Path temporary) throws Exception {
        Path firstRoot = Files.createDirectory(temporary.resolve("binding-first"));
        ServerIdentityStore store = ServerIdentityStore.open(firstRoot.resolve(ServerIdentityStore.FILE_NAME));
        Path secondRoot = pairRoot(temporary.resolve("binding-second"), firstRoot);
        ServerIdentityStore second = ServerIdentityStore.open(secondRoot.resolve(ServerIdentityStore.FILE_NAME));
        Map<Path, ServerId> expected = Map.of(
            firstRoot, store.serverId(),
            secondRoot, second.serverId());
        store.quiesce();

        int readerCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(readerCount + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            var writer = executor.submit(() -> {
                try {
                    start.await();
                    for (int index = 0; index < 160; index++) {
                        store.rebind((index & 1) == 0 ? firstRoot : secondRoot);
                    }
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    running.set(false);
                }
            });
            var readers = new ArrayList<Future<?>>(readerCount);
            for (int index = 0; index < readerCount; index++) {
                readers.add(executor.submit(() -> {
                    try {
                        start.await();
                        while (running.get()) {
                            ServerIdentityStore.Binding binding = store.binding();
                            Path root = binding.path().getParent();
                            assertEquals(expected.get(root), binding.serverId());
                            assertEquals(root.resolve(ServerIdentityStore.INSTALL_SIGNAL_FILE), binding.installSignalPath());
                            assertEquals(binding.serverId().canonicalText() + "\n", Files.readString(binding.path()));
                            assertTrue(Files.readString(binding.installSignalPath()).contains(
                                "server-id=" + binding.serverId().canonicalText() + "\n"));
                        }
                    } catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                    }
                }));
            }
            start.countDown();
            writer.get(60, TimeUnit.SECONDS);
            for (var reader : readers) {
                reader.get(10, TimeUnit.SECONDS);
            }
            assertNull(failure.get());
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            store.resume();
        }
    }

    private static Path pairRoot(Path root, Path source) throws IOException {
        Files.createDirectory(root);
        Files.copy(source.resolve(ServerIdentityStore.FILE_NAME), root.resolve(ServerIdentityStore.FILE_NAME));
        Files.copy(source.resolve(ServerIdentityStore.INSTALL_SIGNAL_FILE), root.resolve(ServerIdentityStore.INSTALL_SIGNAL_FILE));
        return root;
    }

    private static void copyRegularFiles(Path source, Path target) throws IOException {
        try (var entries = Files.list(source)) {
            for (Path entry : entries.toList()) {
                if (Files.isRegularFile(entry)) {
                    Files.copy(entry, target.resolve(entry.getFileName().toString()));
                }
            }
        }
    }

    private static void executeAuthoritySql(Path dataRoot, String sql, Integer formatVersion) throws Exception {
        try (Connection connection = DriverManager.getConnection(
            "jdbc:sqlite:" + dataRoot.resolve(ServerIdentityStore.AUTHORITY_FILE).toAbsolutePath());
             Statement statement = connection.createStatement()) {
            if (formatVersion != null) {
                statement.execute("PRAGMA application_id = " + Integer.toString(0x52534944));
                statement.execute("PRAGMA user_version = " + formatVersion);
            }
            statement.execute(sql);
        }
    }

    private static String exactAuthorityTableSql() {
        return "CREATE TABLE resync_install_identity_authority (singleton INTEGER PRIMARY KEY CHECK(singleton = 1), server_id TEXT NOT NULL, install_signal TEXT NOT NULL, install_signal_hash TEXT NOT NULL, projection_state TEXT NOT NULL CHECK(projection_state IN ('PENDING', 'COMMITTED')))";
    }
}
