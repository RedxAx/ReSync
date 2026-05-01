package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ConfigLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void configGeneratesApiKeyAndPersistsBindHostDefault() throws Exception {
        Path config = tempDir.resolve("config.properties");
        ReSyncConfig loaded = ConfigLoader.load(config.toString());
        String content = Files.readString(config);

        assertFalse(loaded.getApiKey().isBlank());
        assertEquals("127.0.0.1", loaded.getBindHost());
        assertFalse(content.contains("config.yml"));
    }
}
