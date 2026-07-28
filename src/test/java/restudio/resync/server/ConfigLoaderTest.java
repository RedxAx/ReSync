package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void configGeneratesApiKeyAndPersistsBindHostDefault() throws Exception {
        Path config = tempDir.resolve("plugins/ReSync/config.properties");
        ReSyncConfig loaded = ConfigLoader.load(config.toString());
        String content = Files.readString(config);

        assertFalse(loaded.getApiKey().isBlank());
        assertEquals(12441, loaded.getPort());
        assertEquals("127.0.0.1", loaded.getBindHost());
        assertFalse(content.contains("config.yml"));
    }

    @Test
    void publicBindWithoutExplicitEnablementDisablesApi() throws Exception {
        Path config = tempDir.resolve("public.properties");
        Files.writeString(config, "api-key=secret\nbind-host=0.0.0.0\npublic-bind-enabled=false\n");

        ReSyncConfig loaded = ConfigLoader.load(config.toString());

        assertFalse(loaded.isEnabled());
    }

    @Test
    void publicBindWithExplicitEnablementStaysEnabled() throws Exception {
        Path config = tempDir.resolve("public-enabled.properties");
        Files.writeString(config, "api-key=secret\nbind-host=0.0.0.0\npublic-bind-enabled=true\n");

        ReSyncConfig loaded = ConfigLoader.load(config.toString());

        assertTrue(loaded.isEnabled());
    }
}
