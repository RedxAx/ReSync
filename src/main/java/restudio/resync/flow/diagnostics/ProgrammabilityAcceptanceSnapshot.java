package restudio.resync.flow.diagnostics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Map;

public final class ProgrammabilityAcceptanceSnapshot {
    public static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private ProgrammabilityAcceptanceSnapshot() {
    }

    public static JsonObject create(Map<String, Object> registryDiagnostics, Map<String, Object> readiness, String serverVersion,
                                    String pluginVersion, Instant capturedAt) {
        Map<String, Object> diagnostics = registryDiagnostics != null ? registryDiagnostics : Map.of();
        Map<String, Object> serverReadiness = readiness != null ? readiness : Map.of();
        JsonObject checks = new JsonObject();
        checks.addProperty("inventoryComplete", Boolean.TRUE.equals(diagnostics.get("inventoryComplete")));
        checks.addProperty("registryParity", Boolean.TRUE.equals(diagnostics.get("parity")));
        checks.addProperty("noRejectedDefinitions", number(diagnostics.get("rejectedDefinitions")) == 0L);
        checks.addProperty("noMissingHandlers", empty(diagnostics.get("missingHandlers")));
        checks.addProperty("noMissingOperations", empty(diagnostics.get("missingOperations")));
        checks.addProperty("noMissingCatalogs", empty(diagnostics.get("missingCatalogs")));
        checks.addProperty("clientConnected", number(serverReadiness.get("connectedClients")) > 0L);

        boolean ready = checks.entrySet().stream().allMatch(entry -> entry.getValue().getAsBoolean());
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("schemaVersion", SCHEMA_VERSION);
        snapshot.addProperty("capturedAt", (capturedAt != null ? capturedAt : Instant.now()).toString());
        snapshot.addProperty("serverVersion", serverVersion != null ? serverVersion : "");
        snapshot.addProperty("pluginVersion", pluginVersion != null ? pluginVersion : "");
        snapshot.addProperty("ready", ready);
        snapshot.add("checks", checks);
        snapshot.add("readiness", GSON.toJsonTree(serverReadiness));
        snapshot.add("registryDiagnostics", GSON.toJsonTree(diagnostics));
        return snapshot;
    }

    public static Path write(Path directory, Map<String, Object> registryDiagnostics, Map<String, Object> readiness, String serverVersion,
                             String pluginVersion, Instant capturedAt) throws IOException {
        Instant timestamp = capturedAt != null ? capturedAt : Instant.now();
        Files.createDirectories(directory);
        Path target = directory.resolve("programmability-acceptance-" + FILE_TIMESTAMP.format(timestamp) + ".json");
        Path temporary = directory.resolve(target.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(create(registryDiagnostics, readiness, serverVersion, pluginVersion, timestamp)));
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private static boolean empty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        return String.valueOf(value).isBlank();
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value != null ? Long.parseLong(String.valueOf(value)) : 0L;
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }
}
