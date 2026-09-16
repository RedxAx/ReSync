package restudio.resync.upgrade.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.migration.ProductionPersistenceOwners;

class FullFolderFixtureIntegrityTest {
    private static final String FIXTURE_ROOT = "fixtures/node-replacement/full-folder";

    @Test
    void retainedManifestVerifiesEveryFixtureFileAndCanonicalOwner() throws Exception {
        Path fixtureRoot = fixtureRoot();
        Path manifestPath = fixtureRoot.resolve("fixture-manifest.json");
        byte[] manifest = Files.readAllBytes(manifestPath);
        String pin = Files.readString(fixtureRoot.resolve("manifest.sha256"), StandardCharsets.UTF_8).stripTrailing();

        assertTrue(pin.matches("[0-9a-f]{64}  fixture-manifest\\.json"));
        assertEquals(pin.substring(0, 64), CanonicalHash.rawSha256(manifest));

        Map<?, ?> document = assertInstanceOf(Map.class, CanonicalJson.parse(manifest));
        List<?> participants = assertInstanceOf(List.class, document.get("participants"));
        List<String> owners = new ArrayList<>();
        for (Object value : participants) {
            owners.add(text(object(value), "owner"));
        }
        assertTrue(owners.contains(ProductionPersistenceOwners.FLOW_ASSETS));
        assertTrue(owners.contains(ProductionPersistenceOwners.TRIGGERS));
        assertFalse(owners.contains("flow-storage"));
        assertFalse(owners.contains("triggers"));

        verifySanitizedFiles(document, fixtureRoot);
        verifyParticipants(document, fixtureRoot);
    }

    private static void verifySanitizedFiles(Map<?, ?> document, Path fixtureRoot) throws IOException {
        Set<String> declared = new HashSet<>();
        for (Object value : list(document, "sanitizedFiles")) {
            Map<?, ?> entry = object(value);
            String relative = text(entry, "path");
            assertTrue(declared.add(relative), "Duplicate sanitized fixture path: " + relative);
            Path file = resolve(fixtureRoot, relative);
            assertTrue(Files.isRegularFile(file), "Missing sanitized fixture file: " + relative);
            assertEquals(number(entry, "size"), Files.size(file), relative + " size");
            assertEquals(text(entry, "sha256"), sha256(file), relative + " hash");
        }
        assertEquals(relativeFiles(fixtureRoot.resolve("sanitized-real"), fixtureRoot), declared);
        for (Object value : list(object(document.get("source")), "files")) {
            assertTrue(declared.contains(text(object(value), "sanitizedPath")));
        }
    }

    private static void verifyParticipants(Map<?, ?> document, Path fixtureRoot) throws IOException {
        Path populated = fixtureRoot.resolve("populated");
        Set<String> declared = new HashSet<>();
        Set<String> covered = new HashSet<>();
        for (Object value : list(document, "participants")) {
            Map<?, ?> entry = object(value);
            String relative = text(entry, "path");
            assertTrue(declared.add(relative), "Duplicate participant path: " + relative);
            Path target = resolve(fixtureRoot, relative);
            assertTrue(Files.exists(target), "Missing participant path: " + relative);
            ParticipantDigest actual = participantDigest(target, populated);
            assertEquals(number(entry, "size"), actual.size(), relative + " size");
            assertEquals(text(entry, "sha256"), actual.hash(), relative + " hash");
            for (String file : actual.files()) {
                assertTrue(covered.add(file), "Participant overlap: " + file);
            }
        }
        assertEquals(relativeFiles(populated, populated), covered);
    }

    private static ParticipantDigest participantDigest(Path target, Path populated) throws IOException {
        List<Path> files;
        if (Files.isDirectory(target)) {
            try (var stream = Files.walk(target)) {
                files = stream.filter(Files::isRegularFile).toList();
            }
        } else {
            files = List.of(target);
        }
        List<String> records = new ArrayList<>();
        Set<String> relativeFiles = new HashSet<>();
        long size = 0;
        for (Path file : files) {
            String relative = populated.relativize(file).toString().replace('\\', '/');
            relativeFiles.add(relative);
            size += Files.size(file);
            records.add(sha256(file) + "  " + relative);
        }
        records.sort(String::compareTo);
        String canonical = String.join("\n", records) + "\n";
        return new ParticipantDigest(size, CanonicalHash.rawSha256(canonical.getBytes(StandardCharsets.UTF_8)), relativeFiles);
    }

    private static Set<String> relativeFiles(Path root, Path base) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                .map(path -> base.relativize(path).toString().replace('\\', '/'))
                .collect(Collectors.toSet());
        }
    }

    private static Path fixtureRoot() throws IOException {
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path root : List.of(
            workingDirectory.resolve("src/test/resources"),
            workingDirectory.resolve("../src/test/resources").normalize())) {
            Path candidate = root.resolve(FIXTURE_ROOT).normalize();
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IOException("Missing fixture root: " + FIXTURE_ROOT);
    }

    private static Path resolve(Path root, String relative) {
        Path path = root.resolve(relative).normalize();
        assertTrue(path.startsWith(root), "Fixture path escapes root: " + relative);
        return path;
    }

    private static String sha256(Path file) throws IOException {
        return CanonicalHash.rawSha256(Files.readAllBytes(file));
    }

    private static Map<?, ?> object(Object value) {
        return assertInstanceOf(Map.class, value);
    }

    private static List<?> list(Map<?, ?> value, String key) {
        return assertInstanceOf(List.class, value.get(key));
    }

    private static String text(Map<?, ?> value, String key) {
        return assertInstanceOf(String.class, value.get(key));
    }

    private static long number(Map<?, ?> value, String key) {
        return assertInstanceOf(Number.class, value.get(key)).longValue();
    }

    private record ParticipantDigest(long size, String hash, Set<String> files) {
    }
}
