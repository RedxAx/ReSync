package restudio.resync.network.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.LegacyInstallBoundary;
import restudio.resync.migration.MigrationException;
import restudio.resync.network.paper.state.NetworkPlayerStateConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkSettingsTest {
    @TempDir
    Path plugins;

    @Test
    void preservesOnlyMatchingGeneratedSettingsBeforeFreshBootstrap() throws Exception {
        Path operator = Files.createDirectory(plugins.resolve("ReSync"));
        Path coordination = plugins.resolve(".resync-coordination");
        String previous = "network.enabled=true\nnetwork.id=network\nnetwork.node-id=lobby\nnetwork.enrollment-token=old-token\n";
        Files.writeString(operator.resolve("resync.properties"), previous);
        Files.writeString(plugins.resolve(NetworkSettings.FILE_NAME), "network.config-version=1\nnetwork.id=network\nnetwork.node-id=lobby\nnetwork.enabled=false\n");

        NetworkSettings.prepareBootstrap(operator, coordination);

        assertFalse(Files.exists(operator));
        assertEquals(previous, Files.readString(plugins.resolve(NetworkSettings.LEGACY_FILE_NAME)));
        assertFalse(LegacyInstallBoundary.prepare(operator, coordination).archived());
        assertFalse(ReSyncNetworkAgentConfig.load(operator).enabled());
        NetworkSettings.prepareBootstrap(operator, coordination);
        assertEquals(previous, Files.readString(plugins.resolve(NetworkSettings.LEGACY_FILE_NAME)));
    }

    @Test
    void neverMovesActualLegacyDataOrSettingsForAnotherNetwork() throws Exception {
        Path operator = Files.createDirectory(plugins.resolve("ReSync"));
        Path coordination = plugins.resolve(".resync-coordination");
        String previous = "network.id=old-network\nnetwork.node-id=lobby\n";
        Files.writeString(operator.resolve("resync.properties"), previous);
        Files.writeString(plugins.resolve(NetworkSettings.FILE_NAME), "network.config-version=1\nnetwork.id=new-network\nnetwork.node-id=lobby\n");
        NetworkSettings.prepareBootstrap(operator, coordination);
        assertEquals(previous, Files.readString(operator.resolve("resync.properties")));
        assertFalse(LegacyInstallBoundary.prepare(operator, coordination).archived());
        assertEquals(previous, Files.readString(operator.resolve("resync.properties")));
        assertEquals("network.config-version=1\nnetwork.id=new-network\nnetwork.node-id=lobby\n",
            Files.readString(plugins.resolve(NetworkSettings.FILE_NAME)));

        Files.writeString(plugins.resolve(NetworkSettings.FILE_NAME), "network.config-version=1\nnetwork.id=old-network\nnetwork.node-id=lobby\n");
        Files.writeString(operator.resolve("resync.properties"), previous + "enabled=true\n");
        NetworkSettings.prepareBootstrap(operator, coordination);
        assertTrue(Files.readString(operator.resolve("resync.properties")).contains("enabled=true"));
        Files.writeString(operator.resolve("resync.properties"), previous);
        Files.createDirectory(coordination);
        NetworkSettings.prepareBootstrap(operator, coordination);
        assertEquals(previous, Files.readString(operator.resolve("resync.properties")));
        Files.delete(coordination);
        Files.createDirectory(operator.resolve("assets"));
        NetworkSettings.prepareBootstrap(operator, coordination);
        assertTrue(Files.exists(operator.resolve("assets")));
        assertFalse(Files.exists(plugins.resolve(NetworkSettings.LEGACY_FILE_NAME)));
        assertFalse(LegacyInstallBoundary.prepare(operator, coordination).archived());
        assertTrue(Files.isDirectory(operator.resolve("assets")));
        Path legacy = operator.resolve("assets/legacy.json");
        Files.writeString(legacy, "preserve");
        NetworkSettings.prepareBootstrap(operator, coordination);
        assertThrows(MigrationException.class, () -> LegacyInstallBoundary.prepare(operator, coordination));
        assertEquals("preserve", Files.readString(legacy));
        assertEquals(previous, Files.readString(operator.resolve("resync.properties")));
        assertFalse(Files.exists(plugins.resolve(NetworkSettings.LEGACY_FILE_NAME)));
    }

    @Test
    void managedSettingsPermitFreshBootstrapAndSurviveAnExplicitLegacyArchive() throws Exception {
        Path operator = plugins.resolve("ReSync");
        Path coordination = plugins.resolve(".resync-coordination");
        Path managed = plugins.resolve(NetworkSettings.FILE_NAME);
        String settings = """
            network.config-version=1
            network.enabled=true
            network.id=network-one
            network.node-id=lobby
            network.hub-url=ws://127.0.0.1:12442
            network.enrollment-token=test-enrollment
            network.transfer.profile=SURVIVAL_SHARED
            network.transfer.realm=survival
            """;
        Files.writeString(managed, settings);

        assertFalse(LegacyInstallBoundary.prepare(operator, coordination).archived());
        assertFalse(Files.exists(operator));
        Properties admitted = NetworkSettings.load(operator);
        ReSyncNetworkAgentConfig agent = ReSyncNetworkAgentConfig.load(operator, operator, admitted);
        NetworkPlayerStateConfig playerState = NetworkPlayerStateConfig.load(admitted);
        assertTrue(agent.enabled());
        assertEquals("test-enrollment", agent.enrollmentToken());
        assertTrue(playerState.inventory());
        assertEquals(agent.nodeId(), playerState.nodeId());

        Files.createDirectories(operator.resolve("assets"));
        Files.writeString(operator.resolve("assets/legacy.json"), "preserve");
        assertThrows(MigrationException.class, () -> LegacyInstallBoundary.prepare(operator, coordination));
        Files.writeString(plugins.resolve(LegacyInstallBoundary.RESET_MARKER), "");
        LegacyInstallBoundary.Result archived = LegacyInstallBoundary.prepare(operator, coordination);
        assertTrue(archived.archived());
        assertEquals("preserve", Files.readString(archived.dataBackup().resolve("assets/legacy.json")));
        assertEquals(settings, Files.readString(managed));
        assertTrue(ReSyncNetworkAgentConfig.load(operator).enabled());
    }

    @Test
    void managedSettingsReplaceLegacySettingsWithoutResurrectingRemovedValues() throws Exception {
        Path operator = Files.createDirectory(plugins.resolve("ReSync"));
        Files.writeString(operator.resolve("resync.properties"), "network.enabled=true\nnetwork.id=old-network\nnetwork.enrollment-token=old-token\n");
        assertEquals("old-network", NetworkSettings.load(operator).getProperty("network.id"));
        Files.writeString(plugins.resolve(NetworkSettings.FILE_NAME), "network.config-version=1\nnetwork.enabled=false\n");

        ReSyncNetworkAgentConfig disabled = ReSyncNetworkAgentConfig.load(operator);
        assertFalse(disabled.enabled());
        assertEquals("", disabled.networkId());
        assertEquals("", disabled.enrollmentToken());
    }

    @Test
    void rejectsUnsupportedUnboundedAndLinkedManagedSettings() throws Exception {
        Path operator = plugins.resolve("ReSync");
        Path managed = plugins.resolve(NetworkSettings.FILE_NAME);
        Files.writeString(managed, "network.config-version=2\n");
        assertThrows(IOException.class, () -> NetworkSettings.load(operator));
        Files.writeString(managed, "network.config-version=1\nother.setting=value\n");
        assertThrows(IOException.class, () -> NetworkSettings.load(operator));
        Files.writeString(managed, "x".repeat(1_048_577));
        assertThrows(IOException.class, () -> NetworkSettings.load(operator));
        Files.delete(managed);
        Path original = plugins.resolve("original.properties");
        Files.writeString(original, "network.enabled=false\n");
        Files.createSymbolicLink(managed, original);
        assertThrows(IOException.class, () -> NetworkSettings.load(operator));
        assertEquals("network.enabled=false\n", Files.readString(original));
    }
}
