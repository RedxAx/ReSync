package restudio.resync.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public final class CommittedReplacementRepairJournal {
    public static final int FORMAT_VERSION = 2;

    public enum State {
        PREPARED,
        ARCHIVED,
        INSTALLED,
        MARKER_PUBLISHED
    }

    public record Values(
        String migrationId,
        String planHash,
        String activeReplacementDigest,
        String expectedReplacementDigest,
        String archivedSourceDigest,
        State state,
        String corruptArchiveDigest,
        String activeMarkerHash
    ) {
        public Values(
            String migrationId,
            String planHash,
            String activeReplacementDigest,
            String expectedReplacementDigest,
            String archivedSourceDigest,
            State state,
            String corruptArchiveDigest
        ) {
            this(migrationId, planHash, activeReplacementDigest, expectedReplacementDigest, archivedSourceDigest,
                state, corruptArchiveDigest, "");
        }

        public Values {
            migrationId = MigrationCanonical.requireText(migrationId, "migrationId");
            planHash = MigrationCanonical.requireDigest(planHash, "planHash");
            activeReplacementDigest = MigrationCanonical.requireDigest(activeReplacementDigest, "activeReplacementDigest");
            expectedReplacementDigest = MigrationCanonical.requireDigest(expectedReplacementDigest, "expectedReplacementDigest");
            archivedSourceDigest = MigrationCanonical.requireDigest(archivedSourceDigest, "archivedSourceDigest");
            if (state == null) {
                throw new IllegalArgumentException("repairState Is Required");
            }
            corruptArchiveDigest = corruptArchiveDigest == null || corruptArchiveDigest.isBlank()
                ? "" : MigrationCanonical.requireDigest(corruptArchiveDigest, "corruptArchiveDigest");
            if (!corruptArchiveDigest.isEmpty() && !corruptArchiveDigest.equals(activeReplacementDigest)) {
                throw new IllegalArgumentException("corruptArchiveDigest Must Match activeReplacementDigest");
            }
            activeMarkerHash = activeMarkerHash == null || activeMarkerHash.isBlank()
                ? "" : MigrationCanonical.requireDigest(activeMarkerHash, "activeMarkerHash");
            if (state != State.PREPARED && corruptArchiveDigest.isEmpty()) {
                throw new IllegalArgumentException("corruptArchiveDigest Is Required After PREPARED");
            }
        }

        public Values withState(State next, String nextCorruptArchiveDigest) {
            return new Values(migrationId, planHash, activeReplacementDigest, expectedReplacementDigest,
                archivedSourceDigest, next, nextCorruptArchiveDigest, activeMarkerHash);
        }
    }

    private CommittedReplacementRepairJournal() {
    }

    public static Path path(Path controlRoot, String migrationId) {
        Path root = MigrationPaths.requirePath(controlRoot, "controlRoot");
        String id = MigrationCanonical.requireText(migrationId, "migrationId");
        return root.resolve("journals").resolve(id + "-repair.journal").toAbsolutePath().normalize();
    }

    public static void write(Path path, Values values) throws IOException {
        Path normalized = MigrationPaths.requirePath(path, "repairJournalPath");
        Values verified = new Values(values.migrationId(), values.planHash(), values.activeReplacementDigest(),
            values.expectedReplacementDigest(), values.archivedSourceDigest(), values.state(), values.corruptArchiveDigest(),
            values.activeMarkerHash());
        String canonical = canonical(verified);
        AtomicFiles.write(normalized, (canonical + "repair-hash=" + MigrationCanonical.sha256(canonical) + "\n")
            .getBytes(StandardCharsets.UTF_8));
    }

    public static Values read(Path path) throws IOException {
        Path normalized = MigrationPaths.requirePath(path, "repairJournalPath");
        if (Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Committed Replacement Repair Journal Is Not A Regular File");
        }
        if (Files.size(normalized) > 8192) {
            throw new MigrationException("Committed Replacement Repair Journal Is Too Large");
        }
        String content = decodeUtf8(Files.readAllBytes(normalized));
        List<String> lines = Arrays.asList(content.split("\n", -1));
        if (lines.isEmpty() || !lines.getLast().isEmpty()) {
            throw new MigrationException("Committed Replacement Repair Journal Must End With A Newline");
        }
        lines = lines.subList(0, lines.size() - 1);
        if (lines.size() != 10 || !("format=" + FORMAT_VERSION).equals(lines.getFirst())) {
            throw new MigrationException("Committed Replacement Repair Journal Header Is Invalid");
        }
        String storedHash = MigrationCanonical.requireDigest(raw(lines, 9, "repair-hash="), "repairHash");
        Values values;
        try {
            values = new Values(
                MigrationCanonical.decode(raw(lines, 1, "migration-id=")),
                raw(lines, 2, "plan-hash="),
                raw(lines, 3, "active-replacement-digest="),
                raw(lines, 4, "expected-replacement-digest="),
                raw(lines, 5, "archived-source-digest="),
                State.valueOf(raw(lines, 6, "state=")),
                raw(lines, 7, "corrupt-archive-digest="),
                raw(lines, 8, "active-marker-hash="));
        } catch (IllegalArgumentException exception) {
            throw new MigrationException("Committed Replacement Repair Journal Values Are Invalid", exception);
        }
        String canonical = canonical(values);
        if (!MigrationCanonical.sha256(canonical).equals(storedHash)
            || !(canonical + "repair-hash=" + storedHash + "\n").equals(content)) {
            throw new MigrationException("Committed Replacement Repair Journal Hash Does Not Match Content");
        }
        return values;
    }

    private static String canonical(Values values) {
        return "format=" + FORMAT_VERSION + "\n"
            + "migration-id=" + MigrationCanonical.encode(values.migrationId()) + "\n"
            + "plan-hash=" + values.planHash() + "\n"
            + "active-replacement-digest=" + values.activeReplacementDigest() + "\n"
            + "expected-replacement-digest=" + values.expectedReplacementDigest() + "\n"
            + "archived-source-digest=" + values.archivedSourceDigest() + "\n"
            + "state=" + values.state() + "\n"
            + "corrupt-archive-digest=" + values.corruptArchiveDigest() + "\n"
            + "active-marker-hash=" + values.activeMarkerHash() + "\n";
    }

    private static String raw(List<String> lines, int index, String prefix) throws MigrationException {
        if (index >= lines.size() || !lines.get(index).startsWith(prefix)) {
            throw new MigrationException("Missing Committed Replacement Repair Journal Line: " + prefix);
        }
        return lines.get(index).substring(prefix.length());
    }

    private static String decodeUtf8(byte[] bytes) throws MigrationException {
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
            if (!Arrays.equals(value.getBytes(StandardCharsets.UTF_8), bytes) || value.indexOf('\r') >= 0) {
                throw new MigrationException("Committed Replacement Repair Journal Encoding Is Not Canonical");
            }
            return value;
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new MigrationException("Committed Replacement Repair Journal Encoding Is Invalid", exception);
        }
    }
}
