package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.PersistenceOwnershipContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationPersistenceParticipantTest {
    @TempDir
    Path temporary;

    @Test
    void quiesceRejectsPropertyMutationAndResumeReopensAdmission() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("source"));
        ConfigurationPersistenceParticipant participant = new ConfigurationPersistenceParticipant(dataRoot);
        Properties initial = properties("port", "12441");
        participant.replaceProperties(initial);
        participant.flush();

        participant.quiesce();

        assertTrue(participant.isQuiesced());
        assertThrows(IllegalStateException.class, () -> participant.replaceProperties(properties("port", "12442")));

        participant.resume();
        participant.replaceProperties(properties("port", "12442"));
        participant.flush();

        assertFalse(participant.isQuiesced());
        assertEquals("12442", participant.loadProperties().getProperty("port"));
    }

    @Test
    void rebindRequiresQuiescenceAndFailedCandidatesKeepThePreviousFile() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("source"));
        ConfigurationPersistenceParticipant participant = new ConfigurationPersistenceParticipant(dataRoot);
        participant.replaceProperties(properties("bind-host", "127.0.0.1"));
        participant.flush();
        Path previousFile = participant.root();

        Path replacement = Files.createDirectory(temporary.resolve("replacement"));
        Files.writeString(replacement.resolve(ConfigurationPersistenceParticipant.FILE_NAME), "bind-host=0.0.0.0\n");

        assertThrows(IOException.class, () -> participant.rebind(replacement));
        participant.quiesce();

        Path malformed = Files.createDirectory(temporary.resolve("malformed"));
        Files.writeString(malformed.resolve(ConfigurationPersistenceParticipant.FILE_NAME), "broken=\\u\n");
        assertThrows(IOException.class, () -> participant.rebind(malformed));
        assertEquals(previousFile, participant.root());

        participant.rebind(replacement);
        assertEquals(replacement.resolve(ConfigurationPersistenceParticipant.FILE_NAME).toAbsolutePath().normalize(), participant.root());
        assertEquals("0.0.0.0", participant.loadProperties().getProperty("bind-host"));
        participant.resume();
    }

    @Test
    void flushUsesAtomicPropertiesOutputAndHealthDetectsExternalChanges() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("source"));
        ConfigurationPersistenceParticipant participant = new ConfigurationPersistenceParticipant(dataRoot);
        participant.replaceProperties(properties("enabled", "true"));
        participant.flush();

        Path file = dataRoot.resolve(ConfigurationPersistenceParticipant.FILE_NAME);
        assertTrue(Files.isRegularFile(file));
        assertTrue(Files.readString(file).contains("enabled=true"));
        try (var paths = Files.list(dataRoot)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }

        participant.healthCheck();
        Files.writeString(file, "enabled=false\n");
        assertThrows(IOException.class, participant::healthCheck);
    }

    @Test
    void failedResumeKeepsConfigurationWritesQuiesced() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("source"));
        ConfigurationPersistenceParticipant participant = new ConfigurationPersistenceParticipant(dataRoot);
        participant.replaceProperties(properties("enabled", "true"));
        participant.flush();
        participant.quiesce();

        Files.writeString(dataRoot.resolve(ConfigurationPersistenceParticipant.FILE_NAME), "enabled=false\n");

        assertThrows(IOException.class, participant::resume);
        assertTrue(participant.isQuiesced());
        assertThrows(IllegalStateException.class, () -> participant.replaceProperties(properties("enabled", "true")));
    }

    @Test
    void configLoaderStagesDefaultsUntilTheLifecycleWriterIsActivated() throws Exception {
        Path config = temporary.resolve("plugins/ReSync/config.properties");

        ReSyncConfig loaded = ConfigLoader.load(config.toString());

        assertFalse(Files.exists(config));
        assertFalse(loaded.getApiKey().isBlank());
        assertEquals(config.toAbsolutePath().normalize(), loaded.getPersistenceParticipant().root());
        assertTrue(loaded.getPersistenceParticipant().durabilityDeferred());
        assertEquals("127.0.0.1", loaded.getPersistenceParticipant().properties().getProperty("bind-host"));

        loaded.getPersistenceParticipant().activateAndFlush();

        assertTrue(Files.isRegularFile(config));
        assertEquals("127.0.0.1", new ConfigurationPersistenceParticipant(config.getParent()).loadProperties().getProperty("bind-host"));
    }

    @Test
    void deferredActivationPreservesExistingUnknownPropertiesAndBytesUntilCommit() throws Exception {
        Path config = temporary.resolve("plugins/ReSync/config.properties");
        Files.createDirectories(config.getParent());
        String existing = "# keep this file stable until activation\ncustom.unknown=value\nenabled=true\n";
        Files.writeString(config, existing);

        ReSyncConfig loaded = ConfigLoader.load(config.toString());

        assertEquals(existing, Files.readString(config));
        assertEquals("value", loaded.getPersistenceParticipant().properties().getProperty("custom.unknown"));

        loaded.getPersistenceParticipant().activateAndFlush();

        Properties persisted = new ConfigurationPersistenceParticipant(config.getParent()).loadProperties();
        assertEquals("value", persisted.getProperty("custom.unknown"));
        assertEquals("true", persisted.getProperty("enabled"));
    }

    @Test
    void deferredFlushesStagePropertiesWithoutCreatingTheFile() throws Exception {
        Path config = temporary.resolve("plugins/ReSync/config.properties");
        ReSyncConfig loaded = ConfigLoader.load(config.toString());
        Properties replacement = loaded.getPersistenceParticipant().properties();
        replacement.setProperty("flow.default-tab.id", "main");

        loaded.getPersistenceParticipant().replacePropertiesAndFlush(replacement);

        assertFalse(Files.exists(config));
        assertEquals("main", loaded.getPersistenceParticipant().properties().getProperty("flow.default-tab.id"));
    }

    @Test
    void failedReplaceAndFlushRestoresTheLastDurableProperties() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("source"));
        ConfigurationPersistenceParticipant participant = new ConfigurationPersistenceParticipant(dataRoot);
        participant.replaceProperties(properties("enabled", "true"));
        participant.flush();

        Path file = dataRoot.resolve(ConfigurationPersistenceParticipant.FILE_NAME);
        Files.delete(file);
        Files.createDirectory(file);

        assertThrows(IOException.class, () -> participant.replacePropertiesAndFlush(properties("enabled", "false")));
        assertEquals("true", participant.properties().getProperty("enabled"));
    }

    @Test
    void ownershipIndexClaimsOnlyTheConfigurationFile() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("ownership"));
        ConfigurationPersistenceParticipant participant = new ConfigurationPersistenceParticipant(dataRoot);
        var index = participant.ownershipIndex(new PersistenceOwnershipContext(dataRoot, participant.root()));

        assertTrue(index.owns(ConfigurationPersistenceParticipant.FILE_NAME));
        assertFalse(index.owns(ConfigurationPersistenceParticipant.FILE_NAME + ".tmp"));
        assertFalse(index.owns(ConfigurationPersistenceParticipant.FILE_NAME + "/nested"));
    }

    private Properties properties(String key, String value) {
        Properties properties = new Properties();
        properties.setProperty(key, value);
        return properties;
    }
}
