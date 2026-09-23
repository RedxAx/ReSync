package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.identity.ServerId;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HostedServerIdentityProvisioningTest {
    @TempDir
    Path root;

    @Test
    void configuredIdentityOwnsFreshInstallation() throws Exception {
        ServerId configured = new ServerId(UUID.fromString("684e7752-6055-40be-b498-2714e31ee6f0"));

        ServerIdentityStore created = ServerIdentityStore.open(root.resolve(ServerIdentityStore.FILE_NAME), null,
            configured);
        ServerIdentityStore reopened = ServerIdentityStore.open(root.resolve(ServerIdentityStore.FILE_NAME), null,
            configured);

        assertEquals(configured, created.serverId());
        assertEquals(configured, reopened.serverId());
    }

    @Test
    void configuredIdentityCannotReplaceDurableAuthority() throws Exception {
        ServerId configured = new ServerId(UUID.fromString("684e7752-6055-40be-b498-2714e31ee6f0"));
        ServerIdentityStore.open(root.resolve(ServerIdentityStore.FILE_NAME), null, configured);
        ServerId replacement = new ServerId(UUID.fromString("19aa1e21-ab84-4443-89a4-4336c7d9fb26"));

        assertThrows(IOException.class, () -> ServerIdentityStore.open(
            root.resolve(ServerIdentityStore.FILE_NAME), null, replacement));
    }
}
