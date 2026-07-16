package restudio.resync.network.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncNetworkAgentConfigTest {
    @TempDir
    Path directory;

    @Test
    void loadsLoopbackEnrollmentConfigurationAndPersistsCredential() throws Exception {
        Files.writeString(directory.resolve("resync.properties"), """
            network.enabled=true
            network.id=network-one
            network.node-id=lobby-one
            network.display-name=Lobby
            network.hub-url=ws://127.0.0.1:12442
            network.enrollment-token=one-time-token
            network.credential-file=network/node.credential
            network.capacity=100
            network.maximum-frame-bytes=1048576
            network.maximum-payload-bytes=524288
            network.heartbeat-interval-ticks=100
            network.reconnect-delay-ticks=100
            """);

        ReSyncNetworkAgentConfig config = ReSyncNetworkAgentConfig.load(directory);
        config.saveCredential("issued-credential");
        ReSyncNetworkAgentConfig reloaded = ReSyncNetworkAgentConfig.load(directory);

        assertTrue(config.enabled());
        assertEquals("ws://127.0.0.1:12442", config.hubUrl());
        assertEquals(100, config.capacity());
        assertEquals("issued-credential", reloaded.credential());
        assertTrue(Files.exists(directory.resolve("network/node.credential")));

        reloaded.clearCredential();
        assertFalse(Files.exists(directory.resolve("network/node.credential")));
    }

    @Test
    void defaultsToDisabledWithoutConfiguration() throws Exception {
        ReSyncNetworkAgentConfig config = ReSyncNetworkAgentConfig.load(directory);

        assertFalse(config.enabled());
    }

    @Test
    void rejectsInsecureCrossHostWebSocket() throws Exception {
        Files.writeString(directory.resolve("resync.properties"), """
            network.enabled=true
            network.id=network-one
            network.node-id=lobby-one
            network.hub-url=ws://10.0.0.10:12442
            network.enrollment-token=one-time-token
            """);

        assertThrows(IllegalArgumentException.class, () -> ReSyncNetworkAgentConfig.load(directory));
    }
}
