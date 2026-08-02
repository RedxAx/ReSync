package restudio.resync.replacement.evidence.fixture.sanitize;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FullFolderFixtureSanitizationTest {
    private static final Path FIXTURE = Path.of("src", "test", "resources", "fixtures", "node-replacement", "full-folder");
    private static final Path SOURCE = Path.of("run", "plugins", "ReSync");

    @Test
    void sourceHashesAndSanitizedStructureArePinnedWithoutCopyingSourceValues() throws Exception {
        JsonObject manifest = JsonParser.parseString(Files.readString(FIXTURE.resolve("fixture-manifest.json"))).getAsJsonObject();
        JsonArray sourceFiles = manifest.getAsJsonObject("source").getAsJsonArray("files");
        assertEquals(10, sourceFiles.size());
        assertEquals(10, manifest.getAsJsonArray("sanitizedFiles").size());
        assertEquals(10, manifest.getAsJsonObject("source").get("fileCount").getAsInt());
        long totalBytes = 0;
        for (JsonElement element : sourceFiles) {
            JsonObject entry = element.getAsJsonObject();
            Path source = SOURCE.resolve(entry.get("path").getAsString());
            Path sanitized = FIXTURE.resolve(entry.get("sanitizedPath").getAsString());
            assertTrue(Files.isRegularFile(source));
            assertTrue(Files.isRegularFile(sanitized));
            assertEquals(entry.get("sha256").getAsString(), sha256(source));
            assertEquals(entry.get("size").getAsLong(), Files.size(source));
            totalBytes += Files.size(source);
            assertRetainedShape(entry, sanitized);
        }
        assertEquals(manifest.getAsJsonObject("source").get("totalBytes").getAsLong(), totalBytes);
        for (JsonElement element : manifest.getAsJsonArray("sanitizedFiles")) assertFileEntry(FIXTURE, element.getAsJsonObject());
        assertNoSharedSourceValues(SOURCE, FIXTURE.resolve("sanitized-real"));
    }

    @Test
    void populatedFixtureHasEveryRequiredParticipantAndNoSecretMarkers() throws Exception {
        JsonObject manifest = JsonParser.parseString(Files.readString(FIXTURE.resolve("fixture-manifest.json"))).getAsJsonObject();
        String expectedManifestHash = Files.readString(FIXTURE.resolve("manifest.sha256")).split("\\s+")[0];
        assertEquals(expectedManifestHash, sha256(FIXTURE.resolve("fixture-manifest.json")));
        JsonArray participants = manifest.getAsJsonArray("participants");
        assertEquals(14, participants.size());
        for (JsonElement element : participants) {
            JsonObject participant = element.getAsJsonObject();
            assertTrue(participant.has("owner"));
            assertTrue(participant.has("path"));
            assertTrue(participant.has("size"));
            assertTrue(participant.has("sha256"));
            Path path = FIXTURE.resolve(participant.get("path").getAsString());
            assertTrue(Files.exists(path));
            assertEquals(participant.get("size").getAsLong(), participantSize(path));
            assertEquals(participant.get("sha256").getAsString(), participantHash(path));
        }
        try (var paths = Files.walk(FIXTURE)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8).toLowerCase();
                assertFalse(content.contains("password="));
                assertFalse(content.contains("token="));
                assertFalse(content.contains("https://"));
                assertFalse(content.contains("http://"));
            }
        }
    }

    private void assertNoSharedSourceValues(Path sourceRoot, Path sanitizedRoot) throws IOException {
        Set<String> sourceValues = new HashSet<>();
        try (var paths = Files.walk(sourceRoot)) {
            for (Path source : paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".json")).toList()) {
                collectValues(JsonParser.parseString(Files.readString(source)), sourceValues);
            }
        }
        try (var paths = Files.walk(sanitizedRoot)) {
            for (Path sanitized : paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".json")).toList()) {
                Set<String> sanitizedValues = new HashSet<>();
                collectValues(JsonParser.parseString(Files.readString(sanitized)), sanitizedValues);
                assertTrue(sanitizedValues.stream().filter(value -> value.length() >= 4).noneMatch(sourceValues::contains));
            }
        }
    }

    private void collectValues(JsonElement element, Set<String> values) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            values.add(element.getAsString());
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) collectValues(item, values);
            return;
        }
        if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) collectValues(entry.getValue(), values);
        }
    }

    private void assertFileEntry(Path root, JsonObject entry) throws IOException, NoSuchAlgorithmException {
        Path file = root.resolve(entry.get("path").getAsString());
        assertTrue(Files.isRegularFile(file));
        assertEquals(entry.get("size").getAsLong(), Files.size(file));
        assertEquals(entry.get("sha256").getAsString(), sha256(file));
    }

    private void assertRetainedShape(JsonObject entry, Path sanitized) throws IOException {
        JsonObject shape = entry.getAsJsonObject("shape");
        String root = shape.get("root").getAsString();
        if ("properties".equals(root) || "key-value".equals(root)) {
            long keys = Files.readAllLines(sanitized).stream().filter(line -> line.matches("\\s*[^#;\\s][^:=]*[=:].*")).count();
            assertEquals(shape.get("keyCount").getAsLong(), keys);
            return;
        }
        JsonElement document = JsonParser.parseString(Files.readString(sanitized));
        assertEquals(root, document.isJsonObject() ? "object" : "array");
        if (document.isJsonObject()) {
            List<String> actual = document.getAsJsonObject().keySet().stream().sorted().toList();
            List<String> expected = new ArrayList<>();
            for (JsonElement value : shape.getAsJsonArray("topLevel")) expected.add(value.getAsString());
            expected.sort(String::compareTo);
            assertEquals(expected, actual);
        } else {
            assertEquals(shape.get("length").getAsInt(), document.getAsJsonArray().size());
        }
    }

    private long participantSize(Path path) throws IOException {
        if (Files.isRegularFile(path)) return Files.size(path);
        try (var paths = Files.walk(path)) {
            return paths.filter(Files::isRegularFile).mapToLong(this::size).sum();
        }
    }

    private String participantHash(Path path) throws IOException, NoSuchAlgorithmException {
        Path populated = FIXTURE.resolve("populated");
        List<Path> files;
        if (Files.isRegularFile(path)) {
            files = List.of(path);
        } else {
            try (var paths = Files.walk(path)) {
                files = paths.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString)).toList();
            }
        }
        List<String> records = new ArrayList<>();
        for (Path file : files) records.add(sha256(file) + "  " + populated.relativize(file).toString().replace('\\', '/'));
        return sha256(String.join("\n", records) + "\n");
    }

    private long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        return hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private String sha256(String value) throws NoSuchAlgorithmException {
        return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String hex(byte[] digest) {
        StringBuilder result = new StringBuilder();
        for (byte value : digest) result.append(String.format("%02x", value));
        return result.toString();
    }
}
