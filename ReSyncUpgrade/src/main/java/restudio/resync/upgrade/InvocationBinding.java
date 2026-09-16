package restudio.resync.upgrade;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.migration.AtomicFiles;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.MigrationPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

public final class InvocationBinding {
    private static final String DOMAIN = "resync.offline-upgrader.invocation";
    private static final String HASH_SUFFIX = "\ninvocation-hash=";
    private static final int MAX_BYTES = 65536;

    private InvocationBinding() {
    }

    public static String digest(String canonical) {
        return CanonicalJson.sha256(DOMAIN, requireCanonical(canonical));
    }

    public static void write(Path path, String canonical) throws IOException {
        Path normalized = MigrationPaths.requirePath(path, "invocationPath");
        String expected = requireCanonical(canonical);
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            verify(normalized, expected);
            return;
        }
        String content = expected + HASH_SUFFIX + digest(expected) + "\n";
        AtomicFiles.write(normalized, content.getBytes(StandardCharsets.UTF_8));
    }

    public static void verify(Path path, String expectedCanonical) throws IOException {
        String expected = requireCanonical(expectedCanonical);
        Parsed parsed = read(path);
        if (!parsed.canonical().equals(expected)) {
            throw new MigrationException("Retained Offline Upgrade Invocation Does Not Match The Current Invocation");
        }
    }

    public static void verifyDigest(Path path, String expectedHash) throws IOException {
        String expected = requireDigest(expectedHash, "invocationHash");
        Parsed parsed = read(path);
        if (!parsed.hash().equals(expected)) {
            throw new MigrationException("Retained Offline Upgrade Invocation Digest Does Not Match The Bound Plan");
        }
    }

    private static Parsed read(Path path) throws IOException {
        Path normalized = MigrationPaths.requirePath(path, "invocationPath");
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            throw new MigrationException("Retained Offline Upgrade Invocation Is Missing");
        }
        byte[] bytes = Files.readAllBytes(normalized);
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES || !Arrays.equals(bytes, content.getBytes(StandardCharsets.UTF_8))) {
            throw new MigrationException("Retained Offline Upgrade Invocation Encoding Is Invalid");
        }
        int marker = content.lastIndexOf(HASH_SUFFIX);
        if (marker <= 0 || !content.endsWith("\n")
            || content.indexOf(HASH_SUFFIX, marker + HASH_SUFFIX.length()) >= 0) {
            throw new MigrationException("Retained Offline Upgrade Invocation Hash Is Missing");
        }
        String canonical = content.substring(0, marker);
        String hash = content.substring(marker + HASH_SUFFIX.length(), content.length() - 1);
        if (!digest(canonical).equals(hash)) {
            throw new MigrationException("Retained Offline Upgrade Invocation Hash Does Not Match Content");
        }
        try {
            if (!CanonicalJson.canonicalize(CanonicalJson.parse(canonical)).equals(canonical)) {
                throw new MigrationException("Retained Offline Upgrade Invocation Is Not Canonical");
            }
        } catch (RuntimeException exception) {
            throw new MigrationException("Retained Offline Upgrade Invocation Is Not Canonical", exception);
        }
        return new Parsed(canonical, hash);
    }

    private static String requireCanonical(String value) {
        String canonical = Objects.requireNonNull(value, "canonical");
        if (canonical.isBlank() || canonical.indexOf('\n') >= 0 || canonical.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Invocation Canonical Text Is Invalid");
        }
        if (!CanonicalJson.canonicalize(CanonicalJson.parse(canonical)).equals(canonical)) {
            throw new IllegalArgumentException("Invocation Canonical Text Is Not Canonical");
        }
        return canonical;
    }

    private static String requireDigest(String value, String field) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(field + " Must Be A SHA-256 Digest");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private record Parsed(String canonical, String hash) {
    }
}
