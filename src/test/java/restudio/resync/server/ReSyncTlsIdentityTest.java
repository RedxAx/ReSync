package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncTlsIdentityTest {
    @TempDir
    Path tempDirectory;

    @Test
    void createsReusesAndRotatesBoundIdentity() throws Exception {
        ReSyncConfig.TlsConfig config = config();
        ReSyncTlsIdentity.Prepared first = ReSyncTlsIdentity.prepare(tempDirectory, config);

        assertNotNull(first.sslContext());
        assertTrue(first.metadata().certificateFingerprint().matches("[0-9a-f]{64}"));
        assertTrue(first.metadata().spkiFingerprint().matches("[0-9a-f]{64}"));
        assertTrue(first.metadata().spkiPin().startsWith("sha256/"));
        assertTrue(first.metadata().keyStoreRevision().matches("[0-9a-f]{64}"));
        assertEquals(tempDirectory.resolve("tls/resync-server.runtime.json"), first.metadataFile());
        assertTrue(Files.notExists(first.metadataFile()));
        assertSan(first.certificate(), 2, "node.example");
        assertSan(first.certificate(), 7, "127.0.0.1");
        ReSyncTlsIdentity.publish(first);
        assertTrue(Files.exists(first.metadataFile()));

        ReSyncTlsIdentity.Prepared blankRestart = ReSyncTlsIdentity.prepare(tempDirectory, config);

        assertTrue(Files.notExists(blankRestart.metadataFile()));
        assertEquals(first.certificate().getSerialNumber(), blankRestart.certificate().getSerialNumber());
        assertEquals(first.metadata().keyStoreRevision(), blankRestart.metadata().keyStoreRevision());

        config.setSpkiFingerprint(first.metadata().spkiFingerprint());
        ReSyncTlsIdentity.Prepared reused = ReSyncTlsIdentity.prepare(tempDirectory, config);

        assertEquals(first.certificate().getSerialNumber(), reused.certificate().getSerialNumber());
        assertEquals(first.metadata().keyStoreRevision(), reused.metadata().keyStoreRevision());

        config.setSpkiFingerprint("00".repeat(32));
        ReSyncTlsIdentity.Prepared mismatched = ReSyncTlsIdentity.prepare(tempDirectory, config);

        assertNotEquals(reused.certificate().getSerialNumber(), mismatched.certificate().getSerialNumber());
        assertNotEquals(reused.metadata().keyStoreRevision(), mismatched.metadata().keyStoreRevision());

        ReSyncTlsIdentity.Prepared pendingRestart = ReSyncTlsIdentity.prepare(tempDirectory, config);

        assertEquals(mismatched.certificate().getSerialNumber(), pendingRestart.certificate().getSerialNumber());
        assertEquals(mismatched.metadata().keyStoreRevision(), pendingRestart.metadata().keyStoreRevision());

        config.setSpkiFingerprint("11".repeat(32));
        ReSyncTlsIdentity.Prepared changedBinding = ReSyncTlsIdentity.prepare(tempDirectory, config);

        assertNotEquals(pendingRestart.certificate().getSerialNumber(), changedBinding.certificate().getSerialNumber());
        assertNotEquals(pendingRestart.metadata().keyStoreRevision(), changedBinding.metadata().keyStoreRevision());

        config.setSpkiFingerprint("");
        ReSyncTlsIdentity.Prepared unbound = ReSyncTlsIdentity.prepare(tempDirectory, config);

        assertEquals(changedBinding.certificate().getSerialNumber(), unbound.certificate().getSerialNumber());
        assertEquals(changedBinding.metadata().keyStoreRevision(), unbound.metadata().keyStoreRevision());

        ReSyncTlsIdentity.publish(unbound);
        assertTrue(Files.readString(unbound.metadataFile()).contains("\"format\":\"resync.tls.runtime\",\"version\":1"));
        ReSyncTlsIdentity.withdraw(unbound);
        assertTrue(Files.notExists(unbound.metadataFile()));
    }

    @Test
    void rejectsMetadataOutsidePluginDirectory() {
        ReSyncConfig.TlsConfig config = config();
        config.setRuntimeMetadataFile("../runtime.json");

        assertThrows(IllegalArgumentException.class, () -> ReSyncTlsIdentity.prepare(tempDirectory, config));
    }

    @Test
    void rejectsMalformedTypedSubjectAlternativeNames() {
        ReSyncConfig.TlsConfig config = config();
        config.setSubjectAlternativeNames(List.of("DNS:node_example"));
        assertThrows(IllegalArgumentException.class, () -> ReSyncTlsIdentity.prepare(tempDirectory, config));

        config.setSubjectAlternativeNames(List.of("IP:node.example"));
        assertThrows(IllegalArgumentException.class, () -> ReSyncTlsIdentity.prepare(tempDirectory, config));

        config.setSubjectAlternativeNames(List.of("DNS:127.0.0.1"));
        assertThrows(IllegalArgumentException.class, () -> ReSyncTlsIdentity.prepare(tempDirectory, config));
    }

    private ReSyncConfig.TlsConfig config() {
        ReSyncConfig.TlsConfig config = new ReSyncConfig.TlsConfig();
        config.setEnabled(true);
        config.setSpkiFingerprint("");
        config.setRuntimeMetadataFile("tls/resync-server.runtime.json");
        config.setSubjectAlternativeNames(List.of("DNS:node.example", "IP:127.0.0.1"));
        return config;
    }

    private void assertSan(X509Certificate certificate, int type, String expected) throws Exception {
        Collection<List<?>> names = certificate.getSubjectAlternativeNames();
        assertNotNull(names);
        assertTrue(names.stream().anyMatch(name -> name.size() > 1 && Integer.valueOf(type).equals(name.getFirst()) && expected.equals(name.get(1))));
    }
}
