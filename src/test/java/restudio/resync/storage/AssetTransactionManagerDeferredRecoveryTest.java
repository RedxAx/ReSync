package restudio.resync.storage;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetTransactionManagerDeferredRecoveryTest {
    @TempDir
    Path tempDir;

    @Test
    void deferredModeRequiresTheExactInspectedPreparedFingerprintBeforeApplying() throws Exception {
        Path root = tempDir.resolve("assets");
        Path target = root.resolve("asset.json");
        AssetTransactionManager automatic = new AssetTransactionManager(root, new Gson());
        String transactionId = automatic.commit(Map.of(target, "committed"), "deferred-recovery");
        Path journalFile = root.resolve(".transactions").resolve(transactionId).resolve("journal.json");
        JsonObject journal = JsonParser.parseString(Files.readString(journalFile)).getAsJsonObject();
        journal.addProperty("state", "PREPARED");
        Files.writeString(journalFile, journal.toString());
        Files.writeString(target, "sentinel");

        AssetTransactionManager deferred = new AssetTransactionManager(root, new Gson(), AssetTransactionManager.RecoveryMode.DEFERRED);
        AssetTransactionManager.TransactionInspection inspection = deferred.inspectTransactions().getFirst();

        assertEquals("sentinel", Files.readString(target));
        assertThrows(IOException.class, () -> deferred.recoverValidated(Map.of(transactionId, "0".repeat(64))));
        assertEquals("sentinel", Files.readString(target));

        deferred.recoverValidated(Map.of(transactionId, inspection.visibility().descriptor().fingerprint()));
        assertEquals("committed", Files.readString(target));
    }
}
