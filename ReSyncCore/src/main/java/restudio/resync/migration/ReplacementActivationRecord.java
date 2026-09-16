package restudio.resync.migration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public final class ReplacementActivationRecord {
    public static final String RECORD_FILE = "assets/.migrations/replacement-activation.record";
    public static final int FORMAT_VERSION = 1;

    public record Values(long catalogGeneration, String catalogChecksum,
                         int runtimeBindingManifestVersion, String runtimeBindingManifestHash,
                         int participantReadinessVersion, String participantReadinessHash) {
        public Values {
            if (catalogGeneration < 1) {
                throw new IllegalArgumentException("catalogGeneration Must Be Positive");
            }
            catalogChecksum = MigrationCanonical.requireDigest(catalogChecksum, "catalogChecksum");
            if (runtimeBindingManifestVersion < 1) {
                throw new IllegalArgumentException("runtimeBindingManifestVersion Must Be Positive");
            }
            runtimeBindingManifestHash = MigrationCanonical.requireDigest(runtimeBindingManifestHash, "runtimeBindingManifestHash");
            if (participantReadinessVersion < 1) {
                throw new IllegalArgumentException("participantReadinessVersion Must Be Positive");
            }
            participantReadinessHash = MigrationCanonical.requireDigest(participantReadinessHash, "participantReadinessHash");
        }
    }

    private ReplacementActivationRecord() {
    }

    public static Path path(Path root) {
        return MigrationPaths.resolveInside(MigrationPaths.requirePath(root, "root"), RECORD_FILE);
    }

    public static boolean exists(Path root) {
        Path record = path(root);
        return Files.isRegularFile(record, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(record);
    }

    public static void write(Path root, Values values) throws IOException {
        MigrationPaths.requireDirectory(root, "root");
        Values verified = new Values(values.catalogGeneration(), values.catalogChecksum(),
            values.runtimeBindingManifestVersion(), values.runtimeBindingManifestHash(),
            values.participantReadinessVersion(), values.participantReadinessHash());
        String canonical = canonical(verified);
        AtomicFiles.write(path(root), (canonical + "record-hash=" + MigrationCanonical.sha256(canonical) + "\n")
            .getBytes(StandardCharsets.UTF_8));
    }

    public static Values read(Path root) throws IOException {
        MigrationPaths.requireDirectory(root, "root");
        Path record = path(root);
        if (Files.isSymbolicLink(record) || !Files.isRegularFile(record, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Replacement Activation Record Is Missing");
        }
        if (Files.size(record) > 4096) {
            throw new MigrationException("Replacement Activation Record Is Too Large");
        }
        String content = decodeUtf8(Files.readAllBytes(record));
        if (content.indexOf('\r') >= 0 || !content.endsWith("\n")) {
            throw new MigrationException("Replacement Activation Record Is Not Canonical");
        }
        List<String> lines = Arrays.asList(content.split("\n", -1));
        if (lines.size() != 10 || !("format=" + FORMAT_VERSION).equals(lines.getFirst()) || !"state=ACTIVE".equals(lines.get(1))) {
            throw new MigrationException("Replacement Activation Record Header Is Invalid");
        }
        long generation = longValue(lines, 2, "catalog-generation=");
        String catalogChecksum = value(lines, 3, "catalog-checksum=");
        int runtimeVersion = integer(lines, 4, "runtime-binding-manifest-version=");
        String runtimeHash = value(lines, 5, "runtime-binding-manifest-hash=");
        int readinessVersion = integer(lines, 6, "participant-readiness-version=");
        String readinessHash = value(lines, 7, "participant-readiness-hash=");
        String recordHash = value(lines, 8, "record-hash=");
        if (!lines.get(9).isEmpty()) {
            throw new MigrationException("Replacement Activation Record Has Trailing Data");
        }
        Values values = new Values(generation, catalogChecksum, runtimeVersion, runtimeHash, readinessVersion, readinessHash);
        MigrationCanonical.requireDigest(recordHash, "recordHash");
        String expected = canonical(values) + "record-hash=" + MigrationCanonical.sha256(canonical(values)) + "\n";
        if (!expected.equals(content) || !MigrationCanonical.sha256(canonical(values)).equals(recordHash)) {
            throw new MigrationException("Replacement Activation Record Hash Does Not Match");
        }
        return values;
    }

    private static String canonical(Values values) {
        return "format=" + FORMAT_VERSION + "\nstate=ACTIVE\ncatalog-generation=" + values.catalogGeneration()
            + "\ncatalog-checksum=" + values.catalogChecksum()
            + "\nruntime-binding-manifest-version=" + values.runtimeBindingManifestVersion()
            + "\nruntime-binding-manifest-hash=" + values.runtimeBindingManifestHash()
            + "\nparticipant-readiness-version=" + values.participantReadinessVersion()
            + "\nparticipant-readiness-hash=" + values.participantReadinessHash() + "\n";
    }

    private static String value(List<String> lines, int index, String prefix) throws MigrationException {
        if (index >= lines.size() || !lines.get(index).startsWith(prefix)) {
            throw new MigrationException("Missing Replacement Activation Record Field: " + prefix);
        }
        return lines.get(index).substring(prefix.length());
    }

    private static long longValue(List<String> lines, int index, String prefix) throws MigrationException {
        try {
            return Long.parseLong(value(lines, index, prefix));
        } catch (NumberFormatException exception) {
            throw new MigrationException("Invalid Replacement Activation Record Number: " + prefix, exception);
        }
    }

    private static int integer(List<String> lines, int index, String prefix) throws MigrationException {
        try {
            return Integer.parseInt(value(lines, index, prefix));
        } catch (NumberFormatException exception) {
            throw new MigrationException("Invalid Replacement Activation Record Number: " + prefix, exception);
        }
    }

    private static String decodeUtf8(byte[] bytes) throws MigrationException {
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
            if (!Arrays.equals(value.getBytes(StandardCharsets.UTF_8), bytes)) {
                throw new MigrationException("Replacement Activation Record Encoding Is Not Canonical");
            }
            return value;
        } catch (CharacterCodingException exception) {
            throw new MigrationException("Replacement Activation Record Encoding Is Invalid", exception);
        }
    }
}
