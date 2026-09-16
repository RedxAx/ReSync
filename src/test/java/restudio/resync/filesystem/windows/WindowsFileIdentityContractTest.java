package restudio.resync.filesystem.windows;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsFileIdentityContractTest {
    private static final byte[] FILE_ID = {
        1, 2, 3, 4, 5, 6, 7, 8,
        9, 10, 11, 12, 13, 14, 15, 16
    };

    @Test
    void fakeBackendReturnsImmutableObservationAndNormalizesPath() throws IOException {
        Path requested = Path.of(".").resolve("contract").resolve("..").resolve("contract.txt");
        Path normalized = requested.toAbsolutePath().normalize();
        WindowsFileIdentity.FileIdInfo fileId = new WindowsFileIdentity.FileIdInfo(12L, FILE_ID, false);
        WindowsFileIdentity.Observation expected = new WindowsFileIdentity.Observation(
            normalized,
            fileId,
            false,
            true,
            false,
            false,
            0);
        WindowsFileIdentity.Backend backend = new WindowsFileIdentity.Backend() {
            @Override
            public WindowsFileIdentity.Observation observe(Path path) {
                assertEquals(normalized, path);
                return expected;
            }

            @Override
            public void flush(Path path) {
                assertEquals(normalized, path);
            }
        };

        WindowsFileIdentity identity = new WindowsFileIdentity(backend);
        WindowsFileIdentity.Observation actual = identity.observe(requested);
        identity.flush(requested);

        assertEquals(normalized, actual.normalizedPath());
        assertEquals(fileId, actual.fileIdInfo());
        assertTrue(actual.regularFile());
        assertFalse(actual.directory());
        assertFalse(actual.other());
        byte[] copy = actual.fileIdInfo().identifier();
        copy[0] = 99;
        assertTrue(Arrays.equals(FILE_ID, actual.fileIdInfo().identifier()));
    }

    @Test
    void invalidObservationFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> new WindowsFileIdentity.Observation(
            Path.of("file"),
            new WindowsFileIdentity.FileIdInfo(12L, FILE_ID, false),
            false,
            false,
            false,
            false,
            0));
        assertThrows(IllegalArgumentException.class, () -> new WindowsFileIdentity.FileIdInfo(12L, new byte[16], false));
    }
}
