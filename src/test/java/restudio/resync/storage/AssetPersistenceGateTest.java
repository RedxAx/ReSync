package restudio.resync.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetPersistenceGateTest {
    @Test
    void rebindRequiresQuiescenceAndPublishesTheNormalizedScopeAtomically(@TempDir Path temporary) throws Exception {
        Path current = Files.createDirectory(temporary.resolve("current"));
        Path candidate = Files.createDirectory(temporary.resolve("candidate"));
        AssetPersistenceGate gate = new AssetPersistenceGate(current);

        assertThrows(IllegalStateException.class, () -> gate.rebind(candidate));
        assertEquals(current.toAbsolutePath().normalize(), gate.scopeRoot());

        gate.quiesce();
        gate.rebind(candidate.resolve(".").normalize());
        assertEquals(candidate.toAbsolutePath().normalize(), gate.scopeRoot());

        gate.resume();
        assertThrows(IllegalStateException.class, () -> gate.rebind(current));
        assertEquals(candidate.toAbsolutePath().normalize(), gate.scopeRoot());
    }
}
