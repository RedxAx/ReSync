package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.ReSyncPersistenceCoordinator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlsPersistenceParticipantTest {
    @TempDir
    Path temporary;

    @Test
    void enabledTlsIdentitySealsAndPublishesThroughItsRegisteredOwner() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("enabled"));
        ReSyncTlsIdentity.Prepared prepared = ReSyncTlsIdentity.prepare(root, config());
        TlsPersistenceParticipant participant = prepared.persistence();
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(root, temporary.resolve("coordination"), new MigrationFence());
        coordinator.register(participant);

        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(coordinator, root,
            List.of(ReSyncPersistenceTopology.requiredForRestore(participant.owner(), participant.root(), participant)));

        assertTrue(registration.sealed(), registration.unavailableReasons().toString());
        assertTrue(coordinator.sealed());
        Path activeRoot = coordinator.activeDataRoot();
        assertEquals(activeRoot.resolve("tls/resync-server.runtime.json"), prepared.metadataFile());
        assertEquals(activeRoot.resolve("tls/resync-server.p12"), participant.root());
        ReSyncTlsIdentity.publish(prepared);
        assertTrue(Files.readString(prepared.metadataFile()).contains(prepared.metadata().spkiPin()));
        assertEquals(TlsPersistenceParticipant.OWNER, coordinator.participants().ownerFor(activeRoot, prepared.metadataFile()));
        coordinator.participants().validateForRoot(activeRoot);
        participant.healthCheck();
        coordinator.close();
        assertFalse(Files.exists(prepared.metadataFile()));
        assertTrue(Files.isRegularFile(participant.root()));
        assertThrows(IOException.class, () -> ReSyncTlsIdentity.publish(prepared));
    }

    @Test
    void exactOwnershipIncludesRotationAndOnlyItsGeneratedTemporaryNames() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("ownership"));
        ReSyncConfig.TlsConfig config = config();
        config.setSpkiFingerprint("00".repeat(32));
        ReSyncTlsIdentity.Prepared prepared = ReSyncTlsIdentity.prepare(root, config);
        TlsPersistenceParticipant participant = prepared.persistence();
        var index = participant.ownershipIndex(new PersistenceOwnershipContext(root, participant.root()));

        for (String name : List.of("resync-server.p12", "resync-server.password", "resync-server.rotation.properties",
            "resync-server.runtime.json", "resync-server.p12123456.tmp")) {
            assertTrue(index.owns("tls/" + name), name);
            assertTrue(participant.owns(root.resolve("tls/" + name)), name);
        }
        assertTrue(Files.exists(root.resolve("tls/resync-server.rotation.properties")));
        for (String name : List.of("unrelated.txt", "resync-server.password.extra", "resync-server.p12/child",
            "resync-server.p12.bad.tmp", "resync-server.p12.tmp")) {
            assertFalse(index.owns("tls/" + name), name);
        }
    }

    @Test
    void quiescenceAndRebindKeepPublicationOnTheAdmittedIdentityAndRoot() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        ReSyncTlsIdentity.Prepared prepared = ReSyncTlsIdentity.prepare(source, config());
        TlsPersistenceParticipant participant = prepared.persistence();
        ReSyncTlsIdentity.publish(prepared);
        Path originalMetadata = prepared.metadataFile();
        Path replacement = copyRoot(source, "replacement");

        assertThrows(IOException.class, () -> participant.rebind(replacement));
        participant.quiesce();
        assertThrows(IOException.class, () -> ReSyncTlsIdentity.publish(prepared));
        assertThrows(IOException.class, () -> ReSyncTlsIdentity.withdraw(prepared));
        participant.rebind(replacement);
        assertEquals(replacement.resolve("tls/resync-server.runtime.json"), prepared.metadataFile());
        participant.resume();
        ReSyncTlsIdentity.withdraw(prepared);
        assertFalse(Files.exists(prepared.metadataFile()));
        assertTrue(Files.exists(originalMetadata));
        ReSyncTlsIdentity.publish(prepared);
        assertTrue(Files.exists(prepared.metadataFile()));
    }

    @Test
    void incompatibleRestoreAndSymlinkCandidatesLeaveThePreviousRootQuiesced() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        ReSyncTlsIdentity.Prepared prepared = ReSyncTlsIdentity.prepare(source, config());
        TlsPersistenceParticipant participant = prepared.persistence();
        Path different = Files.createDirectory(temporary.resolve("different"));
        ReSyncTlsIdentity.prepare(different, config());
        Path linked = copyRoot(source, "linked");
        Files.delete(linked.resolve("tls/resync-server.password"));
        Files.createSymbolicLink(linked.resolve("tls/resync-server.password"), source.resolve("tls/resync-server.password"));
        Path original = participant.root();

        participant.quiesce();
        assertThrows(IOException.class, () -> participant.rebind(different));
        assertThrows(IOException.class, () -> participant.rebind(linked));
        assertEquals(original, participant.root());
        assertThrows(IOException.class, () -> ReSyncTlsIdentity.publish(prepared));
        participant.resume();
        ReSyncTlsIdentity.publish(prepared);
    }

    @Test
    void externalIdentityDriftAndInvalidRuntimeMetadataRemainFailClosed() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("drift"));
        ReSyncTlsIdentity.Prepared prepared = ReSyncTlsIdentity.prepare(root, config());
        TlsPersistenceParticipant participant = prepared.persistence();
        participant.quiesce();
        Files.writeString(prepared.metadataFile(), "invalid");

        assertThrows(IOException.class, participant::resume);
        assertThrows(IOException.class, () -> ReSyncTlsIdentity.publish(prepared));
        Files.delete(prepared.metadataFile());
        participant.resume();
        Files.writeString(root.resolve("tls/resync-server.password"), "changed");
        assertThrows(IOException.class, participant::healthCheck);
        assertThrows(IOException.class, () -> ReSyncTlsIdentity.publish(prepared));
    }

    @Test
    void disabledTlsStillOwnsPriorIdentityAndWithdrawsStalePublication() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("disabled"));
        ReSyncTlsIdentity.Prepared prepared = ReSyncTlsIdentity.prepare(root, config());
        ReSyncTlsIdentity.publish(prepared);
        ReSyncConfig.TlsConfig disabled = config();
        disabled.setEnabled(false);
        TlsPersistenceParticipant participant = ReSyncTlsIdentity.persistence(root, disabled);

        assertFalse(Files.exists(prepared.metadataFile()));
        participant.healthCheck();
        assertTrue(participant.owns(root.resolve("tls/resync-server.p12")));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(root, temporary.resolve("coordination"), new MigrationFence());
        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(coordinator, root,
            List.of(ReSyncPersistenceTopology.requiredForRestore(participant.owner(), participant.root(), participant)));
        assertTrue(registration.sealed(), registration.unavailableReasons().toString());
        coordinator.close();
    }

    @Test
    void disabledFreshRootAndCustomMetadataLocationDoNotClaimUnrelatedFiles() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("fresh"));
        ReSyncConfig.TlsConfig config = config();
        config.setEnabled(false);
        TlsPersistenceParticipant participant = ReSyncTlsIdentity.persistence(root, config);
        assertTrue(participant.rootMayBeAbsent());
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(root, temporary.resolve("coordination"), new MigrationFence());
        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(coordinator, root,
            List.of(ReSyncPersistenceTopology.requiredForRestore(participant.owner(), participant.root(), participant)));
        assertTrue(registration.sealed(), registration.unavailableReasons().toString());
        assertFalse(Files.exists(root.resolve("tls")));
        coordinator.close();

        config.setEnabled(true);
        config.setRuntimeMetadataFile("runtime.json");
        ReSyncTlsIdentity.Prepared prepared = ReSyncTlsIdentity.prepare(root, config);
        var index = prepared.persistence().ownershipIndex(new PersistenceOwnershipContext(root, prepared.persistence().root()));
        assertTrue(index.owns("runtime.json"));
        assertTrue(index.owns("resync-server.password"));
        assertFalse(index.owns("config.properties"));
    }

    private Path copyRoot(Path source, String name) throws IOException {
        Path target = Files.createDirectory(temporary.resolve(name));
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                if (path.equals(source)) continue;
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
        return target;
    }

    private ReSyncConfig.TlsConfig config() {
        ReSyncConfig.TlsConfig config = new ReSyncConfig.TlsConfig();
        config.setEnabled(true);
        config.setSpkiFingerprint("");
        config.setRuntimeMetadataFile("tls/resync-server.runtime.json");
        config.setSubjectAlternativeNames(List.of("DNS:node.example", "IP:127.0.0.1"));
        return config;
    }
}
