package restudio.resync.velocity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.network.NetworkCredentials;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityNetworkConfigLoaderTest {
    @TempDir
    Path directory;

    @Test
    void createsDisabledLocalConfiguration() throws Exception {
        VelocityNetworkConfig config = VelocityNetworkConfigLoader.load(directory);

        assertFalse(config.enabled());
        assertEquals("127.0.0.1", config.bindHost());
        assertTrue(Files.exists(directory.resolve("network.properties")));
    }

    @Test
    void loadsEnrollmentNodesAndBoundedHubStorage() throws Exception {
        byte[] tokenHash = NetworkCredentials.hash("one-time-token");
        Files.writeString(directory.resolve("network.properties"), """
            network.enabled=true
            network.id=network-one
            network.node-id=proxy-one
            network.display-name=Proxy
            hub.bind-host=127.0.0.1
            hub.port=12442
            hub.database=network/network.db
            hub.maximum-frame-bytes=1048576
            hub.maximum-payload-bytes=524288
            hub.heartbeat-timeout-millis=15000
            hub.tls.enabled=false
            nodes=lobby-one
            node.lobby-one.display-name=Lobby
            node.lobby-one.role=LOBBY
            node.lobby-one.capabilities=presence
            node.lobby-one.enrollment-token-hash=%s
            node.lobby-one.enrollment-expires-at=0
            routes=lobby
            route.lobby.node-id=lobby-one
            route.lobby.address=127.0.0.1
            route.lobby.port=25566
            maintenance-route=lobby
            """.formatted(Base64.getUrlEncoder().withoutPadding().encodeToString(tokenHash)));

        VelocityNetworkConfig config = VelocityNetworkConfigLoader.load(directory);

        assertTrue(config.enabled());
        assertEquals(directory.resolve("network/network.db").toAbsolutePath().normalize(), config.databasePath());
        assertEquals(1, config.enrollmentNodes().size());
        VelocityNetworkConfig.EnrollmentNode node = config.enrollmentNodes().get("lobby-one");
        assertEquals("Lobby", node.displayName());
        assertEquals("LOBBY", node.role());
        assertTrue(node.capabilities().contains("presence"));
        assertArrayEquals(tokenHash, node.tokenHash());
        assertEquals("lobby-one", config.routes().get("lobby").nodeId());
        assertEquals(25566, config.routes().get("lobby").port());
        assertEquals("lobby", config.maintenanceRoute());
    }

    @Test
    void rejectsNetworkFilesOutsideThePluginDirectory() throws Exception {
        Files.writeString(directory.resolve("network.properties"), "hub.database=../outside.db\n");

        assertThrows(IllegalArgumentException.class, () -> VelocityNetworkConfigLoader.load(directory));
    }
}
