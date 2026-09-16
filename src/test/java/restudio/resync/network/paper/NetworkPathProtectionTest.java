package restudio.resync.network.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkPathProtectionTest {
    @TempDir
    Path temporary;

    @Test
    void theEntireResyncDataSubtreeIsAnExcludedPathAuthority() {
        Path data = temporary.resolve("plugin-data");
        assertTrue(NetworkPathSynchronizer.isReSyncDataPath(data, data));
        assertTrue(NetworkPathSynchronizer.isReSyncDataPath(data.resolve("network/resource-manifest.json"), data));
        assertFalse(NetworkPathSynchronizer.isReSyncDataPath(temporary.resolve("server/plugins/LuckPerms"), data));
    }
}
