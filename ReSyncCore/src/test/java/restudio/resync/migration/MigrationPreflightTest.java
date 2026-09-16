package restudio.resync.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationPreflightTest {
    @TempDir
    Path temporary;

    @Test
    void rejectsInsufficientStagingSpaceFromSingleDeterministicProbe() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Files.write(source.resolve("payload.bin"), new byte[4]);
        Path staging = temporary.resolve("missing-parent/staging");
        PersistenceParticipantRegistry participants = participants(source);
        AtomicInteger probes = new AtomicInteger();
        MigrationPreflight preflight = new MigrationPreflight(path -> {
            probes.incrementAndGet();
            assertEquals(temporary.toAbsolutePath().normalize(), path);
            return 9;
        });

        PreflightResult result = preflight.inspect(source, staging, participants, 6);

        assertFalse(result.passed());
        assertTrue(check(result, "source-root").passed());
        assertTrue(check(result, "staging-root").passed());
        assertTrue(check(result, "participants").passed());
        assertTrue(check(result, "source-tree").passed());
        assertEquals(new PreflightResult.Check(
            "disk-space",
            false,
            "Insufficient Staging Space: Required 10, Available 9"), check(result, "disk-space"));
        assertEquals(1, probes.get());
    }

    private static PreflightResult.Check check(PreflightResult result, String name) {
        return result.checks().stream().filter(check -> check.name().equals(name)).findFirst().orElseThrow();
    }

    private static PersistenceParticipantRegistry participants(Path source) {
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return "core";
            }

            @Override
            public Path root() {
                return source;
            }

            @Override
            public void flush() {
            }

            @Override
            public void quiesce() {
            }

            @Override
            public void resume() {
            }
        });
        return participants;
    }
}
