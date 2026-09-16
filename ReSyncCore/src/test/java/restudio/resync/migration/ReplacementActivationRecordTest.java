package restudio.resync.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplacementActivationRecordTest {
    @TempDir
    Path temporary;

    @Test
    void writesAndReadsTheCanonicalActivationBinding() throws Exception {
        ReplacementActivationRecord.Values expected = new ReplacementActivationRecord.Values(
            7, "a".repeat(64), 3, "b".repeat(64), 2, "c".repeat(64));

        ReplacementActivationRecord.write(temporary, expected);

        assertEquals(expected, ReplacementActivationRecord.read(temporary));
    }

    @Test
    void rejectsWhitespaceTrailingAndTruncatedRecords() throws Exception {
        ReplacementActivationRecord.Values values = new ReplacementActivationRecord.Values(
            1, "a".repeat(64), 1, "b".repeat(64), 1, "c".repeat(64));
        ReplacementActivationRecord.write(temporary, values);
        Path record = ReplacementActivationRecord.path(temporary);
        String canonical = Files.readString(record);

        Files.writeString(record, canonical + "\n");
        assertThrows(MigrationException.class, () -> ReplacementActivationRecord.read(temporary));

        Files.writeString(record, canonical.replace("catalog-generation=1", "catalog-generation=1 "));
        assertThrows(MigrationException.class, () -> ReplacementActivationRecord.read(temporary));

        Files.writeString(record, canonical.substring(0, canonical.length() - 1));
        assertThrows(MigrationException.class, () -> ReplacementActivationRecord.read(temporary));
    }

}
