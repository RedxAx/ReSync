package restudio.resync.flow.diagnostics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgrammabilityAcceptanceSnapshotTest {
    @TempDir
    Path directory;

    @Test
    void readySnapshotRequiresEveryProductionRegistryCheck() {
        JsonObject snapshot = ProgrammabilityAcceptanceSnapshot.create(readyDiagnostics(), Map.of("connectedClients", 1), "Paper", "1.0", Instant.EPOCH);

        assertTrue(snapshot.get("ready").getAsBoolean());
        assertEquals("1970-01-01T00:00:00Z", snapshot.get("capturedAt").getAsString());
        assertTrue(snapshot.getAsJsonObject("checks").get("clientConnected").getAsBoolean());

        JsonObject disconnected = ProgrammabilityAcceptanceSnapshot.create(readyDiagnostics(), Map.of("connectedClients", 0), "Paper", "1.0", Instant.EPOCH);
        assertFalse(disconnected.get("ready").getAsBoolean());
    }

    @Test
    void snapshotWriteIsAtomicAndReloadable() throws IOException {
        Path result = ProgrammabilityAcceptanceSnapshot.write(directory, readyDiagnostics(), Map.of("connectedClients", 2), "Paper", "1.0",
            Instant.parse("2026-07-17T12:34:56Z"));

        assertEquals("programmability-acceptance-20260717-123456.json", result.getFileName().toString());
        assertTrue(Files.exists(result));
        assertFalse(Files.exists(directory.resolve(result.getFileName() + ".tmp")));
        JsonObject persisted = JsonParser.parseString(Files.readString(result)).getAsJsonObject();
        assertTrue(persisted.get("ready").getAsBoolean());
        assertEquals(ProgrammabilityAcceptanceSnapshot.SCHEMA_VERSION, persisted.get("schemaVersion").getAsInt());
    }

    private Map<String, Object> readyDiagnostics() {
        return Map.of(
            "inventoryComplete", true,
            "parity", true,
            "rejectedDefinitions", 0,
            "missingHandlers", List.of(),
            "missingOperations", List.of(),
            "missingCatalogs", List.of()
        );
    }
}
