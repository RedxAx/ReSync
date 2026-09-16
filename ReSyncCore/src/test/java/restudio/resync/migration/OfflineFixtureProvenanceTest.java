package restudio.resync.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import restudio.resync.flow.canonical.CanonicalJson;

class OfflineFixtureProvenanceTest {
    private static final String MANIFEST_RESOURCE = "fixtures/node-replacement/migration/offline-graph-overlay/provenance-manifest.json";
    private static final String ZERO_DIGEST = "0".repeat(64);

    @Test
    void provenanceManifestMatchesFixtureTreesFilesAndExpectedActions() throws Exception {
        Map<?, ?> manifest = object(CanonicalJson.parse(Files.readAllBytes(resource(MANIFEST_RESOURCE))));

        assertEquals(1, number(manifest, "format"));
        assertEquals("offline-graph-overlay", text(manifest, "fixture"));
        assertEquals(List.of(
            "retain-sanitized-real-source",
            "copy-canonical-flow-to-replacement",
            "quarantine-derived-legacy-overlay",
            "preserve-original-source-archive"), texts(manifest, "expectedActions"));

        List<?> sources = list(manifest, "sources");
        assertEquals(Set.of("sanitized-real", "synthetic-supplement", "derived-overlay"), sources.stream()
            .map(value -> text(object(value), "id"))
            .collect(java.util.stream.Collectors.toSet()));
        for (Object value : sources) {
            verifySource(object(value));
        }
    }

    private void verifySource(Map<?, ?> source) throws IOException {
        String id = text(source, "id");
        String rootName = text(source, "root");
        String treeHash = text(source, "treeHash");
        List<?> files = list(source, "files");
        if (rootName.equals("none")) {
            assertEquals("synthetic-supplement", id);
            assertEquals(ZERO_DIGEST, treeHash);
            assertEquals(List.of(), files);
            assertEquals("none", text(source, "action"));
            return;
        }
        Path root = resource(rootName);
        assertEquals(treeHash, TreeDigest.of(root));
        for (Object value : files) {
            Map<?, ?> file = object(value);
            Path path = MigrationPaths.resolveInside(root, text(file, "path"));
            assertEquals(text(file, "sha256"), sha256(path));
            assertEquals(text(file, "action"), switch (id) {
                case "sanitized-real" -> "copy-to-replacement";
                case "derived-overlay" -> "quarantine-legacy-duplicate";
                default -> throw new MigrationException("Unexpected Provenance Source: " + id);
            });
        }
    }

    private static Path resource(String name) throws IOException {
        try {
            var url = OfflineFixtureProvenanceTest.class.getClassLoader().getResource(name);
            assertNotNull(url, "Missing fixture resource: " + name);
            return Path.of(url.toURI());
        } catch (URISyntaxException exception) {
            throw new IOException("Invalid fixture resource URI: " + name, exception);
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path);
                 DigestInputStream stream = new DigestInputStream(input, digest)) {
                stream.transferTo(OutputStream.nullOutputStream());
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new MigrationException("SHA-256 Is Unavailable", exception);
        }
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

    private static int number(Map<?, ?> value, String key) {
        return assertInstanceOf(Number.class, value.get(key)).intValue();
    }

    private static List<String> texts(Map<?, ?> value, String key) {
        return list(value, key).stream().map(item -> assertInstanceOf(String.class, item)).toList();
    }
}
