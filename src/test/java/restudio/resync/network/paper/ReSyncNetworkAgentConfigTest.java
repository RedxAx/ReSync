package restudio.resync.network.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

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
            network.chat.enabled=true
            network.chat.channel-mode=ALLOW_LIST
            network.chat.channels=global, Staff
            network.chat.retention-millis=45000
            network.resources.enabled=true
            network.resources.type-mode=DENY_LIST
            network.resources.types=world, CUSTOM_CONTENT
            network.resources.conflict-policy=LOCAL_WINS
            network.path-sync.ids=luckperms,server-settings
            network.path-sync.luckperms.enabled=true
            network.path-sync.luckperms.name=LuckPerms
            network.path-sync.luckperms.paths=plugins/LuckPerms
            network.path-sync.luckperms.conflict-policy=LOCAL_WINS
            network.path-sync.luckperms.command-count=1
            network.path-sync.luckperms.command.0=lp reload
            network.path-sync.server-settings.enabled=false
            network.path-sync.server-settings.name=Server Settings
            network.path-sync.server-settings.paths=server.properties
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
        assertTrue(config.chatEnabled());
        assertEquals(ReSyncNetworkAgentConfig.SelectionMode.ALLOW_LIST, config.chat().channelMode());
        assertEquals(45000, config.chat().retentionMillis());
        assertTrue(config.chat().includes("GLOBAL"));
        assertTrue(config.chat().includes("staff"));
        assertFalse(config.chat().includes("local"));
        assertTrue(config.resourcesEnabled());
        assertEquals(ReSyncNetworkAgentConfig.SelectionMode.DENY_LIST, config.resources().typeMode());
        assertEquals(ReSyncNetworkAgentConfig.ResourceConflictPolicy.LOCAL_WINS, config.resources().conflictPolicy());
        assertFalse(config.resources().includes("world"));
        assertFalse(config.resources().includes("custom_content"));
        assertTrue(config.resources().includes("chat"));
        assertTrue(config.pathsEnabled());
        assertEquals(2, config.pathSyncs().size());
        assertEquals(Set.of("plugins/LuckPerms"), config.pathSyncs().getFirst().entries());
        assertEquals(ReSyncNetworkAgentConfig.ResourceConflictPolicy.LOCAL_WINS, config.pathSyncs().getFirst().conflictPolicy());
        assertEquals("lp reload", config.pathSyncs().getFirst().commands().getFirst());
        assertFalse(config.pathSyncs().get(1).enabled());
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
        assertFalse(config.chatEnabled());
        assertFalse(config.resourcesEnabled());
        assertFalse(config.pathsEnabled());
        assertEquals(ReSyncNetworkAgentConfig.SelectionMode.ALL, config.chat().channelMode());
        assertEquals(120000, config.chat().retentionMillis());
        assertEquals(ReSyncNetworkAgentConfig.SelectionMode.ALL, config.resources().typeMode());
        assertEquals(ReSyncNetworkAgentConfig.ResourceConflictPolicy.NETWORK_WINS, config.resources().conflictPolicy());

        ReSyncNetworkAgentConfig.ResourcePolicy chatResources = config.resources().withIncluded("CHAT");
        assertTrue(chatResources.enabled());
        assertTrue(chatResources.includes("chat"));
        assertFalse(chatResources.includes("world"));
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

    @Test
    void rejectsInvalidNetworkPolicies() throws Exception {
        Files.writeString(directory.resolve("resync.properties"), "network.chat.channel-mode=SOME_CHANNELS");

        assertThrows(IllegalArgumentException.class, () -> ReSyncNetworkAgentConfig.load(directory));

        Files.writeString(directory.resolve("resync.properties"), """
            network.path-sync.ids=unsafe
            network.path-sync.unsafe.enabled=true
            network.path-sync.unsafe.name=Unsafe
            network.path-sync.unsafe.paths=../secrets
            """);

        assertThrows(IllegalArgumentException.class, () -> ReSyncNetworkAgentConfig.load(directory));

        Files.writeString(directory.resolve("resync.properties"), """
            network.path-sync.ids=everything
            network.path-sync.everything.enabled=true
            network.path-sync.everything.name=Everything
            network.path-sync.everything.paths=.
            """);

        assertEquals(Set.of("."), ReSyncNetworkAgentConfig.load(directory).pathSyncs().getFirst().entries());

        Files.writeString(directory.resolve("resync.properties"), """
            network.path-sync.ids=everything,plugin
            network.path-sync.everything.enabled=true
            network.path-sync.everything.name=Everything
            network.path-sync.everything.paths=.
            network.path-sync.plugin.enabled=true
            network.path-sync.plugin.name=Plugin
            network.path-sync.plugin.paths=plugins/LuckPerms
            """);

        assertThrows(IllegalArgumentException.class, () -> ReSyncNetworkAgentConfig.load(directory));

        Files.writeString(directory.resolve("resync.properties"), "network.chat.retention-millis=0");

        assertThrows(IllegalArgumentException.class, () -> ReSyncNetworkAgentConfig.load(directory));

        Files.writeString(directory.resolve("resync.properties"), "network.chat.retention-millis=31536000001");

        assertThrows(IllegalArgumentException.class, () -> ReSyncNetworkAgentConfig.load(directory));
    }
}
