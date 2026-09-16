package restudio.resync.restore;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.MigrationJournal;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.StagedMigration;

import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class RestoreJournalDetail {
    private static final String PREFIX = "restore-detail-v2:";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final StagedMigration staged;
    private final String manifestHash;

    private RestoreJournalDetail(StagedMigration staged, String manifestHash) {
        this.staged = staged;
        this.manifestHash = ContentHash.of(manifestHash).canonicalText();
    }

    static RestoreJournalDetail of(StagedMigration staged, String manifestHash) {
        return new RestoreJournalDetail(staged, manifestHash);
    }

    static Optional<RestoreJournalDetail> find(List<MigrationJournal.Entry> entries) {
        for (int index = entries.size() - 1; index >= 0; index--) {
            String detail = entries.get(index).detail();
            if (detail.startsWith(PREFIX)) {
                return Optional.of(parse(detail));
            }
        }
        return Optional.empty();
    }

    StagedMigration staged() {
        return staged;
    }

    String manifestHash() {
        return manifestHash;
    }

    String encode() {
        Map<String, Object> value = Map.of(
            "contentHash", staged.contentHash(),
            "format", 1,
            "manifestHash", manifestHash,
            "planHash", staged.planHash(),
            "previousRoot", staged.previousRoot().map(Path::toString).orElse("-"),
            "stagedRoot", staged.root().toString());
        return PREFIX + ENCODER.encodeToString(CanonicalJson.canonicalBytes(value));
    }

    private static RestoreJournalDetail parse(String value) {
        try {
            Object parsed = CanonicalJson.parse(DECODER.decode(value.substring(PREFIX.length())));
            if (!(parsed instanceof Map<?, ?> fields) || integer(fields.get("format")) != 1) {
                throw new MigrationException("Restore Journal Detail Format Is Invalid");
            }
            Path stagedRoot = path(fields.get("stagedRoot"), "stagedRoot");
            String previousValue = text(fields.get("previousRoot"), "previousRoot");
            Optional<Path> previousRoot = previousValue.equals("-") ? Optional.empty() : Optional.of(MigrationPaths.requirePath(Path.of(previousValue), "previousRoot"));
            StagedMigration staged = new StagedMigration(stagedRoot, previousRoot, text(fields.get("planHash"), "planHash"), text(fields.get("contentHash"), "contentHash"));
            return new RestoreJournalDetail(staged, text(fields.get("manifestHash"), "manifestHash"));
        } catch (MigrationException exception) {
            throw new IllegalArgumentException("Restore Journal Detail Is Invalid", exception);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Restore Journal Detail Is Invalid", exception);
        }
    }

    private static Path path(Object value, String field) {
        return MigrationPaths.requirePath(Path.of(text(value, field)), field);
    }

    private static String text(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Restore Journal Detail Field Is Invalid: " + field);
        }
        return text;
    }

    private static int integer(Object value) {
        if (!(value instanceof Number number) || number.longValue() != 1L || number.doubleValue() != 1.0d) {
            throw new IllegalArgumentException("Restore Journal Detail Format Is Invalid");
        }
        return 1;
    }
}
